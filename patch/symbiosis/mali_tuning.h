// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file mali_tuning.h
 * @brief Mali-specific device identification and tuning.
 *
 * Eden already carries a substantial block of workarounds for Qualcomm's
 * proprietary driver, and scattered checks for `VK_DRIVER_ID_ARM_PROPRIETARY`
 * exist in the buffer, pipeline and query caches. What is missing is any
 * notion of *which* Mali this is: a Bifrost part from 2017 and an Immortalis
 * from 2023 differ enormously in memory model, subgroup support and how badly
 * they punish a mid-frame readback, yet the emulator treats them identically.
 *
 * This module fills that gap. It classifies the GPU into an architecture
 * generation, records the traits that actually change rendering decisions, and
 * exposes a per-generation tuning profile. Everything is derived from the
 * device's own reported name and properties, so no hard-coded model list has
 * to be kept up to date.
 */

#pragma once

#include <string>
#include <vector>

#include "common/common_types.h"

namespace Symbiosis {

/// Mali architecture generations, oldest first.
enum class MaliGeneration : u32 {
    NotMali = 0,
    Utgard,      ///< Mali-400/450. No Vulkan at all; listed for completeness.
    Midgard,     ///< T-series (T720..T880). Vulkan 1.0 at best, very limited.
    BifrostGen1, ///< G31/G51/G71. First Bifrost, weak compute.
    BifrostGen2, ///< G52/G72/G76. Noticeably better throughput.
    ValhallGen1, ///< G57/G77. Warp size 16, solid Vulkan 1.1+.
    ValhallGen2, ///< G68/G78. The common "good Mali" of recent phones.
    ValhallGen3, ///< G310/G510/G610/G710.
    Valhall5,    ///< G615/G715 and similar.
    Immortalis,  ///< G715/G720 Immortalis with hardware ray tracing.
    UnknownMali, ///< Definitely Mali, generation not recognised.
};

const char* ToString(MaliGeneration generation);

/// Traits that change how the renderer should behave.
struct MaliTraits {
    MaliGeneration generation{MaliGeneration::NotMali};
    /// Full device name as reported by Vulkan, e.g. "Mali-G610 MC6".
    std::string device_name;
    /// Core count parsed from the "MCn" suffix, 0 when absent.
    u32 core_count{0};
    /// Subgroup (warp) size the driver reports.
    u32 subgroup_size{0};
    /// True when the GPU shares memory with the CPU. Always true on Mali, but
    /// stated explicitly because it drives several decisions.
    bool unified_memory{true};
    /// True when the part is old enough that async shader compilation is
    /// unreliable on its driver.
    bool fragile_parallel_compile{false};
    /// True when compute shaders are slow enough that CPU fallbacks win.
    bool weak_compute{false};
    /// True when the driver reports Vulkan 1.1 or newer.
    bool vulkan_11_plus{false};
};

/// Rendering advice derived from the traits.
struct MaliAdvice {
    /// Recommended render scale index into ResolutionSetup.
    u32 resolution_index{3}; ///< 3 = Res1X (native)
    /// Whether reactive flushing should be allowed. Almost never on a tiler.
    bool allow_reactive_flushing{false};
    /// Whether asynchronous shader compilation is safe on this driver.
    bool allow_async_shaders{true};
    /// Whether ASTC should be decoded on the GPU rather than recompressed.
    bool gpu_astc{false};
    /// Suggested texture cache ceiling as a fraction of the memory budget.
    float texture_budget_fraction{0.5f};
    /// Plain-language explanation shown in the UI.
    std::string rationale;
};

/**
 * @brief Identifies the Mali part and produces tuning advice.
 */
class MaliTuning {
public:
    /**
     * @brief Classifies a GPU from its reported name.
     *
     * @param device_name    VkPhysicalDeviceProperties::deviceName.
     * @param is_arm_driver  True when the driver id is ARM proprietary.
     * @param subgroup_size  Reported subgroup size, 0 if unknown.
     * @param api_version    Reported Vulkan API version.
     */
    [[nodiscard]] static MaliTraits Identify(std::string_view device_name, bool is_arm_driver,
                                             u32 subgroup_size, u32 api_version);

    /// Tuning advice for the identified part.
    [[nodiscard]] static MaliAdvice Advise(const MaliTraits& traits);

    /// Multi-line summary for logs and the diagnostics screen.
    [[nodiscard]] static std::string Describe(const MaliTraits& traits, const MaliAdvice& advice);

    /// Stores the identification so other subsystems can query it without
    /// needing a VkDevice.
    static void Publish(const MaliTraits& traits);

    /// Last published traits; NotMali when nothing was published.
    [[nodiscard]] static MaliTraits Current();
};

/// A driver package that may be worth trying on this device.
struct DriverSuggestion {
    std::string name;
    std::string description;
    /// Where the user can obtain it. Empty when it is already on the device.
    std::string url;
    /// True when this is the driver the device already ships with.
    bool is_system{false};
    /// Honest assessment of whether it will help here.
    std::string verdict;
};

/**
 * @brief Suggests Vulkan drivers appropriate for the identified GPU.
 *
 * Most Mali devices cannot load a replacement driver at all: unlike Adreno,
 * there is no widely deployed equivalent of the Turnip/adrenotools path, and
 * the vendor blob is the only option. Saying so plainly is more useful than
 * offering downloads that will not work.
 */
[[nodiscard]] std::vector<DriverSuggestion> SuggestDrivers(const MaliTraits& traits);

/// Firmware guidance for the identified device.
[[nodiscard]] std::string FirmwareAdvice(const MaliTraits& traits);

} // namespace Symbiosis
