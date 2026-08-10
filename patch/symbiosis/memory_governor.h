// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file memory_governor.h
 * @brief Keeps Eden alive inside a hard, dishonest memory budget.
 *
 * The target device has 8 GB of RAM of which roughly 5 GB is ever available to
 * an app: the rest is held by the Android system, the vendor HAL stack and,
 * on Mali, by a GPU driver that allocates its own shadow copies of buffers.
 * `/proc/meminfo` will happily report several gigabytes "free" that the
 * lowmemorykiller will never actually let a foreground app take.
 *
 * The governor therefore does not trust the OS numbers. It computes an honest
 * budget, hands out reservations to subsystems that ask, and applies graduated
 * pressure long before the kernel would start killing things.
 *
 * The "mutual aid" aspect: subsystems register a release callback, so under
 * pressure the caches that can cheaply rebuild themselves (shader disk cache,
 * texture staging) give memory back to the ones that cannot (guest RAM, which
 * is fixed at 4 GB + 1 GB extended by the Switch memory layout).
 */

#pragma once

#include <atomic>
#include <functional>
#include <mutex>
#include <string>
#include <vector>

#include "common/common_types.h"

namespace Symbiosis {

/// How close to the wall we are.
enum class MemoryPressure : u32 {
    Relaxed = 0, ///< < 60% of budget used.
    Elevated,    ///< 60-80%: stop growing caches.
    Critical,    ///< 80-92%: actively trim.
    Emergency,   ///< > 92%: drop everything droppable.
};

const char* ToString(MemoryPressure pressure);

/// Opaque handle returned by RegisterDonor, used to unregister later.
using DonorHandle = u64;
inline constexpr DonorHandle kInvalidDonor = 0;

/// A subsystem that can give memory back when asked.
struct Donor {
    std::string name;
    /// Called with a target number of bytes to release. Returns bytes actually
    /// released. Must be safe to call from any thread and must never block on
    /// the GPU.
    std::function<u64(u64)> release;
    /// Lower priority donors are asked first.
    int priority{0};
};

/**
 * @brief Process-wide memory budget manager.
 */
class MemoryGovernor {
public:
    MemoryGovernor();

    MemoryGovernor(const MemoryGovernor&) = delete;
    MemoryGovernor& operator=(const MemoryGovernor&) = delete;

    /**
     * @brief Establishes the budget by measuring the device.
     *
     * @param declared_total_bytes Total RAM as reported by the OS, or 0 to
     *        detect it. The governor deliberately applies a headroom factor to
     *        whatever it is told.
     */
    void Initialise(u64 declared_total_bytes = 0);

    /// Bytes the process may use in total before Emergency.
    [[nodiscard]] u64 Budget() const {
        return budget_bytes.load(std::memory_order_relaxed);
    }

    /// Best estimate of current process usage (RSS + known GPU allocations).
    [[nodiscard]] u64 Used() const;

    [[nodiscard]] MemoryPressure Pressure() const;

    /**
     * @brief Registers a subsystem willing to release memory under pressure.
     *
     * @return A handle that MUST be passed to UnregisterDonor() before the
     *         registering object is destroyed. The governor is a process-wide
     *         singleton and outlives every cache, so a donor that captures
     *         `this` and is never removed becomes a dangling call the moment
     *         the cache goes away -- which is exactly what happens when a game
     *         is closed.
     */
    [[nodiscard]] DonorHandle RegisterDonor(Donor donor);

    /// Removes a previously registered donor. Safe to call with
    /// kInvalidDonor or an already-removed handle.
    void UnregisterDonor(DonorHandle handle);

    /// Drops every donor. Used when a session ends, so nothing survives into
    /// the next one.
    void ClearDonors();

    /**
     * @brief Asks for permission to allocate @p bytes.
     *
     * If the allocation would push us past the budget, donors are asked to
     * release memory first. Returns false only if, after reclaiming, the
     * allocation still cannot fit — the caller must then degrade gracefully
     * rather than call malloc anyway.
     */
    [[nodiscard]] bool RequestAllocation(u64 bytes, std::string_view purpose);

    /// Tells donors to release approximately @p target_bytes. Returns the
    /// number of bytes actually reclaimed.
    u64 Reclaim(u64 target_bytes);

    /// Recomputes pressure and trims automatically if needed. Cheap; intended
    /// to be called once per frame.
    void Tick();

    /// Records a GPU-side allocation that does not show up in RSS.
    void NoteGpuAllocation(s64 delta_bytes);

    [[nodiscard]] std::string DescribeState() const;

    /// Recommended guest-visible heap trim, in bytes, given the budget. Used to
    /// decide whether the 6 GB "extended memory layout" is safe to enable.
    [[nodiscard]] bool ExtendedMemoryLayoutSafe() const;

private:
    u64 QueryProcessRss() const;
    u64 QueryAvailableSystemMemory() const;

    mutable std::mutex mutex;
    /// Donors paired with their handles.
    std::vector<std::pair<DonorHandle, Donor>> donors;
    DonorHandle next_handle{1};
    /// Guards against a donor triggering another reclaim while releasing.
    bool reclaim_in_progress{false};
    std::atomic<u64> budget_bytes{0};
    std::atomic<u64> gpu_bytes{0};
    std::atomic<u64> total_ram_bytes{0};
    std::atomic<u32> reclaim_events{0};
    bool initialised{false};
};

MemoryGovernor& GetMemoryGovernor();

} // namespace Symbiosis
