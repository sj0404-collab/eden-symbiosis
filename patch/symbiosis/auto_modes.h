// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file auto_modes.h
 * @brief One-tap modes that configure the whole emulator coherently.
 *
 * Eden exposes around 110 individual settings on Android. Most of them are
 * accuracy trade-offs, driver workarounds and outright hacks whose effect is
 * impossible to predict without reading the renderer source. Presenting all of
 * them to a player is not "power user friendly", it is a maze: the combinations
 * that actually work are a tiny subset, and several pairs actively fight each
 * other.
 *
 * A mode is a curated, internally consistent snapshot of every setting that
 * matters, chosen for a single goal. Picking one replaces guesswork with a
 * known-good configuration, and the advanced list stays available for anyone
 * who wants it.
 *
 * Modes are resolved against the detected hardware, so "Performance" on a Mali
 * device with only the system driver is not the same set of values as
 * "Performance" on Adreno with Turnip.
 */

#pragma once

#include <string>
#include <vector>

#include "common/common_types.h"
#include "common/symbiosis/symbiosis_types.h"

namespace Symbiosis {

enum class AutoMode : u32 {
    Quality = 0,   ///< Best image the device can sustain.
    Balanced,      ///< Sensible default; what most people should use.
    Performance,   ///< Highest frame rate, accepts visual compromises.
    Stability,     ///< Fewest crashes and stutters, accuracy over speed.
    Compatibility, ///< For titles that refuse to boot or render correctly.
    Turbo,         ///< Maximum load up to a temperature ceiling.
    Custom,        ///< User-managed; the layer applies nothing.
    COUNT,
};

const char* ToString(AutoMode mode);

/// A single setting change with the reason it belongs in this mode.
struct ModeTweak {
    std::string key;
    std::string value;
    std::string reason;
};

struct ModeDefinition {
    AutoMode mode{AutoMode::Balanced};
    std::string key;           ///< Stable identifier for configs.
    std::string display_name;
    std::string summary;       ///< One line for the UI.
    std::string detail;        ///< What the mode prioritises and gives up.
    std::vector<ModeTweak> tweaks;
    /// Temperature ceiling in Celsius; 0 means "use the global default".
    u32 temp_ceiling{0};
    /// Whether the mode is safe when only the stock system driver exists.
    bool works_on_stock_driver{true};
};

/**
 * @brief Builds and applies the auto modes.
 */
class AutoModeEngine {
public:
    AutoModeEngine();

    /// Definition resolved against the given hardware.
    [[nodiscard]] ModeDefinition Resolve(AutoMode mode, GpuFamily family,
                                         DriverOrigin origin) const;

    /// All modes, for listing in the UI.
    [[nodiscard]] std::vector<ModeDefinition> AllFor(GpuFamily family,
                                                     DriverOrigin origin) const;

    /**
     * @brief Applies a mode to the live settings.
     *
     * Custom applies nothing by design: it is the escape hatch for people who
     * want to manage settings themselves.
     *
     * @return Number of settings actually changed.
     */
    u32 Apply(AutoMode mode, GpuFamily family, DriverOrigin origin);

    /// The mode currently recorded in the settings.
    [[nodiscard]] static AutoMode Current();

    /// Applies the recorded mode. Called once when a game starts so the
    /// configuration is always coherent, even after a settings file was edited
    /// by hand or carried over from another build.
    void ApplyCurrentOnStartup(GpuFamily family, DriverOrigin origin);

    /// Human-readable dump for the diagnostics screen.
    [[nodiscard]] std::string Describe(const ModeDefinition& definition) const;

private:
    [[nodiscard]] ModeDefinition BuildQuality(GpuFamily family, DriverOrigin origin) const;
    [[nodiscard]] ModeDefinition BuildBalanced(GpuFamily family, DriverOrigin origin) const;
    [[nodiscard]] ModeDefinition BuildPerformance(GpuFamily family, DriverOrigin origin) const;
    [[nodiscard]] ModeDefinition BuildStability(GpuFamily family, DriverOrigin origin) const;
    [[nodiscard]] ModeDefinition BuildCompatibility(GpuFamily family, DriverOrigin origin) const;
    [[nodiscard]] ModeDefinition BuildTurbo(GpuFamily family, DriverOrigin origin) const;
};

AutoModeEngine& GetAutoModeEngine();

} // namespace Symbiosis
