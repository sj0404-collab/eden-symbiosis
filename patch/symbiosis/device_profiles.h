// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file device_profiles.h
 * @brief Per-device / per-driver tuning profiles.
 *
 * The same emulator settings behave very differently depending on the GPU and
 * the driver behind it. A Mali device with only the stock vendor driver cannot
 * use the tricks that make Adreno+Turnip fast, and applying them anyway costs
 * frames instead of gaining them.
 *
 * This module ships a table of curated profiles keyed by GPU family and driver
 * origin, each expressed as a small set of setting overrides plus a plain
 * language rationale. The user picks an intent (max FPS / balanced / quality)
 * and the engine resolves it against the detected hardware.
 *
 * Everything is advisory: a profile only proposes values. The UI applies them
 * explicitly, and every individual knob stays user-editable afterwards.
 */

#pragma once

#include <string>
#include <vector>

#include "common/common_types.h"
#include "common/symbiosis/symbiosis_types.h"

namespace Symbiosis {

/// What the user is optimising for.
enum class TuningIntent : u32 {
    MaxFps = 0,   ///< Lowest latency / highest frame rate, accepts artefacts.
    Balanced,     ///< Sensible default for the detected hardware.
    Quality,      ///< Best image, accepts a lower frame rate.
    Battery,      ///< Cap work to reduce heat and drain.
};

const char* ToString(TuningIntent intent);

/// One setting override proposed by a profile.
struct Tweak {
    std::string key;     ///< Settings key, e.g. "resolution_setup".
    std::string value;   ///< Value encoded as a string.
    std::string reason;  ///< Why this helps on this hardware.
};

/// A complete recommendation for one (hardware, intent) pair.
struct Profile {
    std::string id;
    std::string display_name;
    std::string summary;         ///< One-line description for the UI.
    GpuFamily family{GpuFamily::Unknown};
    DriverOrigin origin{DriverOrigin::System};
    TuningIntent intent{TuningIntent::Balanced};
    std::vector<Tweak> tweaks;
    /// Rough expected effect, e.g. "+15-25% FPS". Deliberately a range: the
    /// real number depends on the title.
    std::string expected_effect;
    /// True when the profile is safe on a device that cannot install custom
    /// drivers (only the system blob available).
    bool works_on_stock_driver{true};
};

/**
 * @brief Catalogue of tuning profiles.
 */
class ProfileEngine {
public:
    ProfileEngine();

    /// All profiles that apply to the given hardware, best match first.
    [[nodiscard]] std::vector<Profile> ProfilesFor(GpuFamily family, DriverOrigin origin) const;

    /// Single best profile for a hardware + intent combination.
    /// Falls back to a generic profile when the hardware is unknown.
    [[nodiscard]] Profile Resolve(GpuFamily family, DriverOrigin origin,
                                  TuningIntent intent) const;

    [[nodiscard]] const std::vector<Profile>& All() const {
        return profiles;
    }

    /// Human-readable dump for logs and the diagnostics screen.
    [[nodiscard]] std::string Describe(const Profile& profile) const;

private:
    void BuildCatalogue();
    std::vector<Profile> profiles;
};

ProfileEngine& GetProfileEngine();

} // namespace Symbiosis
