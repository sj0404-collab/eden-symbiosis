// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <cstdlib>
#include <iterator>
#include <mutex>

#include "common/settings.h"
#include "common/symbiosis/mali_tuning.h"
#include "common/symbiosis/settings_audit.h"
#include "common/symbiosis/symbiosis_log.h"

namespace Symbiosis {

namespace {

std::mutex g_mutex;
SettingsAudit::DeviceFacts g_device;
SettingsAudit::PresentFacts g_present;
std::string g_title;
bool g_run_active = false;
u32 g_last_status = 0;
std::string g_last_detail;

/// Vulkan present mode values, spelled out so this file need not include
/// vulkan headers (it is compiled into common/, which must stay independent
/// of video_core).
constexpr u32 kPresentImmediate = 0;
constexpr u32 kPresentMailbox = 1;
constexpr u32 kPresentFifo = 2;
constexpr u32 kPresentFifoRelaxed = 3;

const char* PresentModeName(u32 mode) {
    switch (mode) {
    case kPresentImmediate:
        return "Immediate";
    case kPresentMailbox:
        return "Mailbox";
    case kPresentFifo:
        return "FIFO";
    case kPresentFifoRelaxed:
        return "FIFO relaxed";
    default:
        return "unknown";
    }
}

/// VkDriverId for the ARM proprietary driver.
constexpr u32 kDriverArmProprietary = 12;

/// Reads a setting's current value as text without knowing its type.
std::string ReadSetting(const std::string& key, bool* found = nullptr) {
    if (found != nullptr) {
        *found = false;
    }
    for (const auto& [category, settings] : Settings::values.linkage.by_category) {
        for (auto* setting : settings) {
            if (setting->GetLabel() != key) {
                continue;
            }
            if (found != nullptr) {
                *found = true;
            }
            return setting->ToString();
        }
    }
    return {};
}

bool WriteSetting(const std::string& key, const std::string& value) {
    for (const auto& [category, settings] : Settings::values.linkage.by_category) {
        for (auto* setting : settings) {
            if (setting->GetLabel() != key) {
                continue;
            }
            try {
                setting->LoadString(value);
                return true;
            } catch (const std::exception&) {
                return false;
            }
        }
    }
    return false;
}

/// Turns a ResolutionSetup index into the scale it actually means.
/// The bare index is unreadable, and off-by-one errors in it are invisible.
std::string ResolutionLabel(const std::string& value) {
    static const char* kLabels[] = {"0.25x", "0.5x", "0.75x", "1x (native)",
                                    "1.25x", "1.5x", "2x",    "3x",
                                    "4x",    "5x",   "6x",    "7x",
                                    "8x"};
    const int index = std::atoi(value.c_str());
    if (index < 0 || index >= static_cast<int>(std::size(kLabels))) {
        return value;
    }
    return kLabels[index];
}

bool IsTruthy(const std::string& value) {
    return value == "true" || value == "1";
}

AuditEntry MakeEntry(std::string key, std::string requested, std::string effective,
                     AuditVerdict verdict, AuditRemedy remedy, std::string reason,
                     std::string evidence, std::string suggested = {}) {
    AuditEntry entry;
    entry.key = std::move(key);
    entry.requested = std::move(requested);
    entry.effective = std::move(effective);
    entry.verdict = verdict;
    entry.remedy = remedy;
    entry.reason = std::move(reason);
    entry.evidence = std::move(evidence);
    entry.suggested_value = std::move(suggested);
    return entry;
}

} // namespace

const char* ToString(AuditVerdict verdict) {
    switch (verdict) {
    case AuditVerdict::Applied:
        return "applied";
    case AuditVerdict::Substituted:
        return "substituted";
    case AuditVerdict::Ignored:
        return "ignored";
    case AuditVerdict::Unsupported:
        return "unsupported";
    case AuditVerdict::Unknown:
    default:
        return "unknown";
    }
}

void SettingsAudit::ReportDevice(const DeviceFacts& facts) {
    std::scoped_lock lock{g_mutex};
    g_device = facts;
    g_device.valid = true;
    LogInfo(LogArea::Profile,
            "audit: device reported (astc_native=" + std::string(facts.astc_native ? "y" : "n") +
                ", boost_clocks=" + (facts.should_boost_clocks ? "y" : "n") +
                ", pipeline_workers=" + std::to_string(facts.pipeline_workers) + ")");
}

void SettingsAudit::ReportPresent(const PresentFacts& facts) {
    std::scoped_lock lock{g_mutex};
    g_present = facts;
    g_present.valid = true;
    LogInfo(LogArea::Render,
            "audit: present mode = " + std::string(PresentModeName(facts.chosen_mode)));
}

void SettingsAudit::ReportPipelineWorkers(u32 workers) {
    std::scoped_lock lock{g_mutex};
    g_device.pipeline_workers = workers;
}

void SettingsAudit::BeginRun(std::string_view title) {
    std::scoped_lock lock{g_mutex};
    g_title = std::string{title};
    g_run_active = true;
    // Device facts deliberately survive: the device outlives individual games
    // and re-probing it would produce nothing new.
}

namespace {

/// Maps Core::SystemResultStatus to something a person can act on.
std::string DescribeStatus(u32 status) {
    switch (status) {
    case 0:
        return {};
    case 1:
        return "The emulator was not initialised.";
    case 2:
        return "No loader could handle this file. The dump may be the wrong "
               "format or incomplete.";
    case 3:
        return "System files are missing. Firmware is required for this title.";
    case 4:
        return "The shared font is missing; it comes from firmware.";
    case 5:
        return "The graphics backend failed. This is the Vulkan driver "
               "refusing or crashing, not the game.";
    case 7:
        return "The file could not be loaded. Usually missing keys, the wrong "
               "keys, or an encrypted dump.";
    default:
        return "The run ended with an unrecognised error.";
    }
}

} // namespace

void SettingsAudit::ReportRunEnded(u32 status, std::string_view detail) {
    std::scoped_lock lock{g_mutex};
    g_last_status = status;
    g_last_detail = std::string{detail};
    if (status != 0) {
        LogWarning(LogArea::General,
                   "audit: run ended with status " + std::to_string(status) + " " +
                       DescribeStatus(status));
    }
}

std::string SettingsAudit::LastRunOutcome() {
    std::scoped_lock lock{g_mutex};
    if (g_last_status == 0) {
        return {};
    }
    std::string out = DescribeStatus(g_last_status);
    if (!g_last_detail.empty()) {
        out += "\n" + g_last_detail;
    }
    return out;
}

bool SettingsAudit::HasData() {
    std::scoped_lock lock{g_mutex};
    return g_run_active && g_device.valid;
}

void SettingsAudit::Evaluate(std::vector<AuditEntry>& out) {
    // --- Resolution ------------------------------------------------------
    // Read straight back from the setting: no gate stands between this and the
    // renderer, so it is the control that proves the pipeline works at all.
    {
        bool found = false;
        const auto value = ReadSetting("resolution_setup", &found);
        if (found) {
            // Report the scale, not the enum index. "1" told the user nothing;
            // worse, it looked like 1x when it actually means 0.5x.
            const auto label = ResolutionLabel(value);
            out.push_back(MakeEntry("resolution_setup", label, label, AuditVerdict::Applied,
                                    AuditRemedy::None,
                                    "Resolution scaling is applied directly by the renderer. "
                                    "This is the setting with the largest effect on frame rate.",
                                    ""));
        }
    }

    // --- ASTC recompression ----------------------------------------------
    // maxwell_to_vk.cpp:248 wraps the whole switch in
    // `if (!device.IsOptimalAstcSupported() && IsPixelFormatASTC(...))`.
    // Mali decodes ASTC in hardware, so the setting is read and thrown away.
    {
        bool found = false;
        const auto value = ReadSetting("astc_recompression", &found);
        if (found && g_device.valid) {
            // One entry per setting. An earlier version emitted both "applied"
            // and "unsupported" for this key, which is worse than saying
            // nothing: the user cannot tell which one to believe.
            if (value == "0") {
                out.push_back(MakeEntry(
                    "astc_recompression", "off", "off", AuditVerdict::Applied,
                    AuditRemedy::None,
                    g_device.astc_native
                        ? "Off, which is right here: the GPU samples ASTC directly."
                        : "Off: ASTC is decoded to full-size RGBA. Correct colours, "
                          "but the highest memory use of the three options.",
                    ""));
            } else if (g_device.astc_native) {
                // maxwell_to_vk.cpp:248 only consults this when ASTC is *not*
                // natively supported, so here it is read and thrown away.
                out.push_back(MakeEntry(
                    "astc_recompression", value == "1" ? "BC1" : "BC3", "off",
                    AuditVerdict::Ignored, AuditRemedy::AutoFix,
                    "This GPU decodes ASTC in hardware, so recompression is never "
                    "reached. Costs nothing, saves nothing.",
                    "maxwell_to_vk.cpp:248", "0"));
            } else if (!g_device.bcn_native) {
                // The recompression path is live and targets a BC format this
                // GPU cannot sample. Nothing upstream checks for that.
                out.push_back(MakeEntry(
                    "astc_recompression", value == "1" ? "BC1" : "BC3",
                    "unsupported format", AuditVerdict::Unsupported, AuditRemedy::AutoFix,
                    "Textures would be recompressed into a BC format this GPU cannot "
                    "sample. Symbiosis now forces this off at startup; without that "
                    "it is a likely cause of a crash shortly after launch.",
                    "maxwell_to_vk.cpp:248", "0"));
            } else {
                out.push_back(MakeEntry(
                    "astc_recompression", value == "1" ? "BC1" : "BC3",
                    value == "1" ? "BC1" : "BC3", AuditVerdict::Applied, AuditRemedy::None,
                    "ASTC textures are recompressed to a BC format before upload.", ""));
            }
        }
    }

    // --- Forced maximum clocks -------------------------------------------
    // renderer_vulkan.cpp:160 requires ShouldBoostClocks(), whose allow-list
    // (vulkan_device.cpp:947) names AMD, NVIDIA, Intel, Qualcomm and Samsung.
    // ARM is not on it, so the turbo thread is never started on Mali.
    {
        bool found = false;
        const auto value = ReadSetting("force_max_clock", &found);
        if (found && g_device.valid && IsTruthy(value)) {
            if (!g_device.should_boost_clocks) {
                const bool is_arm = g_device.driver_id == kDriverArmProprietary;
                out.push_back(MakeEntry(
                    "force_max_clock", "true", "false", AuditVerdict::Ignored,
                    AuditRemedy::AutoFix,
                    is_arm ? "The clock-boost path only runs on an allow-list of drivers "
                             "that does not include ARM. No turbo thread is created."
                           : "This driver is not on the clock-boost allow-list.",
                    "vulkan_device.cpp:947", "false"));
            } else {
                out.push_back(MakeEntry("force_max_clock", "true", "true", AuditVerdict::Applied,
                                        AuditRemedy::None,
                                        "A background thread holds the GPU at maximum clocks.",
                                        ""));
            }
        }
    }

    // --- Asynchronous shader compilation ---------------------------------
    // The setting survives, but the worker count collapses to one when the
    // driver is known to miscompile in parallel (vk_pipeline_cache.cpp:353).
    {
        bool found = false;
        const auto value = ReadSetting("use_asynchronous_shaders", &found);
        if (found && g_device.valid) {
            if (IsTruthy(value) && g_device.broken_parallel_compile) {
                out.push_back(MakeEntry(
                    "use_asynchronous_shaders", "true", "true (1 thread)",
                    AuditVerdict::Substituted, AuditRemedy::None,
                    "Shaders still build in the background, but on a single thread: "
                    "this GPU generation miscompiles when several threads build at "
                    "once. Expect longer first-run stutter.",
                    "vk_pipeline_cache.cpp:353"));
            } else if (IsTruthy(value)) {
                out.push_back(MakeEntry(
                    "use_asynchronous_shaders", "true",
                    "true (" + std::to_string(g_device.pipeline_workers) + " threads)",
                    AuditVerdict::Applied, AuditRemedy::None,
                    "Shaders build in the background while the game runs.", ""));
            }
        }
    }

    // --- Present mode -----------------------------------------------------
    // ChooseSwapPresentMode (vk_swapchain.cpp:44) renegotiates the request
    // against the modes the surface actually offers, and silently falls back.
    {
        bool found = false;
        const auto value = ReadSetting("use_vsync", &found);
        if (found && g_present.valid) {
            const char* chosen = PresentModeName(g_present.chosen_mode);
            u32 requested_mode = kPresentFifo;
            if (value == "0") {
                requested_mode = kPresentImmediate;
            } else if (value == "1") {
                requested_mode = kPresentMailbox;
            } else if (value == "3") {
                requested_mode = kPresentFifoRelaxed;
            }

            if (requested_mode == g_present.chosen_mode) {
                out.push_back(MakeEntry("use_vsync", PresentModeName(requested_mode), chosen,
                                        AuditVerdict::Applied, AuditRemedy::None,
                                        "The surface supports the requested mode.", ""));
            } else {
                out.push_back(MakeEntry(
                    "use_vsync", PresentModeName(requested_mode), chosen,
                    AuditVerdict::Substituted, AuditRemedy::None,
                    "The display surface does not offer the requested mode, so the "
                    "driver used the nearest one it has. This caps the frame rate.",
                    "vk_swapchain.cpp:44"));
            }
        }
    }

    // --- Speed limit ------------------------------------------------------
    // Not a silent gate, but the single most common reason a frame rate refuses
    // to move, and worth stating outright next to the others.
    {
        bool found = false;
        const auto value = ReadSetting("use_speed_limit", &found);
        if (found && IsTruthy(value)) {
            const auto limit = ReadSetting("speed_limit");
            // 100% is the intended speed, not a handicap. Flagging it as a
            // problem sent the user chasing a setting that was already correct.
            const bool is_normal = limit == "100";
            out.push_back(MakeEntry(
                "use_speed_limit", "true", limit + "%", AuditVerdict::Applied,
                is_normal ? AuditRemedy::None : AuditRemedy::Suggest,
                is_normal
                    ? std::string{"Capped at full speed, which is what the games expect. "
                                  "This does not hold back the frame rate."}
                    : "Emulation is capped at " + limit +
                          "% of full speed, so it is deliberately running slower.",
                "", is_normal ? "" : "100"));
        }
    }

    // --- Reactive flushing ------------------------------------------------
    // Applies everywhere, but on a tile-based GPU it forces the tiler to
    // resolve mid-frame, which is the single most expensive thing to ask of it.
    {
        bool found = false;
        const auto value = ReadSetting("use_reactive_flushing", &found);
        const auto traits = MaliTuning::Current();
        const bool is_mali = traits.generation != MaliGeneration::NotMali;
        if (found && IsTruthy(value) && is_mali) {
            out.push_back(MakeEntry(
                "use_reactive_flushing", "true", "true", AuditVerdict::Applied,
                AuditRemedy::Suggest,
                "Active, and expensive here: a tile-based GPU must flush its tile "
                "buffer mid-frame to honour it. Usually costs more than it fixes.",
                "", "false"));
        }
    }

    // --- Disk shader cache ------------------------------------------------
    {
        bool found = false;
        const auto value = ReadSetting("use_disk_shader_cache", &found);
        if (found && !IsTruthy(value)) {
            out.push_back(MakeEntry(
                "use_disk_shader_cache", "false", "false", AuditVerdict::Applied,
                AuditRemedy::AutoFix,
                "Off, so every shader is rebuilt from scratch on each launch. This "
                "is the usual cause of stutter that never settles down.",
                "", "true"));
        }
    }

    // --- NVDEC ------------------------------------------------------------
    {
        bool found = false;
        const auto value = ReadSetting("nvdec_emulation", &found);
        if (found && value == "0") {
            out.push_back(MakeEntry("nvdec_emulation", "off", "off", AuditVerdict::Applied,
                                    AuditRemedy::None,
                                    "Video decoding is disabled; cutscenes will be skipped.",
                                    ""));
        }
    }
}

std::vector<AuditEntry> SettingsAudit::Run() {
    std::scoped_lock lock{g_mutex};
    std::vector<AuditEntry> out;
    if (!g_device.valid) {
        out.push_back(MakeEntry("", "", "", AuditVerdict::Unknown, AuditRemedy::None,
                                "No game has run yet this session, so nothing can be "
                                "checked: most of these gates only exist once the GPU "
                                "device is created.",
                                ""));
        return out;
    }
    Evaluate(out);
    return out;
}

SettingsAudit::Summary SettingsAudit::Summarise() {
    const auto entries = Run();
    Summary summary;
    for (const auto& entry : entries) {
        switch (entry.verdict) {
        case AuditVerdict::Applied:
            summary.applied++;
            break;
        case AuditVerdict::Substituted:
            summary.substituted++;
            break;
        case AuditVerdict::Ignored:
            summary.ignored++;
            break;
        case AuditVerdict::Unsupported:
            summary.unsupported++;
            break;
        default:
            break;
        }
        if (entry.remedy == AuditRemedy::AutoFix) {
            summary.fixable++;
        }
    }
    return summary;
}

std::string SettingsAudit::Describe() {
    const auto entries = Run();
    std::string out;

    {
        std::scoped_lock lock{g_mutex};
        if (!g_title.empty()) {
            out += g_title + "\n";
        }
        if (g_device.valid && !g_device.device_name.empty()) {
            out += g_device.device_name + "\n";
        }
        out += "\n";

        // Lead with the failure. If the last run died, that is the only thing
        // worth reading first; the settings table is context for it.
        if (g_last_status != 0) {
            out += "!! " + DescribeStatus(g_last_status) + "\n";
            if (!g_last_detail.empty()) {
                out += "   " + g_last_detail + "\n";
            }
            out += "\n";
        }
    }

    for (const auto& entry : entries) {
        if (entry.key.empty()) {
            out += entry.reason + "\n";
            continue;
        }

        // Mark the verdict with a character rather than a colour so the report
        // stays readable when copied out as plain text.
        const char* mark = "?";
        switch (entry.verdict) {
        case AuditVerdict::Applied:
            mark = "+";
            break;
        case AuditVerdict::Substituted:
            mark = "~";
            break;
        case AuditVerdict::Ignored:
            mark = "-";
            break;
        case AuditVerdict::Unsupported:
            mark = "x";
            break;
        default:
            break;
        }

        out += mark;
        out += " " + entry.key + ": " + entry.requested;
        if (entry.effective != entry.requested) {
            out += " -> " + entry.effective;
        }
        out += "\n    " + entry.reason + "\n";
        if (!entry.evidence.empty()) {
            out += "    [" + entry.evidence + "]\n";
        }
        out += "\n";
    }

    return out;
}

std::string SettingsAudit::PreviewAutoFix() {
    const auto entries = Run();
    std::string out;
    for (const auto& entry : entries) {
        if (entry.remedy != AuditRemedy::AutoFix || entry.suggested_value.empty()) {
            continue;
        }
        out += entry.key + ": " + entry.effective + " -> " + entry.suggested_value + "\n";
    }
    return out;
}

u32 SettingsAudit::AutoFix() {
    const auto entries = Run();
    u32 changed = 0;
    for (const auto& entry : entries) {
        if (entry.remedy != AuditRemedy::AutoFix || entry.suggested_value.empty()) {
            continue;
        }
        if (WriteSetting(entry.key, entry.suggested_value)) {
            changed++;
            LogInfo(LogArea::Profile,
                    "audit autofix: " + entry.key + " = " + entry.suggested_value);
        }
    }
    return changed;
}

} // namespace Symbiosis
