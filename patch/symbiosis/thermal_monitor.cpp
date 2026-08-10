// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <filesystem>
#include <fstream>
#include <string>

#include "common/logging.h"
#include "common/symbiosis/thermal_monitor.h"

namespace Symbiosis {

namespace {

/// Zones whose names indicate they track the SoC rather than the battery,
/// charger or skin. Matching is substring-based because vendors are creative.
constexpr const char* kRelevantZoneHints[] = {
    "cpu", "gpu", "soc", "tsens", "mtktscpu", "mtktsbattery" /* excluded below */,
    "apc",  "silver", "gold", "prime", "big", "little", "npu",
};

constexpr const char* kIgnoredZoneHints[] = {
    "battery", "charger", "usb", "pa_therm", "skin", "quiet", "case",
};

std::string ReadFileTrimmed(const std::string& path) {
    std::ifstream file{path};
    if (!file.is_open()) {
        return {};
    }
    std::string value;
    std::getline(file, value);
    while (!value.empty() && (value.back() == '\n' || value.back() == '\r' || value.back() == ' ')) {
        value.pop_back();
    }
    return value;
}

bool ContainsAny(const std::string& haystack, const char* const* needles, std::size_t count) {
    for (std::size_t i = 0; i < count; ++i) {
        if (haystack.find(needles[i]) != std::string::npos) {
            return true;
        }
    }
    return false;
}

s64 ReadNumber(const std::string& path) {
    const std::string text = ReadFileTrimmed(path);
    if (text.empty()) {
        return -1;
    }
    try {
        return static_cast<s64>(std::stoll(text));
    } catch (...) {
        return -1;
    }
}

} // Anonymous namespace

const char* ToString(ThermalState state) {
    switch (state) {
    case ThermalState::Unknown:
        return "Unknown";
    case ThermalState::Cool:
        return "Cool";
    case ThermalState::Warm:
        return "Warm";
    case ThermalState::Throttling:
        return "Throttling";
    case ThermalState::Critical:
        return "Critical";
    }
    return "Unknown";
}

ThermalMonitor::ThermalMonitor() = default;

void ThermalMonitor::DiscoverZones() {
    std::error_code ec;
    const std::string base = "/sys/class/thermal";
    if (!std::filesystem::is_directory(base, ec)) {
        return;
    }

    for (const auto& entry : std::filesystem::directory_iterator{base, ec}) {
        if (ec) {
            break;
        }
        const std::string name = entry.path().filename().string();
        if (name.rfind("thermal_zone", 0) != 0) {
            continue;
        }

        Zone zone{};
        zone.temp_path = (entry.path() / "temp").string();
        zone.type = ReadFileTrimmed((entry.path() / "type").string());

        std::string lowered = zone.type;
        std::transform(lowered.begin(), lowered.end(), lowered.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });

        const bool ignored =
            ContainsAny(lowered, kIgnoredZoneHints, std::size(kIgnoredZoneHints));
        const bool relevant =
            !ignored && ContainsAny(lowered, kRelevantZoneHints, std::size(kRelevantZoneHints));
        zone.relevant = relevant;

        // Only keep zones we can actually read, so Sample() stays cheap.
        if (ReadNumber(zone.temp_path) >= 0) {
            zones.push_back(std::move(zone));
        }
    }

    has_sensors = std::any_of(zones.begin(), zones.end(),
                              [](const Zone& z) { return z.relevant; });
    LOG_INFO(Common, "[Symbiosis] thermal: {} zone(s), {} relevant", zones.size(),
             std::count_if(zones.begin(), zones.end(), [](const Zone& z) { return z.relevant; }));
}

