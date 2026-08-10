// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>

#include "common/logging.h"
#include "common/symbiosis/device_profiles.h"

namespace Symbiosis {

const char* ToString(TuningIntent intent) {
    switch (intent) {
    case TuningIntent::MaxFps:
        return "Max FPS";
    case TuningIntent::Balanced:
        return "Balanced";
    case TuningIntent::Quality:
        return "Quality";
    case TuningIntent::Battery:
        return "Battery";
    }
    return "Unknown";
}

ProfileEngine::ProfileEngine() {
    BuildCatalogue();
}

void ProfileEngine::BuildCatalogue() {
    profiles.clear();
    profiles.reserve(16);

    // ---------------------------------------------------------------------
    // Mali + stock vendor driver.
    //
    // This is the hardest configuration and the one the user actually has:
    // no custom driver can be installed, so every gain must come from making
    // the emulator ask the driver for less work.
    //
    // Mali tilers hate: frequent renderpass restarts, large descriptor churn,
    // and any readback that forces a tile flush. They tolerate raw fillrate
    // reasonably well at native resolution but collapse when upscaled.
    // ---------------------------------------------------------------------
    profiles.push_back(Profile{
        .id = "mali_stock_maxfps",
        .display_name = "Mali (system driver) — Max FPS",
        .summary = "Aggressively cuts GPU work. Expect visual glitches in some titles.",
        .family = GpuFamily::Mali,
        .origin = DriverOrigin::System,
        .intent = TuningIntent::MaxFps,
        .tweaks =
            {
                {"resolution_setup", "2", "0.75x render scale (Res3_4X): Mali fillrate is the main wall."},
                {"scaling_filter", "0", "Nearest scaling costs almost nothing to composite."},
                {"anti_aliasing", "0", "No AA: every full-screen pass costs a tile flush."},
                {"accelerate_astc", "0",
                 "CPU ASTC decode: Mali's native path stalls the tiler on many blobs."},
                {"use_asynchronous_shaders", "true",
                 "Avoids multi-second freezes when new shaders appear."},
                {"use_vsync", "0",
                 "Immediate present removes the queue wait that pins Mali to 30 FPS."},
                {"use_reactive_flushing", "false",
                 "Reactive flushing forces mid-frame readbacks -- very costly on a tiler."},
                {"astc_recompression", "1", "Recompress to BC1 to cut memory bandwidth."},
                {"max_anisotropy", "0", "Anisotropic filtering is disproportionately slow on Mali."},
            },
        .expected_effect = "+20-40% FPS, noticeably softer image",
        .works_on_stock_driver = true,
    });

    profiles.push_back(Profile{
        .id = "mali_stock_balanced",
        .display_name = "Mali (system driver) — Balanced",
        .summary = "Recommended starting point when you cannot install a custom driver.",
        .family = GpuFamily::Mali,
        .origin = DriverOrigin::System,
        .intent = TuningIntent::Balanced,
        .tweaks =
            {
                {"resolution_setup", "3", "Native 1x (Res1X): the sweet spot for Mali bandwidth."},
                {"scaling_filter", "1", "Bilinear: cheap and hides the lack of AA."},
                {"anti_aliasing", "0", "AA remains too expensive on a tiler."},
                {"use_asynchronous_shaders", "true", "Keeps frame pacing smooth while compiling."},
                {"astc_recompression", "1", "Less bandwidth for the same visual result."},
                {"use_vsync", "1", "Mailbox: no tearing without the FIFO stall."},
                {"use_reactive_flushing", "false", "Avoids tiler-hostile mid-frame readback."},
            },
        .expected_effect = "+10-20% FPS versus defaults, image unchanged",
        .works_on_stock_driver = true,
    });

    profiles.push_back(Profile{
        .id = "mali_stock_quality",
        .display_name = "Mali (system driver) — Quality",
        .summary = "For light 2D or visual-novel titles that already hit the frame cap.",
        .family = GpuFamily::Mali,
        .origin = DriverOrigin::System,
        .intent = TuningIntent::Quality,
        .tweaks =
            {
                {"resolution_setup", "5", "1.5x scale (Res3_2X), only viable in light titles."},
                {"scaling_filter", "3", "Bicubic for a cleaner upscale."},
                {"anti_aliasing", "1", "FXAA is the only AA a tiler can afford."},
                {"max_anisotropy", "2", "2x aniso: visible gain, moderate cost."},
                {"astc_recompression", "0", "Keep original ASTC quality."},
            },
        .expected_effect = "Sharper image, -20-30% FPS",
        .works_on_stock_driver = true,
    });

    profiles.push_back(Profile{
        .id = "mali_stock_battery",
        .display_name = "Mali (system driver) — Battery / cool",
        .summary = "Caps work to reduce heat and throttling on long sessions.",
        .family = GpuFamily::Mali,
        .origin = DriverOrigin::System,
        .intent = TuningIntent::Battery,
        .tweaks =
            {
                {"resolution_setup", "2", "0.75x scale (Res3_4X) cuts GPU power draw the most."},
                {"use_speed_limit", "true", "Frame limiter prevents pointless overdraw."},
                {"speed_limit", "100", "Cap at 100%: no benefit from running hotter."},
                {"anti_aliasing", "0", "Every extra pass is extra heat."},
                {"use_vsync", "2", "FIFO parks the GPU between frames."},
                {"scaling_filter", "0", "Cheapest possible composite."},
            },
        .expected_effect = "Lower temperature, far less throttling late in a session",
        .works_on_stock_driver = true,
    });

    // ---------------------------------------------------------------------
    // Mali + PanVK (Mesa). Rare but growing; different trade-offs.
    // ---------------------------------------------------------------------
    profiles.push_back(Profile{
        .id = "mali_panvk_balanced",
        .display_name = "Mali + PanVK (Mesa) — Balanced",
        .summary = "PanVK handles descriptors better but has gaps in compute.",
        .family = GpuFamily::Mali,
        .origin = DriverOrigin::PanVK,
        .intent = TuningIntent::Balanced,
        .tweaks =
            {
                {"resolution_setup", "3", "Native resolution (Res1X)."},
                {"use_asynchronous_shaders", "true", "PanVK compiles slowly; hide it."},
                {"accelerate_astc", "0", "PanVK ASTC support is incomplete."},
                {"astc_recompression", "1", "Recompression sidesteps the gaps."},
                {"use_reactive_flushing", "false", "Still a tiler underneath."},
            },
        .expected_effect = "Comparable to the vendor blob, fewer shader stutters",
        .works_on_stock_driver = false,
    });

    // ---------------------------------------------------------------------
    // Adreno + Turnip. The configuration most guides are written for.
    // ---------------------------------------------------------------------
    profiles.push_back(Profile{
        .id = "adreno_turnip_maxfps",
        .display_name = "Adreno + Turnip — Max FPS",
        .summary = "Turnip's low CPU overhead lets you push scheduling hard.",
        .family = GpuFamily::Adreno,
        .origin = DriverOrigin::Turnip,
        .intent = TuningIntent::MaxFps,
        .tweaks =
            {
                {"resolution_setup", "2", "0.75x scale (Res3_4X)."},
                {"use_asynchronous_shaders", "true", "Turnip benefits the most from this."},
                {"use_vsync", "0", "Immediate present."},
                {"anti_aliasing", "0", "No AA."},
                {"scaling_filter", "0", "Nearest."},
                {"accelerate_astc", "1", "Turnip's GPU ASTC path is genuinely fast."},
            },
        .expected_effect = "+25-45% FPS over the Qualcomm blob",
        .works_on_stock_driver = false,
    });

    profiles.push_back(Profile{
        .id = "adreno_stock_balanced",
        .display_name = "Adreno (Qualcomm driver) — Balanced",
        .summary = "For devices where Turnip cannot be installed.",
        .family = GpuFamily::Adreno,
        .origin = DriverOrigin::System,
        .intent = TuningIntent::Balanced,
        .tweaks =
            {
                {"resolution_setup", "3", "Native resolution (Res1X)."},
                {"use_asynchronous_shaders", "true", "The Qualcomm blob stutters badly without it."},
                {"astc_recompression", "1", "Saves bandwidth."},
                {"use_vsync", "1", "Mailbox."},
            },
        .expected_effect = "+10-15% FPS, far fewer stutters",
        .works_on_stock_driver = true,
    });

    // ---------------------------------------------------------------------
    // Other families, kept short but honest.
    // ---------------------------------------------------------------------
    profiles.push_back(Profile{
        .id = "xclipse_balanced",
        .display_name = "Samsung Xclipse — Balanced",
        .summary = "RDNA-derived: behaves more like a desktop GPU than a tiler.",
        .family = GpuFamily::Xclipse,
        .origin = DriverOrigin::System,
        .intent = TuningIntent::Balanced,
        .tweaks =
            {
                {"resolution_setup", "3", "Native resolution (Res1X)."},
                {"use_reactive_flushing", "true", "Xclipse tolerates readback far better."},
                {"accelerate_astc", "1", "Native ASTC works well here."},
                {"use_asynchronous_shaders", "true", "Smoother first run."},
            },
        .expected_effect = "Stable frame pacing, accurate image",
        .works_on_stock_driver = true,
    });

    profiles.push_back(Profile{
        .id = "powervr_safe",
        .display_name = "PowerVR — Compatibility",
        .summary = "PowerVR Vulkan drivers are fragile; this profile favours not crashing.",
        .family = GpuFamily::PowerVR,
        .origin = DriverOrigin::System,
        .intent = TuningIntent::Balanced,
        .tweaks =
            {
                {"resolution_setup", "2", "0.75x (Res3_4X) to stay inside driver limits."},
                {"accelerate_astc", "0", "Native ASTC is a common crash source."},
                {"astc_recompression", "1", "Recompress instead."},
                {"anti_aliasing", "0", "Extra passes trip driver bugs."},
                {"use_asynchronous_shaders", "false",
                 "Async shader compile is unstable on several PowerVR blobs."},
            },
        .expected_effect = "Fewer crashes; performance is secondary",
        .works_on_stock_driver = true,
    });

    // Generic fallback for unknown hardware.
    profiles.push_back(Profile{
        .id = "generic_balanced",
        .display_name = "Generic — Balanced",
        .summary = "Conservative defaults used when the GPU cannot be identified.",
        .family = GpuFamily::Unknown,
        .origin = DriverOrigin::System,
        .intent = TuningIntent::Balanced,
        .tweaks =
            {
                {"resolution_setup", "3", "Native resolution (Res1X) is always a safe baseline."},
                {"use_asynchronous_shaders", "true", "Helps on essentially every GPU."},
                {"astc_recompression", "1", "Bandwidth is scarce on all mobile parts."},
            },
        .expected_effect = "Safe baseline",
        .works_on_stock_driver = true,
    });

    LOG_INFO(Common, "[Symbiosis] profile catalogue: {} entries", profiles.size());
}

std::vector<Profile> ProfileEngine::ProfilesFor(GpuFamily family, DriverOrigin origin) const {
    std::vector<Profile> matches;
    for (const auto& profile : profiles) {
        // Immortalis is Valhall-derived; Mali profiles apply directly.
        const bool family_ok =
            profile.family == family ||
            (family == GpuFamily::Immortalis && profile.family == GpuFamily::Mali);
        if (!family_ok) {
            continue;
        }
        matches.push_back(profile);
    }

    // Exact driver-origin matches first, then the rest.
    std::stable_sort(matches.begin(), matches.end(),
                     [origin](const Profile& a, const Profile& b) {
                         const int sa = a.origin == origin ? 0 : 1;
                         const int sb = b.origin == origin ? 0 : 1;
                         return sa < sb;
                     });

    if (matches.empty()) {
        for (const auto& profile : profiles) {
            if (profile.family == GpuFamily::Unknown) {
                matches.push_back(profile);
            }
        }
    }
    return matches;
}

Profile ProfileEngine::Resolve(GpuFamily family, DriverOrigin origin,
                               TuningIntent intent) const {
    const auto candidates = ProfilesFor(family, origin);

    // Exact intent + exact origin.
    for (const auto& profile : candidates) {
        if (profile.intent == intent && profile.origin == origin) {
            return profile;
        }
    }
    // Exact intent, any origin.
    for (const auto& profile : candidates) {
        if (profile.intent == intent) {
            return profile;
        }
    }
    // Anything for this family.
    if (!candidates.empty()) {
        return candidates.front();
    }
    // Last resort: the generic profile always exists.
    return profiles.back();
}

std::string ProfileEngine::Describe(const Profile& profile) const {
    std::string out = profile.display_name + "\n";
    out += "  " + profile.summary + "\n";
    out += "  intent: ";
    out += ToString(profile.intent);
    out += ", expected: " + profile.expected_effect + "\n";
    if (!profile.works_on_stock_driver) {
        out += "  requires a custom driver\n";
    }
    for (const auto& tweak : profile.tweaks) {
        out += "    " + tweak.key + " = " + tweak.value + "  (" + tweak.reason + ")\n";
    }
    return out;
}

ProfileEngine& GetProfileEngine() {
    static ProfileEngine instance;
    return instance;
}

} // namespace Symbiosis
