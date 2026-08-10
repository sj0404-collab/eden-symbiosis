// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <cctype>
#include <mutex>

#include "common/logging.h"
#include "common/symbiosis/mali_tuning.h"
#include "common/symbiosis/symbiosis_log.h"

namespace Symbiosis {

namespace {

std::mutex g_mutex;
MaliTraits g_current{};

std::string Lower(std::string_view text) {
    std::string out{text};
    std::transform(out.begin(), out.end(), out.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return out;
}

/// Extracts the numeric part of a Mali model, e.g. "Mali-G610 MC6" -> 610.
/// Returns 0 when no model number can be found.
u32 ParseModelNumber(const std::string& lowered) {
    // Look for the first digit run after a 'g' or 't' that follows "mali".
    const auto mali = lowered.find("mali");
    if (mali == std::string::npos) {
        return 0;
    }
    for (std::size_t i = mali; i < lowered.size(); ++i) {
        const char c = lowered[i];
        if (c != 'g' && c != 't') {
            continue;
        }
        std::size_t j = i + 1;
        // Skip a separator such as '-' between the letter and the digits.
        while (j < lowered.size() && (lowered[j] == '-' || lowered[j] == ' ')) {
            ++j;
        }
        u32 value = 0;
        std::size_t digits = 0;
        while (j < lowered.size() && std::isdigit(static_cast<unsigned char>(lowered[j]))) {
            value = value * 10 + static_cast<u32>(lowered[j] - '0');
            ++j;
            ++digits;
        }
        if (digits >= 2) {
            return value;
        }
    }
    return 0;
}

/// Parses the "MCn" core count suffix.
u32 ParseCoreCount(const std::string& lowered) {
    const auto mc = lowered.find("mc");
    if (mc == std::string::npos) {
        return 0;
    }
    u32 value = 0;
    std::size_t digits = 0;
    for (std::size_t i = mc + 2; i < lowered.size(); ++i) {
        if (!std::isdigit(static_cast<unsigned char>(lowered[i]))) {
            break;
        }
        value = value * 10 + static_cast<u32>(lowered[i] - '0');
        ++digits;
    }
    return digits > 0 ? value : 0;
}

} // Anonymous namespace

const char* ToString(MaliGeneration generation) {
    switch (generation) {
    case MaliGeneration::NotMali:
        return "Not Mali";
    case MaliGeneration::Utgard:
        return "Utgard (Mali-4xx)";
    case MaliGeneration::Midgard:
        return "Midgard (T-series)";
    case MaliGeneration::BifrostGen1:
        return "Bifrost 1st gen";
    case MaliGeneration::BifrostGen2:
        return "Bifrost 2nd gen";
    case MaliGeneration::ValhallGen1:
        return "Valhall 1st gen";
    case MaliGeneration::ValhallGen2:
        return "Valhall 2nd gen";
    case MaliGeneration::ValhallGen3:
        return "Valhall 3rd gen";
    case MaliGeneration::Valhall5:
        return "Valhall 5th gen";
    case MaliGeneration::Immortalis:
        return "Immortalis";
    case MaliGeneration::UnknownMali:
        return "Mali (unrecognised)";
    }
    return "Unknown";
}

MaliTraits MaliTuning::Identify(std::string_view device_name, bool is_arm_driver,
                                u32 subgroup_size, u32 api_version) {
    MaliTraits traits{};
    traits.device_name = std::string{device_name};
    traits.subgroup_size = subgroup_size;
    // VK_API_VERSION_MINOR without pulling in vulkan.h here.
    traits.vulkan_11_plus = ((api_version >> 12) & 0x3FFU) >= 1;

    const std::string lowered = Lower(device_name);
    const bool looks_mali = lowered.find("mali") != std::string::npos ||
                            lowered.find("immortalis") != std::string::npos;

    if (!looks_mali && !is_arm_driver) {
        traits.generation = MaliGeneration::NotMali;
        return traits;
    }

    traits.core_count = ParseCoreCount(lowered);

    // Immortalis is branded separately from the G-series number.
    if (lowered.find("immortalis") != std::string::npos) {
        traits.generation = MaliGeneration::Immortalis;
        return traits;
    }

    const u32 model = ParseModelNumber(lowered);
    const bool t_series = lowered.find("mali-t") != std::string::npos ||
                          lowered.find("mali t") != std::string::npos;

    if (t_series) {
        traits.generation = MaliGeneration::Midgard;
        traits.weak_compute = true;
        traits.fragile_parallel_compile = true;
        return traits;
    }

    if (model == 0) {
        traits.generation = MaliGeneration::UnknownMali;
        // Unknown Mali: assume the conservative case rather than the optimistic
        // one. Being slightly slow on a modern part is far better than being
        // broken on an old one.
        traits.fragile_parallel_compile = !traits.vulkan_11_plus;
        return traits;
    }

    // Mali-4xx are Utgard and have no Vulkan driver at all; if we somehow see
    // one, treat it as unusable rather than pretending.
    if (model < 500 && model >= 400) {
        traits.generation = MaliGeneration::Utgard;
        traits.weak_compute = true;
        traits.fragile_parallel_compile = true;
        return traits;
    }

    // G-series generations. The numbering is not strictly monotonic across
    // generations, so the ranges are chosen to match ARM's own grouping.
    if (model == 31 || model == 51 || model == 71) {
        traits.generation = MaliGeneration::BifrostGen1;
        traits.weak_compute = true;
        traits.fragile_parallel_compile = true;
    } else if (model == 52 || model == 72 || model == 76) {
        traits.generation = MaliGeneration::BifrostGen2;
        traits.weak_compute = true;
    } else if (model == 57 || model == 77) {
        traits.generation = MaliGeneration::ValhallGen1;
    } else if (model == 68 || model == 78) {
        traits.generation = MaliGeneration::ValhallGen2;
    } else if (model == 310 || model == 510 || model == 610 || model == 710) {
        traits.generation = MaliGeneration::ValhallGen3;
    } else if (model == 615 || model == 715 || model == 720) {
        traits.generation = MaliGeneration::Valhall5;
    } else if (model >= 300) {
        // Newer three-digit part we do not know by name: assume Valhall-class.
        traits.generation = MaliGeneration::ValhallGen3;
    } else {
        // Two-digit part we do not know: assume Bifrost-class and be careful.
        traits.generation = MaliGeneration::BifrostGen2;
        traits.weak_compute = true;
    }

    return traits;
}

MaliAdvice MaliTuning::Advise(const MaliTraits& traits) {
    MaliAdvice advice{};

    // Reactive flushing forces the tiler to resolve and re-load a tile
    // mid-frame. On every Mali generation this is expensive; there is no
    // version where enabling it by default is right.
    advice.allow_reactive_flushing = false;

    switch (traits.generation) {
    case MaliGeneration::Utgard:
    case MaliGeneration::Midgard:
        advice.resolution_index = 2; // 0.75x
        advice.allow_async_shaders = false;
        advice.gpu_astc = false;
        advice.texture_budget_fraction = 0.35f;
        advice.rationale =
            "Midgard-class hardware predates any usable Vulkan support for this workload. "
            "Everything is set to the safest possible values; expect low frame rates.";
        break;

    case MaliGeneration::BifrostGen1:
        advice.resolution_index = 2; // 0.75x
        advice.allow_async_shaders = false;
        advice.gpu_astc = false;
        advice.texture_budget_fraction = 0.40f;
        advice.rationale =
            "First-generation Bifrost has weak compute throughput and a driver that is "
            "unreliable at parallel shader compilation, so shaders are compiled "
            "synchronously and ASTC is recompressed on the CPU.";
        break;

    case MaliGeneration::BifrostGen2:
        advice.resolution_index = 2; // 0.75x
        advice.allow_async_shaders = true;
        advice.gpu_astc = false;
        advice.texture_budget_fraction = 0.45f;
        advice.rationale =
            "Second-generation Bifrost handles asynchronous compilation, but compute is "
            "still slow enough that CPU-side ASTC recompression wins.";
        break;

    case MaliGeneration::ValhallGen1:
    case MaliGeneration::ValhallGen2:
        advice.resolution_index = 3; // native
        advice.allow_async_shaders = true;
        advice.gpu_astc = false;
        advice.texture_budget_fraction = 0.50f;
        advice.rationale =
            "Valhall runs native resolution comfortably. ASTC is still recompressed: the "
            "hardware can sample it, but the bandwidth saving from BC1 is worth more than "
            "the decode.";
        break;

    case MaliGeneration::ValhallGen3:
    case MaliGeneration::Valhall5:
        advice.resolution_index = 3; // native
        advice.allow_async_shaders = true;
        advice.gpu_astc = true;
        advice.texture_budget_fraction = 0.55f;
        advice.rationale =
            "Recent Valhall has enough memory bandwidth and compute to sample ASTC "
            "natively and hold native resolution.";
        break;

    case MaliGeneration::Immortalis:
        advice.resolution_index = 3; // native
        advice.allow_async_shaders = true;
        advice.gpu_astc = true;
        advice.texture_budget_fraction = 0.60f;
        advice.rationale =
            "Immortalis is the strongest Mali available and can be pushed hardest; it is "
            "still a tiler, so mid-frame readback stays disabled.";
        break;

    case MaliGeneration::UnknownMali:
        advice.resolution_index = 3;
        advice.allow_async_shaders = traits.vulkan_11_plus;
        advice.gpu_astc = false;
        advice.texture_budget_fraction = 0.45f;
        advice.rationale =
            "This Mali model is not in the table, so conservative defaults are used. "
            "Being a little slow on a modern part is better than being broken on an old one.";
        break;

    case MaliGeneration::NotMali:
    default:
        advice.resolution_index = 3;
        advice.allow_async_shaders = true;
        advice.gpu_astc = true;
        advice.texture_budget_fraction = 0.50f;
        advice.rationale = "Not a Mali GPU; Mali-specific tuning does not apply.";
        break;
    }

    // A part with very few cores cannot sustain native resolution regardless of
    // its generation. Core count is a better signal here than model number.
    if (traits.core_count > 0 && traits.core_count <= 2 && advice.resolution_index > 2) {
        advice.resolution_index = 2;
        advice.rationale += " Reduced to 0.75x because this part has only " +
                            std::to_string(traits.core_count) + " shader core(s).";
    }

    // Trust the observed driver traits over the generation guess.
    if (traits.fragile_parallel_compile) {
        advice.allow_async_shaders = false;
    }

    return advice;
}

std::string MaliTuning::Describe(const MaliTraits& traits, const MaliAdvice& advice) {
    std::string out = "Mali tuning:\n";
    out += "  device:      " + traits.device_name + "\n";
    out += "  generation:  ";
    out += ToString(traits.generation);
    out += "\n";
    if (traits.core_count > 0) {
        out += "  cores:       " + std::to_string(traits.core_count) + "\n";
    }
    if (traits.subgroup_size > 0) {
        out += "  subgroup:    " + std::to_string(traits.subgroup_size) + "\n";
    }
    out += "  Vulkan 1.1+: ";
    out += traits.vulkan_11_plus ? "yes" : "no";
    out += "\n";
    out += "  advice:\n";
    out += "    render scale index:   " + std::to_string(advice.resolution_index) + "\n";
    out += "    async shaders:        ";
    out += advice.allow_async_shaders ? "yes" : "no";
    out += "\n";
    out += "    reactive flushing:    ";
    out += advice.allow_reactive_flushing ? "yes" : "no";
    out += "\n";
    out += "    GPU ASTC:             ";
    out += advice.gpu_astc ? "yes" : "no";
    out += "\n";
    out += "    texture budget:       " +
           std::to_string(static_cast<int>(advice.texture_budget_fraction * 100)) + "%\n";
    out += "  " + advice.rationale + "\n";
    return out;
}

void MaliTuning::Publish(const MaliTraits& traits) {
    {
        std::scoped_lock lock{g_mutex};
        g_current = traits;
    }
    if (traits.generation != MaliGeneration::NotMali) {
        LogInfo(LogArea::Device, std::string{"Mali detected: "} + traits.device_name + " (" +
                                     ToString(traits.generation) + ")");
    }
}

MaliTraits MaliTuning::Current() {
    std::scoped_lock lock{g_mutex};
    return g_current;
}

std::vector<DriverSuggestion> SuggestDrivers(const MaliTraits& traits) {
    std::vector<DriverSuggestion> out;

    // The system driver is always present and, on Mali, almost always the only
    // usable one.
    out.push_back(DriverSuggestion{
        .name = "System driver (ARM Mali)",
        .description = traits.device_name.empty() ? "The driver your device shipped with."
                                                  : traits.device_name + " vendor driver",
        .url = {},
        .is_system = true,
        .verdict = "In use. On Mali this is normally the best and only option.",
    });

    if (traits.generation == MaliGeneration::NotMali) {
        out.push_back(DriverSuggestion{
            .name = "Mesa Turnip",
            .description = "Open-source Vulkan driver for Qualcomm Adreno.",
            .url = "https://github.com/K11MCH1/AdrenoToolsDrivers/releases",
            .is_system = false,
            .verdict = "Only relevant on Adreno hardware.",
        });
        return out;
    }

    // PanVK is the only Mesa driver targeting Mali. It is genuinely
    // experimental: on most phones it cannot be loaded at all because the
    // loader path adrenotools provides is Adreno-specific.
    out.push_back(DriverSuggestion{
        .name = "Mesa PanVK",
        .description = "Open-source Vulkan driver for Mali (Panfrost project).",
        .url = "https://docs.mesa3d.org/drivers/panvk.html",
        .is_system = false,
        .verdict = traits.generation >= MaliGeneration::ValhallGen1
                       ? "Experimental. Targets Valhall, but most retail phones provide no "
                         "supported way to load a replacement Vulkan driver, so it will "
                         "usually not activate."
                       : "Not usable on this generation.",
    });

    out.push_back(DriverSuggestion{
        .name = "Custom driver loading",
        .description = "Replacing the Vulkan driver at runtime.",
        .url = {},
        .is_system = false,
        .verdict = "Unlike Adreno, Mali has no widely deployed equivalent of the "
                   "adrenotools loader. If your device refuses custom drivers, that is "
                   "expected and not a fault. The layer works around it with emulated "
                   "fallbacks instead.",
    });

    return out;
}

std::string FirmwareAdvice(const MaliTraits& traits) {
    std::string out;
    out += "Firmware and keys are dumped from a Nintendo Switch you own. They are not "
           "distributed with this application and cannot be downloaded from it.\n\n";
    out += "What the emulator needs:\n";
    out += "  prod.keys   - required. Without it, no commercial game will load.\n";
    out += "  title.keys  - needed for some titles and for eShop content.\n";
    out += "  firmware    - required for system applets, online features and a number of "
           "games that call into system services.\n\n";
    out += "Homebrew listed in the catalogue needs none of these, which makes it the "
           "quickest way to confirm the emulator itself works.\n";

    if (traits.generation != MaliGeneration::NotMali &&
        traits.generation <= MaliGeneration::BifrostGen2) {
        out += "\nNote for this device: firmware applets are comparatively heavy. On "
               "older Mali parts they can take a long time to appear.\n";
    }
    return out;
}

} // namespace Symbiosis