void ThermalMonitor::DiscoverGpuFrequencyNodes() {
    // Vendors put the GPU devfreq node in different places. Try the common
    // ones; failure is fine, we just lose the clock percentage.
    static constexpr const char* kCandidateDirs[] = {
        "/sys/class/devfreq/gpufreq",
        "/sys/class/devfreq/mali",
        "/sys/class/kgsl/kgsl-3d0/devfreq",
        "/sys/devices/platform/gpusysfs",
    };

    std::error_code ec;
    for (const char* dir : kCandidateDirs) {
        if (!std::filesystem::is_directory(dir, ec)) {
            continue;
        }
        const std::string cur = std::string{dir} + "/cur_freq";
        const std::string max = std::string{dir} + "/max_freq";
        if (ReadNumber(cur) > 0) {
            gpu_cur_freq_path = cur;
            if (ReadNumber(max) > 0) {
                gpu_max_freq_path = max;
            }
            return;
        }
    }

    // Generic devfreq scan as a last resort: pick any node whose name mentions
    // a GPU.
    const std::string devfreq = "/sys/class/devfreq";
    if (!std::filesystem::is_directory(devfreq, ec)) {
        return;
    }
    for (const auto& entry : std::filesystem::directory_iterator{devfreq, ec}) {
        if (ec) {
            break;
        }
        std::string name = entry.path().filename().string();
        std::transform(name.begin(), name.end(), name.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        if (name.find("gpu") == std::string::npos && name.find("mali") == std::string::npos) {
            continue;
        }
        const std::string cur = (entry.path() / "cur_freq").string();
        if (ReadNumber(cur) > 0) {
            gpu_cur_freq_path = cur;
            const std::string max = (entry.path() / "max_freq").string();
            if (ReadNumber(max) > 0) {
                gpu_max_freq_path = max;
            }
            return;
        }
    }
}

ThermalReading ThermalMonitor::Sample() {
    if (!discovered) {
        discovered = true;
        DiscoverZones();
        DiscoverGpuFrequencyNodes();
    }

    ThermalReading reading{};

    // --- Temperature -----------------------------------------------------
    s64 hottest = -1;
    for (const auto& zone : zones) {
        if (!zone.relevant) {
            continue;
        }
        const s64 value = ReadNumber(zone.temp_path);
        if (value <= 0) {
            continue;
        }
        // Some kernels report degrees, others milli-degrees. Normalise.
        const s64 millic = value < 1000 ? value * 1000 : value;
        if (millic > hottest) {
            hottest = millic;
            reading.hottest_zone = zone.type;
        }
    }
    reading.max_temp_millic = hottest > 0 ? static_cast<s32>(hottest) : -1;

    // --- GPU clock -------------------------------------------------------
    if (!gpu_cur_freq_path.empty()) {
        const s64 current = ReadNumber(gpu_cur_freq_path);
        s64 maximum = gpu_max_freq_path.empty() ? -1 : ReadNumber(gpu_max_freq_path);

        if (current > 0) {
            observed_max_freq = std::max(observed_max_freq, static_cast<u64>(current));
            if (maximum <= 0) {
                maximum = static_cast<s64>(observed_max_freq);
            }
            if (maximum > 0) {
                reading.gpu_clock_percent =
                    static_cast<s32>((current * 100) / maximum);
            }
        }
    }

    // --- Classify --------------------------------------------------------
    // Temperature alone is a poor signal: devices idle at 45C and throttle at
    // wildly different points. A measured clock reduction is the ground truth,
    // so it dominates when available.
    const s32 temp_c = reading.max_temp_millic > 0 ? reading.max_temp_millic / 1000 : -1;
    const s32 clock = reading.gpu_clock_percent;

    if (temp_c < 0 && clock < 0) {
        reading.state = ThermalState::Unknown;
        reading.summary = "This device does not expose thermal sensors to apps.";
    } else if (clock >= 0 && clock < 45 && temp_c >= 70) {
        reading.state = ThermalState::Critical;
        reading.summary = "GPU clock reduced to " + std::to_string(clock) +
                          "% while hot -- the device is heavily throttling.";
        reading.advice =
            "No profile can recover this. Let the device cool, remove the case, "
            "or switch to the Battery profile to stop the cycle.";
    } else if ((clock >= 0 && clock < 75) || temp_c >= 80) {
        reading.state = ThermalState::Throttling;
        reading.summary =
            temp_c >= 0 ? "Throttling at " + std::to_string(temp_c) + "C" : "Throttling";
        if (clock >= 0) {
            reading.summary += ", GPU at " + std::to_string(clock) + "% clock";
        }
        reading.advice =
            "Frame drops now are thermal, not the emulator. The Battery profile "
            "keeps clocks lower but steadier.";
    } else if (temp_c >= 65) {
        reading.state = ThermalState::Warm;
        reading.summary = "Warm (" + std::to_string(temp_c) + "C) but not throttling yet.";
        reading.advice = "Expect throttling if you keep playing at this load.";
    } else {
        reading.state = ThermalState::Cool;
        reading.summary = temp_c >= 0 ? "Cool (" + std::to_string(temp_c) + "C)" : "Cool";
    }

    last = reading;
    return reading;
}

ThermalPolicyVerdict EvaluateThermalPolicy(const ThermalReading& reading, u32 ceiling_c,
                                           u32 warning_c) {
    ThermalPolicyVerdict verdict{};

    if (reading.max_temp_millic <= 0) {
        // No sensors: fall back to the clock signal alone. If the GPU is being
        // held far below its peak, something is limiting it.
        if (reading.gpu_clock_percent >= 0 && reading.gpu_clock_percent < 50) {
            verdict.should_warn = true;
            verdict.suggested_rest_minutes = 5;
            verdict.title = "Device is limiting the GPU";
            verdict.body =
                "The GPU is running at " + std::to_string(reading.gpu_clock_percent) +
                "% of its peak clock. This device does not report temperature, but a "
                "reduction this large is almost always heat. A short break will help.";
        }
        return verdict;
    }

    const u32 temp_c = static_cast<u32>(reading.max_temp_millic / 1000);

    if (warning_c > 0 && temp_c >= warning_c) {
        verdict.should_warn = true;
        verdict.over_ceiling = true;
        verdict.suggested_rest_minutes = 10;
        verdict.title = "Time to take a break";
        verdict.body = "The device has reached " + std::to_string(temp_c) +
                       "C. Sustained play at this temperature will throttle hard and is "
                       "hard on the battery. Stop for about 10 minutes and let it cool.";
        return verdict;
    }

    if (ceiling_c > 0 && temp_c >= ceiling_c) {
        verdict.over_ceiling = true;
        verdict.should_warn = true;
        verdict.suggested_rest_minutes = 5;
        verdict.title = "Running hot";
        verdict.body = "At " + std::to_string(temp_c) + "C the device is at the ceiling you set (" +
                       std::to_string(ceiling_c) +
                       "C). Performance mode will stop pushing further. Consider a short break.";
        return verdict;
    }

    // Approaching the ceiling: inform without nagging.
    if (ceiling_c > 5 && temp_c >= ceiling_c - 5) {
        verdict.title = "Warming up";
        verdict.body = "Currently " + std::to_string(temp_c) + "C, ceiling is " +
                       std::to_string(ceiling_c) + "C.";
    }
    return verdict;
}

ThermalMonitor& GetThermalMonitor() {
    static ThermalMonitor instance;
    return instance;
}

} // namespace Symbiosis
