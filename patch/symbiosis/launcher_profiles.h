// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file launcher_profiles.h
 * @brief Launcher skins and the retro rendering presets attached to them.
 *
 * A launcher is two things at once:
 *
 *   1. A UI skin -- accent colours, card shape, grid density, the name of the
 *      library screen.
 *   2. A rendering preset -- how the emulated frame is degraded before it
 *      reaches the panel, so a modern game can be made to look like it is
 *      running on a Famicom, a Game Boy Advance or a PlayStation.
 *
 * The rendering half is deliberately *not* a decorative overlay. It drives a
 * real fragment shader that lowers the effective resolution, quantises each
 * colour channel to a fixed number of levels, dithers before quantising the
 * way period hardware did, and optionally adds the display artefacts of the
 * original screen. That is what actually made 8- and 16-bit output look the
 * way it did.
 */

#pragma once

#include <string>
#include <vector>

#include "common/common_types.h"

namespace Symbiosis {

/// Parameters handed to the retro presentation shader as specialization
/// constants. A value of zero disables the corresponding stage.
struct RetroPreset {
    /// Effective horizontal/vertical resolution. 0 keeps native resolution.
    float virtual_width{0.0f};
    float virtual_height{0.0f};
    /// Levels per colour channel. 2 => 1 bit, 4 => 2 bits, 8 => 3 bits, 32 =>
    /// 5 bits (the classic 16-bit console depth), 0 => untouched.
    float color_levels{0.0f};
    /// Ordered-dither amplitude, 0..1.
    float dither{0.0f};
    /// CRT scanline strength, 0..1.
    float scanline{0.0f};
    /// Handheld LCD grid strength, 0..1.
    float lcd_grid{0.0f};
    /// Saturation multiplier; below 1 washes out like an old LCD.
    float saturation{1.0f};
    /// Contrast lift; above 1 deepens like a CRT.
    float contrast{1.0f};

    [[nodiscard]] bool IsIdentity() const {
        return virtual_width <= 0.0f && virtual_height <= 0.0f && color_levels <= 0.0f &&
               scanline <= 0.0f && lcd_grid <= 0.0f && saturation == 1.0f && contrast == 1.0f;
    }

    /// Rough count of distinct colours the preset can produce, for the UI.
    [[nodiscard]] u64 ColorCount() const {
        if (color_levels < 2.0f) {
            return 0; // unrestricted
        }
        const auto levels = static_cast<u64>(color_levels);
        return levels * levels * levels;
    }
};

/// Visual identity of a launcher skin.
struct LauncherSkin {
    /// ARGB accent colour used for buttons and highlights.
    u32 accent_argb{0xFF4DD8C0};
    /// ARGB background for the library screen.
    u32 background_argb{0xFF0E1116};
    /// Corner radius in dp for game cards.
    u32 card_radius_dp{16};
    /// Preferred columns in the game grid.
    u32 grid_columns{2};
    /// Whether cards use a wide (16:9) or tall (box art) aspect.
    bool wide_cards{false};
};

enum class LauncherId : u32 {
    Eden = 0,   ///< Default Symbiosis look.
    Steam,      ///< Dark blue, wide capsules, dense grid.
    Switch,     ///< Light, red accent, square icons.
    Ps1,        ///< Low-res, dithered, washed out.
    Ps2,        ///< 32-bit-ish, slight softening.
    Ps3,        ///< Near-native with a cool tint.
    Ps4,        ///< Native, blue accent.
    Ps5,        ///< Native, white/blue, large cards.
    Dendy,      ///< NES: 256x240, tiny palette, heavy scanlines.
    Gba,        ///< 240x160, washed-out LCD, visible grid.
    Nds,        ///< 256x192, modest palette.
    Custom,     ///< Everything user-defined.
    COUNT,
};

struct LauncherProfile {
    LauncherId id{LauncherId::Eden};
    std::string key;           ///< Stable identifier used in configs.
    std::string display_name;
    std::string description;
    LauncherSkin skin;
    RetroPreset retro;
    /// Extra emulator settings this launcher implies, as key/value strings.
    std::vector<std::pair<std::string, std::string>> settings;
    /// Honest note about the performance impact.
    std::string performance_note;
};

/**
 * @brief Catalogue of launcher skins and their rendering presets.
 */
class LauncherCatalogue {
public:
    LauncherCatalogue();

    [[nodiscard]] const std::vector<LauncherProfile>& All() const {
        return profiles;
    }

    /// Looks up by stable key; returns the Eden default when not found.
    [[nodiscard]] const LauncherProfile& ByKey(std::string_view key) const;

    [[nodiscard]] const LauncherProfile& ById(LauncherId id) const;

    /// The launcher currently selected in the settings.
    [[nodiscard]] const LauncherProfile& Active() const;

    /// Retro parameters that should be fed to the presentation shader, taking
    /// the user's custom overrides into account.
    [[nodiscard]] RetroPreset ActiveRetroPreset() const;

private:
    void Build();
    std::vector<LauncherProfile> profiles;
};

LauncherCatalogue& GetLauncherCatalogue();

/**
 * @brief Monotonic counter that changes whenever any retro parameter changes.
 *
 * The presentation pipeline bakes the preset into specialization constants, so
 * it must be rebuilt when the user moves a slider. Comparing a cheap hash of
 * the live settings is far simpler than plumbing change notifications through
 * the renderer.
 */
u64 RetroGeneration();

} // namespace Symbiosis
