// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file thermal_monitor.h
 * @brief Detects thermal throttling, the invisible cause of "it got slow".
 *
 * On a phone the emulator does not slow down because the game got harder: it
 * slows down because the SoC crossed a temperature limit and the kernel
 * dropped the GPU and CPU clocks. Users see the frame rate halve twenty
 * minutes in and blame the emulator.
 *
 * This module samples the thermal zones and the GPU frequency exposed by the
 * kernel and reports, in plain terms, whether the device is currently being
 * throttled and by how much. That turns an unexplained slowdown into a fact
 * the user can act on -- for example by switching to the Battery profile.
 *
 * Everything is read-only and best-effort: on devices that hide these nodes
 * the monitor simply reports Unknown rather than guessing.
 */

#pragma once

#include <string>
#include <vector>

#include "common/common_types.h"

namespace Symbiosis {

enum class ThermalState : u32 {
    Unknown = 0, ///< Kernel does not expose usable sensors.
    Cool,        ///< Comfortably below any limit.
    Warm,        ///< Rising, but no clock reduction observed yet.
    Throttling,  ///< Clocks measurably reduced.
    Critical,    ///< Severe throttling; performance profiles will not help.
};

const char* ToString(ThermalState state);

struct ThermalReading {
    ThermalState state{ThermalState::Unknown};
    /// Hottest zone in milli-degrees Celsius, or -1 when unavailable.
    s32 max_temp_millic{-1};
    /// Name of the hottest zone, for diagnostics.
    std::string hottest_zone;
    /// Current GPU clock as a percentage of the maximum the kernel advertises,
    /// or -1 when the frequency nodes are not readable.
    s32 gpu_clock_percent{-1};
    /// Human-readable explanation, always populated.
    std::string summary;
    /// Suggested action, empty when nothing is wrong.
    std::string advice;
};

/**
 * @brief Samples the device's thermal and frequency state.
 *
 * Cheap enough to call once per second; it reads a handful of small sysfs
 * files. Never throws.
 */
class ThermalMonitor {
public:
    ThermalMonitor();

    /// Takes a fresh reading.
    [[nodiscard]] ThermalReading Sample();

    /// Last reading without touching the filesystem again.
    [[nodiscard]] ThermalReading Last() const {
        return last;
    }

    /// True when the device has usable sensors at all.
    [[nodiscard]] bool Available() const {
        return has_sensors;
    }

private:
    void DiscoverZones();
    void DiscoverGpuFrequencyNodes();

    struct Zone {
        std::string temp_path;
        std::string type;
        /// Only zones that plausibly track the SoC are considered; skipping
        /// battery and charger sensors avoids false positives.
        bool relevant{false};
    };

    std::vector<Zone> zones;
    std::string gpu_cur_freq_path;
    std::string gpu_max_freq_path;
    /// Peak clock seen this session, used as the reference when the kernel
    /// does not publish a max frequency node.
    u64 observed_max_freq{0};
    ThermalReading last;
    bool has_sensors{false};
    bool discovered{false};
};

/// Advice produced by the performance-mode policy.
struct ThermalPolicyVerdict {
    /// True when the user should be told to pause and let the device cool.
    bool should_warn{false};
    /// True when we are past the ceiling and sustained load is unwise.
    bool over_ceiling{false};
    /// Minutes of rest suggested, 0 when none needed.
    u32 suggested_rest_minutes{0};
    std::string title;
    std::string body;
};

/**
 * @brief Applies the user's thermal policy to the latest reading.
 *
 * Performance mode deliberately allows the device to run hotter than the
 * default in exchange for frame rate, but only up to a ceiling that stays
 * inside typical SoC specifications, and it tells the user when to stop.
 *
 * @param reading Fresh sample.
 * @param ceiling_c Temperature above which sustained load is discouraged.
 * @param warning_c Temperature at which the user is actively warned.
 */
[[nodiscard]] ThermalPolicyVerdict EvaluateThermalPolicy(const ThermalReading& reading,
                                                         u32 ceiling_c, u32 warning_c);

ThermalMonitor& GetThermalMonitor();

} // namespace Symbiosis
