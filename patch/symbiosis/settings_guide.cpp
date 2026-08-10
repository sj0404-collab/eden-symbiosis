// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <cctype>

#include "common/logging.h"
#include "common/symbiosis/settings_guide.h"

namespace Symbiosis {

namespace {

std::string Lower(std::string_view text) {
    std::string out{text};
    std::transform(out.begin(), out.end(), out.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return out;
}

} // Anonymous namespace

const char* ToString(GuideRisk risk) {
    switch (risk) {
    case GuideRisk::Safe:
        return "Safe";
    case GuideRisk::Tradeoff:
        return "Trade-off";
    case GuideRisk::Risky:
        return "Risky";
    case GuideRisk::Debug:
        return "Debug only";
    }
    return "?";
}

const char* ToString(GuideSection section) {
    switch (section) {
    case GuideSection::Modes:
        return "Modes";
    case GuideSection::Graphics:
        return "Graphics";
    case GuideSection::Advanced:
        return "Advanced";
    case GuideSection::Hacks:
        return "Hacks";
    case GuideSection::System:
        return "System";
    case GuideSection::Audio:
        return "Audio";
    case GuideSection::Overlay:
        return "Overlay";
    case GuideSection::Symbiosis:
        return "Symbiosis";
    case GuideSection::Utilities:
        return "Utilities";
    case GuideSection::COUNT:
        break;
    }
    return "?";
}

SettingsGuide::SettingsGuide() {
    Build();
}

void SettingsGuide::Build() {
    entries.clear();
    entries.reserve(64);

    // ---------------------------------------------------------------------
    // Modes
    // ---------------------------------------------------------------------
    entries.push_back({
        .key = "symbiosis_auto_mode",
        .title = "Mode",
        .section = GuideSection::Modes,
        .risk = GuideRisk::Safe,
        .what = "Applies a curated set of about a dozen settings at once, chosen to work "
                "together for one goal.",
        .cost = "Overwrites the settings it covers at the moment you pick it. Your later "
                "manual edits are kept.",
        .advice = "Balanced is right for most people. Pick a mode first, then adjust "
                  "individual settings if you want to.",
    });
    entries.push_back({
        .key = "",
        .title = "Which mode should I use?",
        .section = GuideSection::Modes,
        .risk = GuideRisk::Safe,
        .what = "Balanced: native resolution, no hacks. Performance: 0.75x scale, no frame "
                "cap. Quality: higher resolution and filtering. Stability: fewest crashes. "
                "Compatibility: maximum accuracy for stubborn games. Turbo: everything "
                "Performance does plus held clocks, up to a temperature limit.",
        .cost = "Quality costs frame rate. Performance costs image quality. Turbo costs "
                "heat and battery. Compatibility costs a lot of speed.",
        .advice = "Start on Balanced. Drop to Performance if a game is too slow, and to "
                  "Stability or Compatibility only if something is actually broken.",
    });

    // ---------------------------------------------------------------------
    // Graphics
    // ---------------------------------------------------------------------
    entries.push_back({
        .key = "resolution_setup",
        .title = "Resolution",
        .section = GuideSection::Graphics,
        .risk = GuideRisk::Tradeoff,
        .what = "Internal render scale. 1x is the resolution games were designed for; "
                "lower renders fewer pixels and upscales, higher renders more.",
        .cost = "The single biggest lever on frame rate. Cost rises roughly with the "
                "square of the scale: 2x is about four times the pixels.",
        .advice = "On a mobile GPU, 0.75x is usually the difference between playable and "
                  "not. Above 1.5x expect a slideshow on anything demanding.",
    });
    entries.push_back({
        .key = "use_vsync",
        .title = "VSync mode",
        .section = GuideSection::Graphics,
        .risk = GuideRisk::Tradeoff,
        .what = "How finished frames are handed to the display. Immediate sends them "
                "straight away, Mailbox keeps the newest and drops the rest, FIFO waits "
                "for the display refresh.",
        .cost = "Immediate can tear. FIFO caps the frame rate to the refresh rate and can "
                "stall the GPU waiting. Mailbox sits between the two.",
        .advice = "Mailbox is the safe default on mobile. Try Immediate if the frame rate "
                  "feels pinned below what the device can do; use FIFO to save battery.",
    });
    entries.push_back({
        .key = "scaling_filter",
        .title = "Scaling filter",
        .section = GuideSection::Graphics,
        .risk = GuideRisk::Safe,
        .what = "How the rendered image is resized to fit the screen.",
        .cost = "Nearest is free but blocky. Bilinear is nearly free. Bicubic and Lanczos "
                "cost a little. FSR costs more but reconstructs detail.",
        .advice = "Bilinear unless you have a reason. Nearest is the right choice with the "
                  "retro launchers, where the pixelation is the point.",
    });
    entries.push_back({
        .key = "anti_aliasing",
        .title = "Anti-aliasing",
        .section = GuideSection::Graphics,
        .risk = GuideRisk::Tradeoff,
        .what = "Smooths jagged edges after the frame is rendered.",
        .cost = "A full-screen pass. On a tile-based mobile GPU this is disproportionately "
                "expensive because it forces the tiler to resolve the whole frame.",
        .advice = "Leave it off on mobile. If you want it, FXAA is the only affordable "
                  "option; SMAA costs roughly twice as much.",
    });

    // ---------------------------------------------------------------------
    // Advanced
    // ---------------------------------------------------------------------
    entries.push_back({
        .key = "gpu_accuracy",
        .title = "GPU accuracy",
        .section = GuideSection::Advanced,
        .risk = GuideRisk::Tradeoff,
        .what = "How faithfully the emulated GPU follows hardware behaviour. High checks "
                "more cases and takes more shortcuts away.",
        .cost = "High is meaningfully slower. Low is faster and correct for most games.",
        .advice = "Low unless a specific game renders wrongly. Raising it is a fix for a "
                  "visual bug, not a general improvement.",
    });
    entries.push_back({
        .key = "use_asynchronous_shaders",
        .title = "Asynchronous shaders",
        .section = GuideSection::Advanced,
        .risk = GuideRisk::Tradeoff,
        .what = "Compiles new shaders on background threads instead of stopping the game "
                "until they are ready.",
        .cost = "Objects can briefly render wrongly or not at all while their shader is "
                "still compiling.",
        .advice = "Keep it on. The alternative is multi-second freezes whenever something "
                  "new appears. Turn it off only if a driver miscompiles under threading, "
                  "which mostly affects older Mali parts.",
    });
    entries.push_back({
        .key = "use_disk_shader_cache",
        .title = "Disk shader cache",
        .section = GuideSection::Advanced,
        .risk = GuideRisk::Safe,
        .what = "Saves compiled shaders so the next run of the same game does not rebuild "
                "them.",
        .cost = "Disk space, and the cache is invalidated when the driver changes.",
        .advice = "Always on. This is why a second play session stutters far less than the "
                  "first.",
    });
    entries.push_back({
        .key = "use_reactive_flushing",
        .title = "Reactive flushing",
        .section = GuideSection::Advanced,
        .risk = GuideRisk::Risky,
        .what = "Reads GPU memory back to the CPU when a game asks for it mid-frame.",
        .cost = "On a tile-based GPU this forces the tiler to resolve and reload a tile, "
                "which is one of the most expensive things you can ask it to do.",
        .advice = "Off on mobile. A small number of games need it to render correctly; the "
                  "Compatibility mode turns it on for exactly that reason.",
    });
    entries.push_back({
        .key = "astc_recompression",
        .title = "ASTC recompression",
        .section = GuideSection::Advanced,
        .risk = GuideRisk::Tradeoff,
        .what = "Converts ASTC textures to a simpler compressed format the GPU samples "
                "more cheaply.",
        .cost = "A small, usually invisible loss of texture quality.",
        .advice = "On for mobile: the memory bandwidth saved is worth more than the "
                  "quality difference, especially on a device short of RAM.",
    });
    entries.push_back({
        .key = "max_anisotropy",
        .title = "Anisotropic filtering",
        .section = GuideSection::Advanced,
        .risk = GuideRisk::Tradeoff,
        .what = "Sharpens textures viewed at a steep angle, such as floors stretching away "
                "from the camera.",
        .cost = "Extra texture samples per pixel. Costs more on mobile than the setting "
                "suggests.",
        .advice = "Default or 2x. Higher values are rarely worth the frames on a phone.",
    });

    // ---------------------------------------------------------------------
    // Hacks - the section that most needs explaining
    // ---------------------------------------------------------------------
    entries.push_back({
        .key = "fast_gpu_time",
        .title = "Fast GPU time",
        .section = GuideSection::Hacks,
        .risk = GuideRisk::Risky,
        .what = "Lies to the game about how long the GPU took, so it queues work sooner.",
        .cost = "Games that pace themselves against GPU timing can run too fast, animate "
                "oddly, or become unstable.",
        .advice = "A real speed-up in many titles, and a real breakage in some. Try it per "
                  "game rather than globally.",
    });
    entries.push_back({
        .key = "force_max_clock",
        .title = "Force maximum clocks",
        .section = GuideSection::Hacks,
        .risk = GuideRisk::Risky,
        .what = "Asks the driver to keep the GPU at its highest frequency instead of "
                "scaling down.",
        .cost = "Considerably more heat and battery drain. On a phone this often backfires: "
                "the device hits its thermal limit sooner and throttles harder than it "
                "would have.",
        .advice = "Only for short sessions. Turbo mode enables it deliberately, together "
                  "with a temperature ceiling.",
    });
    entries.push_back({
        .key = "",
        .title = "Why is there a section called Hacks?",
        .section = GuideSection::Hacks,
        .risk = GuideRisk::Debug,
        .what = "These settings deliberately break emulation accuracy to gain speed or to "
                "work around a specific driver bug.",
        .cost = "Each one is correct for some games and wrong for others. There is no "
                "combination that is right everywhere.",
        .advice = "Leave them alone unless you are chasing a specific problem. If a game "
                  "misbehaves after you change one, change it back before looking anywhere "
                  "else.",
    });

    // ---------------------------------------------------------------------
    // System
    // ---------------------------------------------------------------------
    entries.push_back({
        .key = "use_speed_limit",
        .title = "Frame limiter",
        .section = GuideSection::System,
        .risk = GuideRisk::Safe,
        .what = "Caps emulation at the intended speed instead of running as fast as it can.",
        .cost = "None, unless you want the game to run faster than designed.",
        .advice = "Keep it on. Without it the device burns power rendering frames beyond "
                  "what the game expects, which mostly produces heat.",
    });
    entries.push_back({
        .key = "cpu_accuracy",
        .title = "CPU accuracy",
        .section = GuideSection::System,
        .risk = GuideRisk::Risky,
        .what = "How precisely the emulated CPU reproduces hardware behaviour, including "
                "floating-point edge cases.",
        .cost = "Unsafe modes are faster but can corrupt game logic in ways that look like "
                "random bugs much later.",
        .advice = "Leave on the default. This is not a performance setting worth touching.",
    });
    entries.push_back({
        .key = "memory_layout_mode",
        .title = "Memory layout",
        .section = GuideSection::System,
        .risk = GuideRisk::Risky,
        .what = "How much RAM the emulated console reports having.",
        .cost = "Larger layouts need proportionally more real memory. On an 8 GB phone "
                "where about 5 GB is usable, asking for 8 GB gets the app killed.",
        .advice = "Leave at the default. The Symbiosis memory governor blocks the extended "
                  "layout automatically when there is not enough headroom.",
    });

    // ---------------------------------------------------------------------
    // Symbiosis-specific
    // ---------------------------------------------------------------------
    entries.push_back({
        .key = "symbiosis_mode",
        .title = "Symbiosis layer",
        .section = GuideSection::Symbiosis,
        .risk = GuideRisk::Safe,
        .what = "Surveys every Vulkan driver on the device and composes a working set of "
                "capabilities from them, instead of depending on one.",
        .cost = "Almost none. Off makes the app behave exactly like stock Eden.",
        .advice = "Cooperative is the default and right for nearly everyone. Desperate "
                  "loads drivers known to be risky; only for troubleshooting.",
    });
    entries.push_back({
        .key = "symbiosis_memory_governor",
        .title = "Memory governor",
        .section = GuideSection::Symbiosis,
        .risk = GuideRisk::Safe,
        .what = "Computes an honest memory budget and caps the texture cache to fit inside "
                "it, rather than trusting what the OS reports as free.",
        .cost = "Slightly smaller texture cache, so marginally more re-uploading.",
        .advice = "Keep it on for a device with 8 GB or less. It is what stops the system "
                  "killing the app mid-game.",
    });
    entries.push_back({
        .key = "symbiosis_launcher",
        .title = "Launcher",
        .section = GuideSection::Symbiosis,
        .risk = GuideRisk::Safe,
        .what = "Changes the look of the app, and for the retro presets also how the frame "
                "is presented: lower effective resolution, fewer colours, dithering.",
        .cost = "Retro presets render far fewer pixels, so they are faster, not slower.",
        .advice = "Purely a preference. The retro presets are a real rendering change, not "
                  "a filter painted on top.",
    });
    entries.push_back({
        .key = "symbiosis_pause_on_menu",
        .title = "Pause when the menu opens",
        .section = GuideSection::Overlay,
        .risk = GuideRisk::Safe,
        .what = "Whether emulation stops while you are in the in-game menu or settings.",
        .cost = "Pausing saves battery and is safest. Not pausing keeps timers and online "
                "sessions running.",
        .advice = "Pause unless you are playing something that cannot be interrupted, or "
                  "you want to watch a setting take effect live.",
    });
    entries.push_back({
        .key = "symbiosis_floating_button",
        .title = "Floating menu button",
        .section = GuideSection::Overlay,
        .risk = GuideRisk::Safe,
        .what = "An on-screen button that opens the in-game menu, so the system back "
                "gesture is not needed.",
        .cost = "Occupies a small part of the screen.",
        .advice = "Position, transparency and visibility are all adjustable. Long-press it "
                  "to toggle the touch controls.",
    });
    entries.push_back({
        .key = "symbiosis_show_mode_overlay",
        .title = "Show mode on screen",
        .section = GuideSection::Overlay,
        .risk = GuideRisk::Safe,
        .what = "Adds the active mode and render scale to the performance overlay.",
        .cost = "None.",
        .advice = "Useful while tuning: it is the quickest way to confirm a setting "
                  "actually took effect.",
    });
    entries.push_back({
        .key = "symbiosis_temp_ceiling",
        .title = "Temperature ceiling",
        .section = GuideSection::Symbiosis,
        .risk = GuideRisk::Tradeoff,
        .what = "The temperature above which the layer stops pushing for more performance.",
        .cost = "Higher ceilings mean more sustained speed and more heat.",
        .advice = "90 C is hot but within typical SoC specifications. Above that you are "
                  "trading hardware longevity for frames.",
    });

    // ---------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------
    entries.push_back({
        .key = "",
        .title = "Firmware slimmer",
        .section = GuideSection::Utilities,
        .risk = GuideRisk::Tradeoff,
        .what = "Deletes firmware content you do not use. Applet packages - browser, "
                "album, controller UI - are usually 150-200 MB of a 300 MB dump.",
        .cost = "Removed applets stop working until firmware is reinstalled.",
        .advice = "Safe to remove applets if you only play games. Compression is not "
                  "offered because firmware is encrypted and cannot be compressed.",
    });
    entries.push_back({
        .key = "",
        .title = "Dump doctor",
        .section = GuideSection::Utilities,
        .risk = GuideRisk::Safe,
        .what = "Checks a dump before you launch it: whether it is truncated, and whether "
                "it carries cartridge padding that can be reclaimed.",
        .cost = "None; it only reads headers.",
        .advice = "Run it on any file that fails to load. 'Truncated' means the download "
                  "or dump never finished, which is not an emulator problem.",
    });
    entries.push_back({
        .key = "",
        .title = "Save vault",
        .section = GuideSection::Utilities,
        .risk = GuideRisk::Safe,
        .what = "Keeps rotating copies of your saves outside the app's private storage.",
        .cost = "Disk space proportional to your saves.",
        .advice = "Worth enabling before you ever need it. Clearing app data destroys "
                  "saves permanently, and that is the first thing people try when an "
                  "emulator misbehaves.",
    });
    entries.push_back({
        .key = "",
        .title = "Shared data folder",
        .section = GuideSection::Utilities,
        .risk = GuideRisk::Tradeoff,
        .what = "Points this build at another Eden installation's folder so firmware, keys "
                "and games are not duplicated.",
        .cost = "Both builds must never run at the same time; two processes writing one "
                "shader cache will corrupt it. A lock file guards against this.",
        .advice = "Set it up at first launch if you already have Eden installed. On "
                  "Android 11 and newer you may need a neutral folder outside Android/data.",
    });

    LOG_INFO(Common, "[Symbiosis] settings guide: {} entries", entries.size());
}

std::vector<GuideEntry> SettingsGuide::ForSection(GuideSection section) const {
    std::vector<GuideEntry> out;
    for (const auto& entry : entries) {
        if (entry.section == section) {
            out.push_back(entry);
        }
    }
    return out;
}

const GuideEntry* SettingsGuide::ForKey(std::string_view key) const {
    if (key.empty()) {
        return nullptr;
    }
    for (const auto& entry : entries) {
        if (entry.key == key) {
            return &entry;
        }
    }
    return nullptr;
}

std::vector<GuideEntry> SettingsGuide::Search(std::string_view query) const {
    std::vector<GuideEntry> out;
    if (query.empty()) {
        return out;
    }
    const std::string needle = Lower(query);
    for (const auto& entry : entries) {
        const bool hit = Lower(entry.title).find(needle) != std::string::npos ||
                         Lower(entry.what).find(needle) != std::string::npos ||
                         Lower(entry.advice).find(needle) != std::string::npos ||
                         Lower(entry.key).find(needle) != std::string::npos;
        if (hit) {
            out.push_back(entry);
        }
    }
    return out;
}

SettingsGuide& GetSettingsGuide() {
    static SettingsGuide instance;
    return instance;
}

} // namespace Symbiosis
