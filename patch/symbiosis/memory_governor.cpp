// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>

#include "common/logging.h"
#include "common/symbiosis/memory_governor.h"
#include "common/symbiosis/symbiosis_log.h"

#ifdef __unix__
#include <unistd.h>
#endif

namespace Symbiosis {

namespace {

constexpr u64 kMiB = 1024ULL * 1024ULL;
constexpr u64 kGiB = 1024ULL * kMiB;

/// Fraction of *total* RAM we are willing to consider ours in the worst case.
/// Measured on 8 GB Mali devices: the system, HAL and GPU driver reserve
/// 2.5-3 GB, so 62% is the realistic ceiling before the LMK intervenes.
constexpr double kTotalRamFactor = 0.62;

/// Safety margin subtracted from MemAvailable so that we never drive the
/// device to the point where the launcher gets killed behind us.
constexpr u64 kAvailableHeadroom = 512 * kMiB;

/// The guest Switch memory layout needs this much to enable 6 GB mode.
constexpr u64 kExtendedLayoutRequirement = u64{5} * kGiB + 512 * kMiB;

u64 ReadMemInfoField(const char* field) {
    std::ifstream meminfo{"/proc/meminfo"};
    if (!meminfo.is_open()) {
        return 0;
    }
    std::string line;
    const std::size_t field_len = std::strlen(field);
    while (std::getline(meminfo, line)) {
        if (line.compare(0, field_len, field) != 0) {
            continue;
        }
        const auto colon = line.find(':');
        if (colon == std::string::npos) {
            continue;
        }
        try {
            // Values in /proc/meminfo are in kB.
            return static_cast<u64>(std::stoull(line.substr(colon + 1))) * 1024ULL;
        } catch (...) {
            return 0;
        }
    }
    return 0;
}

} // Anonymous namespace

const char* ToString(MemoryPressure pressure) {
    switch (pressure) {
    case MemoryPressure::Relaxed:
        return "Relaxed";
    case MemoryPressure::Elevated:
        return "Elevated";
    case MemoryPressure::Critical:
        return "Critical";
    case MemoryPressure::Emergency:
        return "Emergency";
    }
    return "Unknown";
}

MemoryGovernor::MemoryGovernor() = default;

u64 MemoryGovernor::QueryProcessRss() const {
#ifdef __unix__
    std::ifstream statm{"/proc/self/statm"};
    if (statm.is_open()) {
        u64 size_pages = 0;
        u64 resident_pages = 0;
        if (statm >> size_pages >> resident_pages) {
            const auto page_size = static_cast<u64>(sysconf(_SC_PAGESIZE));
            return resident_pages * page_size;
        }
    }
#endif
    return 0;
}

u64 MemoryGovernor::QueryAvailableSystemMemory() const {
    const u64 available = ReadMemInfoField("MemAvailable");
    if (available == 0) {
        return 0;
    }
    return available > kAvailableHeadroom ? available - kAvailableHeadroom : 0;
}

void MemoryGovernor::Initialise(u64 declared_total_bytes) {
    std::scoped_lock lock{mutex};
    if (initialised) {
        return;
    }
    initialised = true;

    u64 total = declared_total_bytes;
    if (total == 0) {
        total = ReadMemInfoField("MemTotal");
    }
    if (total == 0) {
        // Nothing to measure (non-Linux host or restricted /proc). Assume the
        // documented target device rather than pretending we have plenty.
        total = 8 * kGiB;
    }
    total_ram_bytes.store(total, std::memory_order_relaxed);

    // Two independent estimates; take the smaller so an optimistic MemAvailable
    // right after boot cannot talk us into an unsafe budget.
    const u64 from_total = static_cast<u64>(static_cast<double>(total) * kTotalRamFactor);
    const u64 from_available = QueryAvailableSystemMemory();

    u64 budget = from_available != 0 ? std::min(from_total, from_available) : from_total;

    // Never go below a floor that makes emulation pointless, and never claim
    // more than the app could realistically hold.
    budget = std::max(budget, u64{1} * kGiB + 512 * kMiB);
    budget_bytes.store(budget, std::memory_order_relaxed);

    LOG_INFO(Common,
             "[Symbiosis] memory governor: total={} MiB, available={} MiB, budget={} MiB",
             total / kMiB, from_available / kMiB, budget / kMiB);
    LogInfo(LogArea::Memory, "budget " + std::to_string(budget / kMiB) + " MiB of " +
                                 std::to_string(total / kMiB) + " MiB total RAM");
}

u64 MemoryGovernor::Used() const {
    const u64 rss = QueryProcessRss();
    const u64 gpu = gpu_bytes.load(std::memory_order_relaxed);
    return rss + gpu;
}

MemoryPressure MemoryGovernor::Pressure() const {
    const u64 budget = budget_bytes.load(std::memory_order_relaxed);
    if (budget == 0) {
        return MemoryPressure::Relaxed;
    }
    const double ratio = static_cast<double>(Used()) / static_cast<double>(budget);
    if (ratio >= 0.92) {
        return MemoryPressure::Emergency;
    }
    if (ratio >= 0.80) {
        return MemoryPressure::Critical;
    }
    if (ratio >= 0.60) {
        return MemoryPressure::Elevated;
    }
    return MemoryPressure::Relaxed;
}

DonorHandle MemoryGovernor::RegisterDonor(Donor donor) {
    std::scoped_lock lock{mutex};
    const DonorHandle handle = next_handle++;
    LOG_INFO(Common, "[Symbiosis] registered memory donor '{}' (priority {}, handle {})",
             donor.name, donor.priority, handle);
    LogInfo(LogArea::Memory, "donor registered: " + donor.name);
    donors.emplace_back(handle, std::move(donor));
    std::stable_sort(donors.begin(), donors.end(),
                     [](const auto& a, const auto& b) { return a.second.priority < b.second.priority; });
    return handle;
}

void MemoryGovernor::UnregisterDonor(DonorHandle handle) {
    if (handle == kInvalidDonor) {
        return;
    }
    std::scoped_lock lock{mutex};
    const auto it = std::find_if(donors.begin(), donors.end(),
                                 [handle](const auto& entry) { return entry.first == handle; });
    if (it == donors.end()) {
        return;
    }
    LOG_INFO(Common, "[Symbiosis] unregistered memory donor '{}'", it->second.name);
    LogInfo(LogArea::Memory, "donor unregistered: " + it->second.name);
    donors.erase(it);
}

void MemoryGovernor::ClearDonors() {
    std::scoped_lock lock{mutex};
    if (!donors.empty()) {
        LOG_INFO(Common, "[Symbiosis] clearing {} memory donor(s)", donors.size());
    }
    donors.clear();
}

u64 MemoryGovernor::Reclaim(u64 target_bytes) {
    std::vector<Donor> snapshot;
    {
        std::scoped_lock lock{mutex};
        // A donor releasing memory can easily trigger another allocation, which
        // would call straight back into Reclaim and recurse until the stack
        // runs out. One reclaim at a time.
        if (reclaim_in_progress) {
            return 0;
        }
        reclaim_in_progress = true;
        snapshot.reserve(donors.size());
        for (const auto& [handle, donor] : donors) {
            snapshot.push_back(donor);
        }
    }

    // Always clear the flag, including on an early return or an exception.
    struct ScopeGuard {
        MemoryGovernor* self;
        ~ScopeGuard() {
            std::scoped_lock lock{self->mutex};
            self->reclaim_in_progress = false;
        }
    } guard{this};

    u64 reclaimed = 0;
    for (const auto& donor : snapshot) {
        if (reclaimed >= target_bytes) {
            break;
        }
        if (!donor.release) {
            continue;
        }
        const u64 want = target_bytes - reclaimed;
        u64 got = 0;
        try {
            got = donor.release(want);
        } catch (...) {
            LOG_ERROR(Common, "[Symbiosis] donor '{}' threw during release", donor.name);
            continue;
        }
        if (got > 0) {
            LOG_DEBUG(Common, "[Symbiosis] donor '{}' released {} MiB", donor.name, got / kMiB);
        }
        reclaimed += got;
    }

    reclaim_events.fetch_add(1, std::memory_order_relaxed);
    if (reclaimed > 0) {
        LOG_INFO(Common, "[Symbiosis] reclaimed {} MiB of {} MiB requested", reclaimed / kMiB,
                 target_bytes / kMiB);
        LogInfo(LogArea::Memory, "reclaimed " + std::to_string(reclaimed / kMiB) + " MiB of " +
                                     std::to_string(target_bytes / kMiB) + " MiB requested");
    }
    return reclaimed;
}

bool MemoryGovernor::RequestAllocation(u64 bytes, std::string_view purpose) {
    const u64 budget = budget_bytes.load(std::memory_order_relaxed);
    if (budget == 0) {
        return true; // Not initialised; do not obstruct.
    }

    const u64 used = Used();
    if (used + bytes <= budget) {
        return true;
    }

    const u64 deficit = (used + bytes) - budget;
    LOG_WARNING(Common, "[Symbiosis] '{}' wants {} MiB, over budget by {} MiB; reclaiming",
                purpose, bytes / kMiB, deficit / kMiB);

    // Ask for the deficit plus a little slack so we are not called again on the
    // very next allocation.
    const u64 reclaimed = Reclaim(deficit + 64 * kMiB);
    if (reclaimed >= deficit) {
        return true;
    }

    LOG_ERROR(Common, "[Symbiosis] denying {} MiB for '{}' ({} MiB short)", bytes / kMiB, purpose,
              (deficit - reclaimed) / kMiB);
    return false;
}

void MemoryGovernor::Tick() {
    const auto pressure = Pressure();
    if (pressure == MemoryPressure::Relaxed || pressure == MemoryPressure::Elevated) {
        return;
    }

    const u64 budget = budget_bytes.load(std::memory_order_relaxed);
    // Aim to get back under 70% of budget.
    const u64 target_usage = static_cast<u64>(static_cast<double>(budget) * 0.70);
    const u64 used = Used();
    if (used <= target_usage) {
        return;
    }
    Reclaim(used - target_usage);
}

void MemoryGovernor::NoteGpuAllocation(s64 delta_bytes) {
    if (delta_bytes >= 0) {
        gpu_bytes.fetch_add(static_cast<u64>(delta_bytes), std::memory_order_relaxed);
        return;
    }
    const auto magnitude = static_cast<u64>(-delta_bytes);
    u64 current = gpu_bytes.load(std::memory_order_relaxed);
    while (true) {
        const u64 next = current > magnitude ? current - magnitude : 0;
        if (gpu_bytes.compare_exchange_weak(current, next, std::memory_order_relaxed)) {
            return;
        }
    }
}

bool MemoryGovernor::ExtendedMemoryLayoutSafe() const {
    return budget_bytes.load(std::memory_order_relaxed) >= kExtendedLayoutRequirement;
}

std::string MemoryGovernor::DescribeState() const {
    const u64 budget = budget_bytes.load(std::memory_order_relaxed);
    const u64 used = Used();
    const u64 gpu = gpu_bytes.load(std::memory_order_relaxed);

    std::string out = "Symbiosis memory state:\n";
    out += "  total RAM:  " + std::to_string(total_ram_bytes.load() / kMiB) + " MiB\n";
    out += "  budget:     " + std::to_string(budget / kMiB) + " MiB\n";
    out += "  used:       " + std::to_string(used / kMiB) + " MiB (GPU-side " +
           std::to_string(gpu / kMiB) + " MiB)\n";
    out += "  pressure:   ";
    out += ToString(Pressure());
    out += '\n';
    out += "  reclaims:   " + std::to_string(reclaim_events.load()) + '\n';
    out += "  6GB layout: ";
    out += ExtendedMemoryLayoutSafe() ? "permitted" : "blocked (insufficient budget)";
    out += '\n';
    return out;
}

std::string MemoryGovernor::DescribeJson() const {
    const u64 budget = budget_bytes.load(std::memory_order_relaxed);
    const u64 used = Used();
    const u64 avail = QueryAvailableSystemMemory();
    const u64 leftover = avail;
    const bool warn = leftover < 700ull * kMiB ||
                      Pressure() == MemoryPressure::Critical ||
                      Pressure() == MemoryPressure::Emergency;
    std::string out = "{";
    out += "\"totalMb\":" + std::to_string(total_ram_bytes.load() / kMiB);
    out += ",\"budgetMb\":" + std::to_string(budget / kMiB);
    out += ",\"usedMb\":" + std::to_string(used / kMiB);
    out += ",\"gpuMb\":" + std::to_string(gpu_bytes.load() / kMiB);
    out += ",\"leftMb\":" + std::to_string(leftover / kMiB);
    out += ",\"pressure\":\"";
    out += ToString(Pressure());
    out += "\",\"warn\":";
    out += warn ? "true" : "false";
    out += ",\"note\":\"";
    if (warn) {
        out += "осталось мало RAM — лучше не запускать тяжёлую игру";
    } else {
        out += "памяти пока хватает";
    }
    out += "\"}";
    return out;
}

MemoryGovernor& GetMemoryGovernor() {
    static MemoryGovernor instance;
    return instance;
}

} // namespace Symbiosis
