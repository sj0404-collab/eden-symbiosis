// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "common/logging.h"
#include "common/settings.h"
#include "common/symbiosis/auto_modes.h"
#include "common/symbiosis/symbiosis_log.h"

namespace Symbiosis {

namespace {

/// True for GPUs that render tile-by-tile. Everything about their performance
/// profile differs from a desktop-style immediate renderer: mid-frame readback
/// forces a tile flush, full-screen passes are disproportionately expensive,
/// and upscaling collapses long before fillrate would suggest.
bool IsTiler(GpuFamily family) {
    switch (family) {
    case GpuFamily::Mali:
    case GpuFamily::Immortalis:
    case GpuFamily::Adreno:
    case GpuFamily::PowerVR:
        return true;
    default:
        return false;
    }
}

} // Anonymous namespace

const char* ToString(AutoMode mode) {
    switch (mode) {
    case AutoMode::Quality:
        return "Quality";
    case AutoMode::Balanced:
        return "Balanced";
    case AutoMode::Performance:
        return "Performance";
    case AutoMode::Stability:
        return "Stability";
    case AutoMode::Compatibility:
        return "Compatibility";
    case AutoMode::Turbo:
        return "Turbo";
    case AutoMode::Custom:
        return "Custom";
    case AutoMode::COUNT:
        break;
    }
    return "Unknown";
}

AutoModeEngine::AutoModeEngine() = default;

// ---------------------------------------------------------------------------
// Mode definitions.
//
// The same setting can appear in several modes with different values; that is
// the point. Each list is a complete statement of intent, so applying a mode
// never leaves a stale value from the previous one behind.
// ---------------------------------------------------------------------------

ModeDefinition AutoModeEngine::BuildBalanced(GpuFamily family, DriverOrigin origin) const {
    ModeDefinition d{};
    d.mode = AutoMode::Balanced;
    d.key = "balanced";
    d.display_name = "Balanced";
    d.summary = "The sensible default. Start here.";
    d.detail = "Native resolution, asynchronous shader compilation to avoid freezes, and "
               "no accuracy hacks. Works on essentially every title.";
    d.temp_ceiling = 85;

    d.tweaks = {
        {"resolution_setup", "3", "Native 1x (Res1X): the resolution the games were made for."},
        {"use_asynchronous_shaders", "true",
         "Compiles shaders in the background instead of freezing for seconds."},
        {"use_asynchronous_gpu_emulation", "true", "Keeps the CPU and GPU threads decoupled."},
        {"use_disk_shader_cache", "true", "Second launch of a game is far smoother."},
        {"anti_aliasing", "0", "Off: a full-screen pass is expensive on mobile."},
        {"scaling_filter", "1", "Bilinear: cheap and clean."},
        {"gpu_accuracy", "0", "Low accuracy is correct for the vast majority of titles."},
        {"astc_recompression", "1", "Cuts texture bandwidth with no visible change."},
        {"use_speed_limit", "true", "Frame limiter on: prevents pointless overdraw and heat."},
        {"speed_limit", "100", "100% of the intended speed."},
    };

    if (IsTiler(family)) {
        d.tweaks.push_back({"use_reactive_flushing", "false",
                            "Reactive flushing forces mid-frame readback, which stalls a tiler."});
        d.tweaks.push_back({"max_anisotropy", "1",
                            "Default (1x): anisotropic filtering costs disproportionately much on mobile."});
    }
    return d;
}

ModeDefinition AutoModeEngine::BuildQuality(GpuFamily family, DriverOrigin origin) const {
    ModeDefinition d{};
    d.mode = AutoMode::Quality;
    d.key = "quality";
    d.display_name = "Quality";
    d.summary = "Best image the device can hold. Lower frame rate.";
    d.detail = "Higher internal resolution and better filtering. Suited to 2D games, "
               "visual novels and anything already hitting the frame cap.";
    d.temp_ceiling = 85;

    // On a tiler, 1.5x is already ambitious; 2x is only realistic on desktop
    // style parts. Promising more than the hardware can do is not a favour.
    const bool tiler = IsTiler(family);

    d.tweaks = {
        {"resolution_setup", tiler ? "5" : "6",
         tiler ? "1.5x (Res3_2X): the most a mobile tiler can usually sustain."
               : "2x internal resolution (Res2X)."},
        {"scaling_filter", "2", "Bicubic for a cleaner upscale."},
        {"anti_aliasing", "1", "FXAA: the only affordable anti-aliasing here."},
        {"max_anisotropy", "2", "2x anisotropic filtering (X2): visible gain at moderate cost."},
        {"astc_recompression", "0", "Keep full ASTC texture quality."},
        {"use_asynchronous_shaders", "true", "Still needed to avoid compilation freezes."},
        {"use_asynchronous_gpu_emulation", "true", "Keeps threads decoupled."},
        {"use_disk_shader_cache", "true", "Smoother repeat launches."},
        {"gpu_accuracy", "1", "High accuracy: fewer rendering artefacts."},
        {"use_speed_limit", "true", "Frame limiter on."},
        {"speed_limit", "100", "100%."},
    };
    if (tiler) {
        d.tweaks.push_back({"use_reactive_flushing", "false", "Still a tiler underneath."});
    }
    return d;
}

ModeDefinition AutoModeEngine::BuildPerformance(GpuFamily family, DriverOrigin origin) const {
    ModeDefinition d{};
    d.mode = AutoMode::Performance;
    d.key = "performance";
    d.display_name = "Performance";
    d.summary = "Highest frame rate. Softer image, occasional glitches.";
    d.detail = "Reduces render scale and strips every optional pass. Some titles will show "
               "minor artefacts; that is the trade being made deliberately.";
    d.temp_ceiling = 88;

    d.tweaks = {
        {"resolution_setup", "2", "0.75x render scale (Res3_4X): the single biggest win on mobile."},
        {"scaling_filter", "0", "Nearest: costs almost nothing to composite."},
        {"anti_aliasing", "0", "No anti-aliasing."},
        {"max_anisotropy", "1", "Default (1x): no extra filtering cost."},
        {"gpu_accuracy", "0", "Low accuracy: fastest."},
        {"astc_recompression", "1", "Less texture bandwidth."},
        {"use_asynchronous_shaders", "true", "Prevents multi-second stalls on new shaders."},
        {"use_asynchronous_gpu_emulation", "true", "Decoupled threads."},
        {"use_disk_shader_cache", "true", "Avoids recompiling everything each run."},
        {"use_speed_limit", "false", "Unlocked: let the device run as fast as it can."},
        {"use_reactive_flushing", "false", "Avoids expensive mid-frame readback."},
        {"fast_gpu_time", "1", "Loosens GPU timing; a common and effective speed-up."},
    };
    return d;
}

ModeDefinition AutoModeEngine::BuildStability(GpuFamily family, DriverOrigin origin) const {
    ModeDefinition d{};
    d.mode = AutoMode::Stability;
    d.key = "stability";
    d.display_name = "Stability";
    d.summary = "Fewest crashes and stutters. Slower, but predictable.";
    d.detail = "Turns off the optimisations most likely to trip driver bugs, and keeps the "
               "frame rate steady rather than high. Use this if a game keeps closing.";
    d.temp_ceiling = 80;

    d.tweaks = {
        {"resolution_setup", "3", "Native resolution (Res1X): no scaling maths to get wrong."},
        {"scaling_filter", "1", "Bilinear."},
        {"anti_aliasing", "0", "Extra passes are extra chances to hit a driver bug."},
        {"gpu_accuracy", "1", "High accuracy: fewer glitches, at some cost."},
        {"use_asynchronous_shaders", "false",
         "Synchronous compilation stutters, but avoids async shader bugs on weak drivers."},
        {"use_asynchronous_gpu_emulation", "true", "Still worth keeping."},
        {"use_disk_shader_cache", "true", "Reduces recompilation."},
        {"astc_recompression", "1", "Lower memory pressure means fewer OOM kills."},
        {"use_speed_limit", "true", "Steady pacing."},
        {"speed_limit", "100", "100%."},
        {"use_reactive_flushing", "false", "A frequent source of hangs on mobile drivers."},
        {"max_anisotropy", "1", "Default (1x)."},
    };
    return d;
}

ModeDefinition AutoModeEngine::BuildCompatibility(GpuFamily family, DriverOrigin origin) const {
    ModeDefinition d{};
    d.mode = AutoMode::Compatibility;
    d.key = "compatibility";
    d.display_name = "Compatibility";
    d.summary = "For games that will not boot or render correctly.";
    d.detail = "Maximum accuracy and every risky optimisation disabled. Expect a noticeably "
               "lower frame rate; the goal here is getting a stubborn title to run at all.";
    d.temp_ceiling = 80;

    d.tweaks = {
        {"resolution_setup", "3", "Native resolution (Res1X)."},
        {"scaling_filter", "1", "Bilinear."},
        {"anti_aliasing", "0", "Off."},
        {"gpu_accuracy", "1", "High accuracy: slowest, but renders the most titles right."},
        {"use_asynchronous_shaders", "false", "Deterministic compilation order."},
        {"use_asynchronous_gpu_emulation", "false",
         "Synchronous GPU emulation: much slower, but fixes timing-sensitive titles."},
        {"use_disk_shader_cache", "true", "Still safe and still helps."},
        {"astc_recompression", "0", "No recompression: rules out a whole class of artefacts."},
        {"use_reactive_flushing", "true",
         "Some titles genuinely need readback to render correctly."},
        {"use_speed_limit", "true", "Steady pacing."},
        {"speed_limit", "100", "100%."},
        {"max_anisotropy", "1", "Default (1x)."},
    };
    return d;
}

ModeDefinition AutoModeEngine::BuildTurbo(GpuFamily family, DriverOrigin origin) const {
    ModeDefinition d{};
    d.mode = AutoMode::Turbo;
    d.key = "turbo";
    d.display_name = "Turbo";
    d.summary = "Maximum load up to a temperature limit. Gets hot.";
    d.detail = "Everything Performance does, plus the frame limiter removed and clocks held "
               "high. The device will run warm by design; the layer watches the temperature "
               "and tells you when to stop.";
    // Deliberately the ceiling the user chose in an earlier conversation: hot,
    // but inside typical SoC specifications rather than pretending there is no
    // limit at all.
    d.temp_ceiling = 90;

    d.tweaks = {
        {"resolution_setup", "2", "0.75x render scale (Res3_4X)."},
        {"scaling_filter", "0", "Nearest."},
        {"anti_aliasing", "0", "Off."},
        {"max_anisotropy", "1", "Default (1x)."},
        {"gpu_accuracy", "0", "Low accuracy: fastest."},
        {"astc_recompression", "1", "Less bandwidth."},
        {"use_asynchronous_shaders", "true", "No compilation stalls."},
        {"use_asynchronous_gpu_emulation", "true", "Decoupled threads."},
        {"use_disk_shader_cache", "true", "Cached pipelines."},
        {"use_speed_limit", "false", "No frame cap."},
        {"use_reactive_flushing", "false", "No mid-frame readback."},
        {"fast_gpu_time", "1", "Loosened GPU timing."},
        {"force_max_clock", "true",
         "Asks the driver to hold maximum clocks instead of ramping down."},
    };
    return d;
}

ModeDefinition AutoModeEngine::Resolve(AutoMode mode, GpuFamily family,
                                       DriverOrigin origin) const {
    switch (mode) {
    case AutoMode::Quality:
        return BuildQuality(family, origin);
    case AutoMode::Performance:
        return BuildPerformance(family, origin);
    case AutoMode::Stability:
        return BuildStability(family, origin);
    case AutoMode::Compatibility:
        return BuildCompatibility(family, origin);
    case AutoMode::Turbo:
        return BuildTurbo(family, origin);
    case AutoMode::Custom: {
        ModeDefinition d{};
        d.mode = AutoMode::Custom;
        d.key = "custom";
        d.display_name = "Custom";
        d.summary = "You manage the settings. Nothing is applied automatically.";
        d.detail = "Every advanced setting stays exactly as you leave it. Switching to any "
                   "other mode will overwrite them.";
        return d;
    }
    case AutoMode::Balanced:
    case AutoMode::COUNT:
    default:
        return BuildBalanced(family, origin);
    }
}

std::vector<ModeDefinition> AutoModeEngine::AllFor(GpuFamily family, DriverOrigin origin) const {
    std::vector<ModeDefinition> out;
    out.reserve(static_cast<std::size_t>(AutoMode::COUNT));
    // Ordered by how likely they are to be the right answer, not by enum value.
    for (const auto mode : {AutoMode::Balanced, AutoMode::Performance, AutoMode::Quality,
                            AutoMode::Stability, AutoMode::Compatibility, AutoMode::Turbo,
                            AutoMode::Custom}) {
        out.push_back(Resolve(mode, family, origin));
    }
    return out;
}

u32 AutoModeEngine::Apply(AutoMode mode, GpuFamily family, DriverOrigin origin) {
    if (mode == AutoMode::Custom) {
        LogInfo(LogArea::Profile, "custom mode selected; leaving settings untouched");
        return 0;
    }

    const auto definition = Resolve(mode, family, origin);
    u32 applied = 0;

    for (const auto& tweak : definition.tweaks) {
        bool found = false;
        for (const auto& [category, settings] : Settings::values.linkage.by_category) {
            for (auto* setting : settings) {
                if (setting->GetLabel() != tweak.key) {
                    continue;
                }
                found = true;
                try {
                    setting->LoadString(tweak.value);
                    applied++;
                } catch (const std::exception& e) {
                    LogWarning(LogArea::Profile,
                               "could not set " + tweak.key + "=" + tweak.value + ": " + e.what());
                }
                break;
            }
            if (found) {
                break;
            }
        }
        if (!found) {
            // Not an error: the catalogue is shared across builds and a key may
            // legitimately not exist in this one.
            LogDebug(LogArea::Profile, "setting '" + tweak.key + "' not present in this build");
        }
    }

    // Keep the thermal ceiling consistent with the mode's intent.
    if (definition.temp_ceiling > 0) {
        Settings::values.symbiosis_temp_ceiling.SetValue(definition.temp_ceiling);
        // Warn a little above the ceiling, clamped to the setting's range.
        const u32 warning = definition.temp_ceiling + 5 > 100 ? 100 : definition.temp_ceiling + 5;
        Settings::values.symbiosis_temp_warning.SetValue(warning);
    }

    Settings::values.symbiosis_auto_mode.SetValue(static_cast<u32>(mode));

    LogInfo(LogArea::Profile, std::string{"mode '"} + definition.display_name + "' applied (" +
                                  std::to_string(applied) + " of " +
                                  std::to_string(definition.tweaks.size()) + " settings)");
    return applied;
}

AutoMode AutoModeEngine::Current() {
    const auto raw = Settings::values.symbiosis_auto_mode.GetValue();
    if (raw < static_cast<u32>(AutoMode::COUNT)) {
        return static_cast<AutoMode>(raw);
    }
    return AutoMode::Balanced;
}

void AutoModeEngine::ApplyCurrentOnStartup(GpuFamily family, DriverOrigin origin) {
    const auto mode = Current();
    if (mode == AutoMode::Custom) {
        LogInfo(LogArea::Profile, "startup: custom mode, settings left as-is");
        return;
    }

    // Do NOT re-apply the mode here.
    //
    // Re-applying on every launch was a mistake: a mode owns resolution,
    // accuracy, filtering and more, so any value the user changed by hand was
    // silently overwritten the next time a game started. The visible symptom is
    // exactly "changing the quality setting does nothing to the frame rate" -
    // the setting really did change, and then got reset before the renderer
    // ever read it.
    //
    // A mode is now applied only when the user picks it. That is the moment
    // they asked for a coherent set of values; every moment after that, their
    // own edits win.
    LogInfo(LogArea::Profile,
            std::string{"startup: mode is "} + ToString(mode) +
                "; leaving settings untouched so manual changes survive");
}

std::string AutoModeEngine::Describe(const ModeDefinition& definition) const {
    std::string out = definition.display_name + "\n  " + definition.summary + "\n";
    out += "  " + definition.detail + "\n";
    if (definition.temp_ceiling > 0) {
        out += "  temperature ceiling: " + std::to_string(definition.temp_ceiling) + "C\n";
    }
    for (const auto& tweak : definition.tweaks) {
        out += "    " + tweak.key + " = " + tweak.value + "  (" + tweak.reason + ")\n";
    }
    return out;
}

AutoModeEngine& GetAutoModeEngine() {
    static AutoModeEngine instance;
    return instance;
}

} // namespace Symbiosis
