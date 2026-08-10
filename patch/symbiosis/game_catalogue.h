// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file game_catalogue.h
 * @brief Free homebrew catalogue and a Mali-oriented compatibility database.
 *
 * Two separate things live here, deliberately kept apart:
 *
 *  1. **Homebrew catalogue** — freely redistributable Switch homebrew, listed
 *     with the URL of its official release page. These are open-source or
 *     otherwise freely licensed programs whose authors publish them for anyone
 *     to download. They are useful for checking that the emulator works at all
 *     without needing a commercial game, and they need no keys.
 *
 *  2. **Compatibility database** — what to expect from a given commercial
 *     title on a Mali device, and which auto mode to use. This stores *advice
 *     only*: no game data, no links, nothing that would let the app obtain a
 *     game the user does not already own.
 *
 * Commercial games are never bundled or downloaded. Dumping a cartridge or an
 * eShop title you own is the user's responsibility and happens outside this
 * application.
 */

#pragma once

#include <string>
#include <vector>

#include "common/common_types.h"

namespace Symbiosis {

/// A freely redistributable homebrew program.
struct HomebrewEntry {
    std::string name;
    std::string author;
    std::string description;
    /// Official release page. The app opens this in a browser rather than
    /// downloading silently, so the user always sees what they are getting.
    std::string url;
    std::string license;
    /// True when the program is primarily a diagnostic or benchmark rather
    /// than a game.
    bool is_test_tool{false};
    /// True when it exercises 3D rendering, which makes it useful for checking
    /// the graphics path specifically.
    bool exercises_gpu{false};
};

/// How well a commercial title is expected to run.
enum class CompatRating : u32 {
    Unknown = 0,
    Perfect,   ///< Runs start to finish without noticeable issues.
    Playable,  ///< Completable, with minor glitches or occasional slowdown.
    Runs,      ///< Boots and plays but with significant problems.
    Intro,     ///< Reaches a menu or intro then breaks.
    Broken,    ///< Does not usefully run.
};

const char* ToString(CompatRating rating);

/// Advice for one commercial title on mobile hardware.
struct CompatEntry {
    std::string title;
    /// Title ID, when known. Lets the app match an installed game exactly.
    std::string title_id;
    CompatRating rating{CompatRating::Unknown};
    /// Index into the AutoMode enum that suits this title best.
    u32 recommended_mode{1}; ///< 1 = Balanced
    /// What to expect and what to do about it.
    std::string note;
    /// True when the title is known to need a large amount of memory, which
    /// matters a great deal on an 8 GB device.
    bool memory_heavy{false};
};

/**
 * @brief Catalogue of homebrew and compatibility advice.
 */
class GameCatalogue {
public:
    GameCatalogue();

    [[nodiscard]] const std::vector<HomebrewEntry>& Homebrew() const {
        return homebrew;
    }

    [[nodiscard]] const std::vector<CompatEntry>& Compatibility() const {
        return compatibility;
    }

    /// Looks a title up by name fragment or title id. Returns nullptr when the
    /// title is not in the database, which is the common case: the list is
    /// deliberately small and honest rather than padded with guesses.
    [[nodiscard]] const CompatEntry* Lookup(std::string_view title_or_id) const;

private:
    void Build();
    std::vector<HomebrewEntry> homebrew;
    std::vector<CompatEntry> compatibility;
};

GameCatalogue& GetGameCatalogue();

} // namespace Symbiosis
