// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file settings_audit.h
 * @brief Reports which settings actually took effect, and why the rest did not.
 *
 * A setting in Eden is a request, not a guarantee. Between the toggle and the
 * renderer sit several silent filters:
 *
 *   - Hardware gates. `astc_recompression` is only consulted when
 *     `!device.IsOptimalAstcSupported()` (maxwell_to_vk.cpp:248). Mali decodes
 *     ASTC natively, so on this device the setting is read and discarded.
 *   - Vendor allow-lists. `force_max_clock` requires `ShouldBoostClocks()`,
 *     which enumerates AMD, NVIDIA, Intel, Qualcomm and Samsung
 *     (vulkan_device.cpp:947). ARM is absent, so the turbo thread is never
 *     created on Mali.
 *   - Driver capability. `vsync_mode` is renegotiated against the present
 *     modes the surface reports (vk_swapchain.cpp:44); asking for Mailbox on a
 *     surface that lacks it silently yields FIFO.
 *   - Backend scope. Some settings are only read by the OpenGL renderer, which
 *     cannot run on Android at all.
 *
 * None of this is visible from the settings screen. The user sees a toggle
 * that stays on and a frame rate that does not move, and reasonably concludes
 * the emulator is broken.
 *
 * This module answers one question honestly: for each setting the user
 * changed, did it reach the GPU, was it overridden, or was it ignored - and by
 * which line of code. Facts are contributed by the subsystems that know them
 * (Report* below), not guessed here.
 */

#pragma once

#include <string>
#include <vector>

#include "common/common_types.h"

namespace Symbiosis {

/// What became of a requested setting.
enum class AuditVerdict : u32 {
    Applied = 0,  ///< Reached the renderer as requested.
    Substituted,  ///< Applied, but with a different value than asked for.
    Ignored,      ///< Read and discarded; the hardware gate was not met.
    Unsupported,  ///< Cannot work on this device at all.
    Unknown,      ///< Not yet observed this run.
};

const char* ToString(AuditVerdict verdict);

/// Whether Symbiosis can do something about a bad verdict.
enum class AuditRemedy : u32 {
    None = 0,     ///< Nothing to be done; informational only.
    Suggest,      ///< A better value exists, but changing it needs consent.
    AutoFix,      ///< Safe to correct automatically.
};

/// One audited setting.
struct AuditEntry {
    /// Settings key, e.g. "astc_recompression".
    std::string key;
    /// What the user asked for, rendered as text.
    std::string requested;
    /// What is actually in force. Equal to `requested` when Applied.
    std::string effective;
    AuditVerdict verdict{AuditVerdict::Unknown};
    AuditRemedy remedy{AuditRemedy::None};
    /// Plain-language reason, naming the mechanism responsible.
    std::string reason;
    /// Source location that decided this, e.g. "vulkan_device.cpp:947".
    /// Empty when the finding is not tied to one line.
    std::string evidence;
    /// Value the remedy would install. Empty when there is nothing to install.
    std::string suggested_value;
};

/**
 * @brief Collects ground truth from the renderer and produces the audit.
 *
 * The audit is only meaningful while a game is running: most gates depend on a
 * live VkDevice. Before that, entries stay Unknown rather than being guessed.
 */
class SettingsAudit {
public:
    /// Facts the Vulkan device knows and the settings layer does not.
    struct DeviceFacts {
        bool valid{false};            ///< False until a device has reported.
        bool astc_native{false};      ///< IsOptimalAstcSupported().
        bool bcn_native{false};       ///< IsOptimalBcnSupported().
        bool should_boost_clocks{false}; ///< ShouldBoostClocks().
        bool broken_parallel_compile{false};
        bool float16{false};
        bool int8{false};
        u32 pipeline_workers{0};      ///< Threads actually building pipelines.
        u32 driver_id{0};             ///< VkDriverId.
        std::string device_name;
    };

    /// Present modes the surface offered, and what was chosen.
    struct PresentFacts {
        bool valid{false};
        bool has_immediate{false};
        bool has_mailbox{false};
        bool has_fifo_relaxed{false};
        u32 chosen_mode{0};           ///< VkPresentModeKHR actually used.
    };

    /// Called from vulkan_device.cpp once the device is up.
    static void ReportDevice(const DeviceFacts& facts);

    /// Called from vk_swapchain.cpp once the present mode is settled.
    static void ReportPresent(const PresentFacts& facts);

    /// Called from vk_pipeline_cache.cpp with the worker count it settled on.
    /// Separate from ReportDevice because the pipeline cache decides this
    /// after the device exists.
    static void ReportPipelineWorkers(u32 workers);

    /// Called when a game starts, to separate one run's findings from the next.
    static void BeginRun(std::string_view title);

    /**
     * @brief Records how the last run ended.
     *
     * A launch that dies within seconds leaves the user with a black screen and
     * no explanation: the loader status never reaches the UI unless it happens
     * to match one of two hard-coded dialogs. Storing it here means the reason
     * is still available afterwards, from the launch report, instead of being
     * lost with the process.
     *
     * @param status  Core::SystemResultStatus as an integer.
     * @param detail  Free-text explanation, may be empty.
     */
    static void ReportRunEnded(u32 status, std::string_view detail);

    /// Human-readable outcome of the previous run, empty when it ran normally.
    [[nodiscard]] static std::string LastRunOutcome();

    /// True once a run has begun and the device has reported.
    [[nodiscard]] static bool HasData();

    /// The full audit for the current run.
    [[nodiscard]] static std::vector<AuditEntry> Run();

    /// Multi-line report for the overlay, newest facts first.
    [[nodiscard]] static std::string Describe();

    /// Counts by verdict, for a one-line summary.
    struct Summary {
        u32 applied{0};
        u32 substituted{0};
        u32 ignored{0};
        u32 unsupported{0};
        u32 fixable{0};
    };
    [[nodiscard]] static Summary Summarise();

    /**
     * @brief Applies every AutoFix remedy.
     *
     * Only settings whose correction cannot change how the game looks are
     * touched: turning off a toggle that provably does nothing costs nothing,
     * whereas dropping resolution is a visual decision the user must make.
     *
     * @return Number of settings changed.
     */
    static u32 AutoFix();

    /// Human-readable description of what AutoFix would change.
    [[nodiscard]] static std::string PreviewAutoFix();

private:
    static void Evaluate(std::vector<AuditEntry>& out);
};

} // namespace Symbiosis
