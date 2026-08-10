// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file settings_guide.h
 * @brief Reference documentation for every setting the emulator exposes.
 *
 * Eden presents roughly 110 settings on Android, including a section literally
 * titled "Hacks". Their effect is impossible to predict without reading the
 * renderer source, and several interact badly with each other. Hiding them
 * behind a toggle helped, but it did not answer the actual question: what does
 * this one do, and should I touch it?
 *
 * This is that answer, kept next to the code it describes so it cannot drift
 * out of date the way a wiki page does. Each entry states what the setting
 * changes, what it costs, and - the part usually missing - when it is a bad
 * idea.
 *
 * The text is deliberately blunt about uncertainty. Several of these knobs are
 * workarounds for specific driver bugs, and saying "this helps on some devices
 * and does nothing on others" is more useful than inventing a rule.
 */

#pragma once

#include <string>
#include <vector>

#include "common/common_types.h"

namespace Symbiosis {

/// How risky a setting is to change.
enum class GuideRisk : u32 {
    Safe = 0,     ///< Cosmetic or clearly bounded. Change freely.
    Tradeoff,     ///< Real cost somewhere else; understand it first.
    Risky,        ///< Can break rendering or stability in some titles.
    Debug,        ///< Diagnostic only. Not for normal play.
};

const char* ToString(GuideRisk risk);

/// Which screen a setting lives on.
enum class GuideSection : u32 {
    Modes = 0,
    Graphics,
    Advanced,
    Hacks,
    System,
    Audio,
    Overlay,
    Symbiosis,
    Utilities,
    COUNT,
};

const char* ToString(GuideSection section);

struct GuideEntry {
    /// Settings key, or empty for entries that describe a concept rather than
    /// a single setting.
    std::string key;
    std::string title;
    GuideSection section{GuideSection::Graphics};
    GuideRisk risk{GuideRisk::Safe};
    /// What it actually does, mechanically.
    std::string what;
    /// What it costs, and what it gains.
    std::string cost;
    /// Concrete advice, including when to leave it alone.
    std::string advice;
};

/**
 * @brief The settings reference.
 */
class SettingsGuide {
public:
    SettingsGuide();

    [[nodiscard]] const std::vector<GuideEntry>& All() const {
        return entries;
    }

    /// Entries for one section.
    [[nodiscard]] std::vector<GuideEntry> ForSection(GuideSection section) const;

    /// Entry for a specific settings key, or nullptr.
    [[nodiscard]] const GuideEntry* ForKey(std::string_view key) const;

    /// Free-text search across titles and descriptions.
    [[nodiscard]] std::vector<GuideEntry> Search(std::string_view query) const;

private:
    void Build();
    std::vector<GuideEntry> entries;
};

SettingsGuide& GetSettingsGuide();

} // namespace Symbiosis
