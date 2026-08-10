// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "common/logging.h"
#include "common/settings.h"
#include "common/symbiosis/launcher_profiles.h"

namespace Symbiosis {

LauncherCatalogue::LauncherCatalogue() {
    Build();
}

void LauncherCatalogue::Build() {
    profiles.clear();
    profiles.reserve(static_cast<std::size_t>(LauncherId::COUNT));

    // ---------------------------------------------------------------------
    // Modern skins: no image degradation, only a different look.
    // ---------------------------------------------------------------------
    profiles.push_back(LauncherProfile{
        .id = LauncherId::Eden,
        .key = "eden",
        .display_name = "Eden",
        .description = "The default Symbiosis look. No image processing.",
        .skin = {.accent_argb = 0xFF4DD8C0,
                 .background_argb = 0xFF0E1116,
                 .card_radius_dp = 16,
                 .grid_columns = 2,
                 .wide_cards = false},
        .retro = {},
        .settings = {},
        .performance_note = "No cost.",
    });

    profiles.push_back(LauncherProfile{
        .id = LauncherId::Steam,
        .key = "steam",
        .display_name = "Steam",
        .description = "Dark blue library with wide capsule art and a dense grid.",
        .skin = {.accent_argb = 0xFF66C0F4,
                 .background_argb = 0xFF1B2838,
                 .card_radius_dp = 6,
                 .grid_columns = 3,
                 .wide_cards = true},
        .retro = {},
        .settings = {},
        .performance_note = "No cost.",
    });

    profiles.push_back(LauncherProfile{
        .id = LauncherId::Switch,
        .key = "switch",
        .display_name = "Switch",
        .description = "Light theme with square icons and the familiar red accent.",
        .skin = {.accent_argb = 0xFFE60012,
                 .background_argb = 0xFFEBEBEB,
                 .card_radius_dp = 8,
                 .grid_columns = 4,
                 .wide_cards = false},
        .retro = {},
        .settings = {},
        .performance_note = "No cost.",
    });

    // ---------------------------------------------------------------------
    // PlayStation line. Each generation gets the colour depth and resolution
    // that hardware actually output.
    // ---------------------------------------------------------------------

    // PS1: 320x240 typical, 15-bit colour (32 levels per channel), no
    // perspective correction and heavy dithering to hide the banding. The
    // "soft and muddy but characterful" look the user asked for.
    profiles.push_back(LauncherProfile{
        .id = LauncherId::Ps1,
        .key = "ps1",
        .display_name = "PlayStation",
        .description =
            "320x240 with 15-bit colour and heavy dithering. Soft, muddy, unmistakably PS1.",
        .skin = {.accent_argb = 0xFF9A9A9A,
                 .background_argb = 0xFF15161A,
                 .card_radius_dp = 2,
                 .grid_columns = 3,
                 .wide_cards = false},
        .retro = {.virtual_width = 320.0f,
                  .virtual_height = 240.0f,
                  .color_levels = 32.0f, // 5 bits per channel
                  .dither = 0.85f,
                  .scanline = 0.25f,
                  .lcd_grid = 0.0f,
                  .saturation = 0.92f,
                  .contrast = 1.05f},
        .settings = {{"scaling_filter", "0"}, {"anti_aliasing", "0"}},
        .performance_note = "Faster than native: the frame is resolved at a fraction of the pixels.",
    });

    profiles.push_back(LauncherProfile{
        .id = LauncherId::Ps2,
        .key = "ps2",
        .display_name = "PlayStation 2",
        .description = "640x448 with 24-bit colour and light dithering.",
        .skin = {.accent_argb = 0xFF2A4FD8,
                 .background_argb = 0xFF0A0C18,
                 .card_radius_dp = 4,
                 .grid_columns = 3,
                 .wide_cards = false},
        .retro = {.virtual_width = 640.0f,
                  .virtual_height = 448.0f,
                  .color_levels = 64.0f,
                  .dither = 0.3f,
                  .scanline = 0.15f,
                  .lcd_grid = 0.0f,
                  .saturation = 1.0f,
                  .contrast = 1.02f},
        .settings = {{"scaling_filter", "1"}},
        .performance_note = "Faster than native on most titles.",
    });

    profiles.push_back(LauncherProfile{
        .id = LauncherId::Ps3,
        .key = "ps3",
        .display_name = "PlayStation 3",
        .description = "720p-era look with a cool tint. Almost no degradation.",
        .skin = {.accent_argb = 0xFF3C7BD4,
                 .background_argb = 0xFF07080C,
                 .card_radius_dp = 6,
                 .grid_columns = 3,
                 .wide_cards = true},
        .retro = {.virtual_width = 1280.0f,
                  .virtual_height = 720.0f,
                  .color_levels = 0.0f,
                  .dither = 0.0f,
                  .scanline = 0.0f,
                  .lcd_grid = 0.0f,
                  .saturation = 0.98f,
                  .contrast = 1.03f},
        .settings = {},
        .performance_note = "Roughly free.",
    });

    profiles.push_back(LauncherProfile{
        .id = LauncherId::Ps4,
        .key = "ps4",
        .display_name = "PlayStation 4",
        .description = "Native output, blue accent, no image processing.",
        .skin = {.accent_argb = 0xFF0070D1,
                 .background_argb = 0xFF11151C,
                 .card_radius_dp = 4,
                 .grid_columns = 3,
                 .wide_cards = true},
        .retro = {},
        .settings = {},
        .performance_note = "No cost.",
    });

    profiles.push_back(LauncherProfile{
        .id = LauncherId::Ps5,
        .key = "ps5",
        .display_name = "PlayStation 5",
        .description = "Native output with large cards and a bright, clean layout.",
        .skin = {.accent_argb = 0xFF2196F3,
                 .background_argb = 0xFF1B1F26,
                 .card_radius_dp = 12,
                 .grid_columns = 2,
                 .wide_cards = true},
        .retro = {},
        .settings = {},
        .performance_note = "No cost.",
    });

    // ---------------------------------------------------------------------
    // 8- and 16-bit machines. These are the aggressive presets: the whole
    // point is to make a modern game look like it is running on the console.
    // ---------------------------------------------------------------------

    // NES/Famicom: 256x240, and a palette so small that quantising to 4 levels
    // per channel (64 combinations) is already generous versus the real 54
    // colours.
    profiles.push_back(LauncherProfile{
        .id = LauncherId::Dendy,
        .key = "dendy",
        .display_name = "Dendy / NES",
        .description = "256x240 with a 64-colour palette and strong scanlines. 8-bit look.",
        .skin = {.accent_argb = 0xFFE03030,
                 .background_argb = 0xFF101010,
                 .card_radius_dp = 0,
                 .grid_columns = 3,
                 .wide_cards = false},
        .retro = {.virtual_width = 256.0f,
                  .virtual_height = 240.0f,
                  .color_levels = 4.0f, // 64 total colours
                  .dither = 0.6f,
                  .scanline = 0.55f,
                  .lcd_grid = 0.0f,
                  .saturation = 1.15f,
                  .contrast = 1.12f},
        .settings = {{"scaling_filter", "0"}, {"anti_aliasing", "0"}, {"max_anisotropy", "0"}},
        .performance_note = "Much faster than native: ~8% of the pixels of a 1080p frame.",
    });

    // GBA: 240x160, 15-bit colour but a famously dim, washed-out screen.
    profiles.push_back(LauncherProfile{
        .id = LauncherId::Gba,
        .key = "gba",
        .display_name = "Game Boy Advance",
        .description = "240x160 with a washed-out LCD and a visible pixel grid.",
        .skin = {.accent_argb = 0xFF5A4FCF,
                 .background_argb = 0xFF1A1A22,
                 .card_radius_dp = 10,
                 .grid_columns = 3,
                 .wide_cards = false},
        .retro = {.virtual_width = 240.0f,
                  .virtual_height = 160.0f,
                  .color_levels = 32.0f,
                  .dither = 0.45f,
                  .scanline = 0.0f,
                  .lcd_grid = 0.7f,
                  .saturation = 0.78f, // the GBA screen was notoriously dull
                  .contrast = 0.92f},
        .settings = {{"scaling_filter", "0"}, {"anti_aliasing", "0"}},
        .performance_note = "Dramatically faster: ~2% of the pixels of a 1080p frame.",
    });

    profiles.push_back(LauncherProfile{
        .id = LauncherId::Nds,
        .key = "nds",
        .display_name = "Nintendo DS",
        .description = "256x192 with an 18-bit palette and a faint LCD grid.",
        .skin = {.accent_argb = 0xFFB0B0B8,
                 .background_argb = 0xFF14161A,
                 .card_radius_dp = 8,
                 .grid_columns = 3,
                 .wide_cards = false},
        .retro = {.virtual_width = 256.0f,
                  .virtual_height = 192.0f,
                  .color_levels = 8.0f, // 512 colours
                  .dither = 0.5f,
                  .scanline = 0.0f,
                  .lcd_grid = 0.45f,
                  .saturation = 0.95f,
                  .contrast = 1.0f},
        .settings = {{"scaling_filter", "0"}, {"anti_aliasing", "0"}},
        .performance_note = "Dramatically faster than native.",
    });

    // ---------------------------------------------------------------------
    // Fully user-defined. Values come from settings, not from this table.
    // ---------------------------------------------------------------------
    profiles.push_back(LauncherProfile{
        .id = LauncherId::Custom,
        .key = "custom",
        .display_name = "Custom",
        .description = "Every parameter is yours: resolution, colour depth, dithering, "
                       "scanlines, grid, saturation and contrast.",
        .skin = {.accent_argb = 0xFF7C6CF0,
                 .background_argb = 0xFF0E1116,
                 .card_radius_dp = 16,
                 .grid_columns = 2,
                 .wide_cards = false},
        .retro = {},
        .settings = {},
        .performance_note = "Depends entirely on your settings.",
    });

    LOG_INFO(Common, "[Symbiosis] launcher catalogue: {} skins", profiles.size());
}

const LauncherProfile& LauncherCatalogue::ByKey(std::string_view key) const {
    for (const auto& profile : profiles) {
        if (profile.key == key) {
            return profile;
        }
    }
    return profiles.front(); // Eden
}

const LauncherProfile& LauncherCatalogue::ById(LauncherId id) const {
    for (const auto& profile : profiles) {
        if (profile.id == id) {
            return profile;
        }
    }
    return profiles.front();
}

const LauncherProfile& LauncherCatalogue::Active() const {
    // The setting is unsigned and range-clamped, so only the upper bound needs
    // checking here.
    const auto index = Settings::values.symbiosis_launcher.GetValue();
    if (static_cast<std::size_t>(index) < profiles.size()) {
        return profiles[static_cast<std::size_t>(index)];
    }
    return profiles.front();
}

RetroPreset LauncherCatalogue::ActiveRetroPreset() const {
    const auto& active = Active();

    // The Custom launcher reads every value from the user's settings so the
    // sliders in the UI map one-to-one onto the shader.
    if (active.id != LauncherId::Custom) {
        return active.retro;
    }

    RetroPreset preset{};
    preset.virtual_width = static_cast<float>(Settings::values.retro_width.GetValue());
    preset.virtual_height = static_cast<float>(Settings::values.retro_height.GetValue());
    preset.color_levels = static_cast<float>(Settings::values.retro_color_levels.GetValue());
    preset.dither = static_cast<float>(Settings::values.retro_dither.GetValue()) / 100.0f;
    preset.scanline = static_cast<float>(Settings::values.retro_scanline.GetValue()) / 100.0f;
    preset.lcd_grid = static_cast<float>(Settings::values.retro_lcd_grid.GetValue()) / 100.0f;
    preset.saturation = static_cast<float>(Settings::values.retro_saturation.GetValue()) / 100.0f;
    preset.contrast = static_cast<float>(Settings::values.retro_contrast.GetValue()) / 100.0f;
    return preset;
}

u64 RetroGeneration() {
    // Hash the values that feed the shader. Any change flips the result and
    // the renderer rebuilds the pipeline on the next frame.
    const auto preset = GetLauncherCatalogue().ActiveRetroPreset();
    u64 hash = 1469598103934665603ULL; // FNV-1a offset basis
    const auto mix = [&hash](float value) {
        // Quantise to 1/1000 so floating-point noise cannot cause churn.
        const auto quantised = static_cast<u64>(static_cast<double>(value) * 1000.0 + 0.5);
        for (int byte = 0; byte < 8; ++byte) {
            hash ^= (quantised >> (byte * 8)) & 0xFF;
            hash *= 1099511628211ULL;
        }
    };
    mix(preset.virtual_width);
    mix(preset.virtual_height);
    mix(preset.color_levels);
    mix(preset.dither);
    mix(preset.scanline);
    mix(preset.lcd_grid);
    mix(preset.saturation);
    mix(preset.contrast);
    return hash;
}

LauncherCatalogue& GetLauncherCatalogue() {
    static LauncherCatalogue instance;
    return instance;
}

} // namespace Symbiosis
