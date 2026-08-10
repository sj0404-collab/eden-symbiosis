// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "common/logging.h"
#include "common/symbiosis/abi_shim.h"
#include "common/symbiosis/device_profiles.h"
#include "common/symbiosis/driver_broker.h"
#include "common/symbiosis/memory_governor.h"
#include "common/symbiosis/selftest.h"
#include "common/symbiosis/thermal_monitor.h"

namespace Symbiosis {

namespace {

constexpr u64 kMiB = 1024ULL * 1024ULL;

void Add(SelfTestReport& report, std::string name, CheckStatus status, std::string detail,
         std::string advice = {}) {
    switch (status) {
    case CheckStatus::Pass:
        report.passed++;
        break;
    case CheckStatus::Warn:
        report.warned++;
        break;
    case CheckStatus::Fail:
        report.failed++;
        break;
    case CheckStatus::Skipped:
        break;
    }
    report.results.push_back(CheckResult{std::move(name), status, std::move(detail),
                                         std::move(advice)});
}

} // Anonymous namespace

const char* ToString(CheckStatus status) {
    switch (status) {
    case CheckStatus::Pass:
        return "PASS";
    case CheckStatus::Warn:
        return "WARN";
    case CheckStatus::Fail:
        return "FAIL";
    case CheckStatus::Skipped:
        return "SKIP";
    }
    return "?";
}

std::string SelfTestReport::ToText() const {
    std::string out;
    for (const auto& result : results) {
        out += "[";
        out += ToString(result.status);
        out += "] " + result.name + "\n";
        if (!result.detail.empty()) {
            out += "      " + result.detail + "\n";
        }
        if (!result.advice.empty()) {
            out += "      -> " + result.advice + "\n";
        }
    }
    out += "\n" + std::to_string(passed) + " passed, " + std::to_string(warned) +
           " warnings, " + std::to_string(failed) + " failed\n";
    return out;
}

SelfTestReport RunSelfTest() {
    SelfTestReport report;

    // --- Driver layer ----------------------------------------------------
    auto& broker = GetDriverBroker();
    const auto& providers = broker.Providers();

    if (providers.empty()) {
        Add(report, "Driver discovery", CheckStatus::Warn,
            "The broker has not run yet.",
            "Start a game once: drivers are surveyed when the GPU is initialised.");
    } else {
        std::size_t usable = 0;
        std::size_t dead = 0;
        for (const auto& provider : providers) {
            if (provider.Usable()) {
                usable++;
            }
            if (provider.health == Health::Dead) {
                dead++;
            }
        }

        if (usable == 0) {
            Add(report, "Driver discovery", CheckStatus::Fail,
                std::to_string(providers.size()) + " candidate(s), none usable.",
                "Emulation will not start. Check the log for dlopen failures.");
        } else if (usable == 1) {
            Add(report, "Driver discovery", CheckStatus::Pass,
                "1 usable driver (system). No alternates to borrow from.",
                "Normal for devices that reject custom drivers -- Symbiosis will "
                "use emulated fallbacks instead of borrowing.");
        } else {
            Add(report, "Driver discovery", CheckStatus::Pass,
                std::to_string(usable) + " usable drivers -- cross-provider borrowing is active.");
        }

        if (dead > 0) {
            Add(report, "Rejected drivers", CheckStatus::Warn,
                std::to_string(dead) + " candidate(s) failed to load.",
                "Usually a driver built for a different GPU. Harmless, but they do nothing.");
        }

        if (auto* primary = broker.Primary()) {
            Add(report, "Primary driver", CheckStatus::Pass,
                primary->name + " (" + ToString(primary->family) + ", " +
                    ToString(primary->origin) + ")");
        }

        // Capability coverage.
        u32 covered = 0;
        u32 emulated = 0;
        for (std::size_t i = 0; i < kCapabilityCount; ++i) {
            if (broker.Supports(static_cast<Capability>(i))) {
                covered++;
            } else {
                emulated++;
            }
        }
        Add(report, "Capability coverage",
            covered >= 4 ? CheckStatus::Pass : CheckStatus::Warn,
            std::to_string(covered) + " of " + std::to_string(kCapabilityCount) +
                " served by a driver, " + std::to_string(emulated) + " by fallback.",
            covered >= 4 ? "" : "Low coverage means more CPU-side emulation and lower FPS.");
    }

    // --- ABI shim --------------------------------------------------------
    {
        const auto borrowed = GetAbiShim().CrossProviderCount();
        if (borrowed > 0) {
            Add(report, "Cross-binary symbol borrowing", CheckStatus::Pass,
                std::to_string(borrowed) + " entry point(s) borrowed from a secondary driver.");
        } else {
            Add(report, "Cross-binary symbol borrowing", CheckStatus::Pass,
                "Nothing needed borrowing -- the primary driver covers what was requested.");
        }
    }

    // --- Memory ----------------------------------------------------------
    {
        auto& governor = GetMemoryGovernor();
        governor.Initialise();

        const u64 budget = governor.Budget();
        const u64 used = governor.Used();

        Add(report, "Memory budget", budget > 0 ? CheckStatus::Pass : CheckStatus::Fail,
            "Budget " + std::to_string(budget / kMiB) + " MiB, currently using " +
                std::to_string(used / kMiB) + " MiB.");

        const auto pressure = governor.Pressure();
        Add(report, "Memory pressure",
            pressure == MemoryPressure::Relaxed || pressure == MemoryPressure::Elevated
                ? CheckStatus::Pass
                : CheckStatus::Warn,
            std::string{ToString(pressure)},
            pressure == MemoryPressure::Critical || pressure == MemoryPressure::Emergency
                ? "Close background apps before launching a heavy title."
                : "");

        if (governor.ExtendedMemoryLayoutSafe()) {
            Add(report, "6 GB memory layout", CheckStatus::Pass,
                "Enough budget to enable the extended layout.");
        } else {
            Add(report, "6 GB memory layout", CheckStatus::Warn,
                "Blocked: the app's honest budget is below 5.5 GiB.",
                "Correct for an 8 GB device -- enabling it anyway would cause OOM kills.");
        }
    }

    // --- Thermal ---------------------------------------------------------
    {
        const auto thermal = GetThermalMonitor().Sample();
        CheckStatus status = CheckStatus::Pass;
        switch (thermal.state) {
        case ThermalState::Unknown:
            status = CheckStatus::Skipped;
            break;
        case ThermalState::Cool:
        case ThermalState::Warm:
            status = CheckStatus::Pass;
            break;
        case ThermalState::Throttling:
            status = CheckStatus::Warn;
            break;
        case ThermalState::Critical:
            status = CheckStatus::Fail;
            break;
        }
        Add(report, "Thermal state", status, thermal.summary, thermal.advice);
    }

    // --- Tuning profiles -------------------------------------------------
    {
        auto& engine = GetProfileEngine();
        GpuFamily family = GpuFamily::Unknown;
        DriverOrigin origin = DriverOrigin::System;
        if (auto* primary = GetDriverBroker().Primary()) {
            family = primary->family;
            origin = primary->origin;
        }
        const auto matches = engine.ProfilesFor(family, origin);
        Add(report, "Tuning profiles", CheckStatus::Pass,
            std::to_string(matches.size()) + " profile(s) match this hardware (" +
                ToString(family) + ").");
    }

    LOG_INFO(Common, "[Symbiosis] self-test: {} pass, {} warn, {} fail", report.passed,
             report.warned, report.failed);
    return report;
}

} // namespace Symbiosis
