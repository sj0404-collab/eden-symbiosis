// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <cctype>

#include "common/logging.h"
#include "common/symbiosis/game_catalogue.h"
#include "common/symbiosis/symbiosis_log.h"

namespace Symbiosis {

namespace {

std::string Lower(std::string_view text) {
    std::string out{text};
    std::transform(out.begin(), out.end(), out.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return out;
}

} // Anonymous namespace

const char* ToString(CompatRating rating) {
    switch (rating) {
    case CompatRating::Unknown:
        return "Unknown";
    case CompatRating::Perfect:
        return "Perfect";
    case CompatRating::Playable:
        return "Playable";
    case CompatRating::Runs:
        return "Runs with issues";
    case CompatRating::Intro:
        return "Intro only";
    case CompatRating::Broken:
        return "Broken";
    }
    return "Unknown";
}

GameCatalogue::GameCatalogue() {
    Build();
}

void GameCatalogue::Build() {
    // -----------------------------------------------------------------------
    // Homebrew. Every entry here is freely redistributable and published by its
    // author for public download. The app links to the official release page
    // rather than mirroring the files: the user sees the source, the licence
    // and the checksums, and the authors keep their download counts.
    //
    // None of these need encryption keys, which makes them the fastest way to
    // find out whether the emulator works on a device.
    // -----------------------------------------------------------------------
    homebrew = {
        {
            .name = "Switch Identity Test (hbmenu)",
            .author = "switchbrew",
            .description = "The reference homebrew menu. Boots quickly and exercises the "
                           "filesystem and input paths without touching the GPU hard.",
            .url = "https://github.com/switchbrew/nx-hbmenu/releases",
            .license = "GPL-2.0 / ISC",
            .is_test_tool = true,
            .exercises_gpu = false,
        },
        {
            .name = "libnx graphics examples",
            .author = "switchbrew",
            .description = "Official SDK samples covering 2D framebuffer output, OpenGL and "
                           "Vulkan. The single best check of whether the graphics path works.",
            .url = "https://github.com/switchbrew/switch-examples",
            .license = "ISC",
            .is_test_tool = true,
            .exercises_gpu = true,
        },
        {
            .name = "SDL2 test suite for Switch",
            .author = "devkitPro",
            .description = "Rendering, audio and input tests built on SDL2. Useful for "
                           "isolating whether a fault is graphics or audio.",
            .url = "https://github.com/devkitPro/SDL/releases",
            .license = "Zlib",
            .is_test_tool = true,
            .exercises_gpu = true,
        },
        {
            .name = "Super Mario 64 port (sm64ex-nx)",
            .author = "community port",
            .description = "A native Switch port of the decompiled Super Mario 64. Heavy "
                           "enough to be a real graphics benchmark. Requires you to supply "
                           "your own ROM to build the assets.",
            .url = "https://github.com/bctengfei/sm64ex-nx/releases",
            .license = "Source is open; assets are not distributed",
            .is_test_tool = false,
            .exercises_gpu = true,
        },
        {
            .name = "OpenTyrian for Switch",
            .author = "community port",
            .description = "Complete freeware shoot-'em-up. Runs on very modest hardware, so "
                           "it is a good first game to prove the emulator end to end.",
            .url = "https://github.com/carstene1ns/opentyrian-nx/releases",
            .license = "GPL-2.0, freeware data",
            .is_test_tool = false,
            .exercises_gpu = true,
        },
        {
            .name = "Cave Story (NXEngine)",
            .author = "community port",
            .description = "Freeware classic. Light on the GPU, so it isolates CPU and audio "
                           "problems from graphics ones.",
            .url = "https://github.com/vitasdk/nxengine-evo/releases",
            .license = "GPL-3.0, freeware data",
            .is_test_tool = false,
            .exercises_gpu = false,
        },
        {
            .name = "DOOM (Chocolate Doom NX)",
            .author = "community port",
            .description = "Runs with the freely distributable shareware WAD. A reliable "
                           "smoke test that needs almost no GPU.",
            .url = "https://github.com/fgsfdsfgs/crispy-doom-nx/releases",
            .license = "GPL-2.0, shareware WAD",
            .is_test_tool = false,
            .exercises_gpu = false,
        },
        {
            .name = "Checkpoint",
            .author = "FlagBrew",
            .description = "Save manager. Not a game, but it exercises the save data paths "
                           "the emulator has to get right.",
            .url = "https://github.com/FlagBrew/Checkpoint/releases",
            .license = "GPL-3.0",
            .is_test_tool = true,
            .exercises_gpu = false,
        },
    };

    // -----------------------------------------------------------------------
    // Compatibility advice. Deliberately short: every entry is something with a
    // well-documented, reproducible characteristic on mobile hardware. Padding
    // this list with guesses would make it worse than useless, because people
    // would trust it.
    //
    // No links, no data, no title keys. Advice only.
    // -----------------------------------------------------------------------
    compatibility = {
        {
            .title = "The Legend of Zelda: Breath of the Wild",
            .title_id = "01007EF00011E000",
            .rating = CompatRating::Runs,
            .recommended_mode = 2, // Performance
            .note = "Very demanding. Expect well under 30 FPS on Mali even at reduced "
                    "resolution. Memory pressure is the main limit on an 8 GB device.",
            .memory_heavy = true,
        },
        {
            .title = "The Legend of Zelda: Tears of the Kingdom",
            .title_id = "0100F2C0115B6000",
            .rating = CompatRating::Runs,
            .recommended_mode = 2,
            .note = "Heavier than Breath of the Wild in every respect. Treat as a stress "
                    "test rather than something to play through.",
            .memory_heavy = true,
        },
        {
            .title = "Super Mario Odyssey",
            .title_id = "0100000000010000",
            .rating = CompatRating::Playable,
            .recommended_mode = 2,
            .note = "Generally good, but individual kingdoms vary a lot. Performance mode "
                    "is usually enough.",
            .memory_heavy = false,
        },
        {
            .title = "Animal Crossing: New Horizons",
            .title_id = "01006F8002326000",
            .rating = CompatRating::Playable,
            .recommended_mode = 1, // Balanced
            .note = "Light enough for Balanced on most Valhall parts.",
            .memory_heavy = false,
        },
        {
            .title = "Super Smash Bros. Ultimate",
            .title_id = "01006A800016E000",
            .rating = CompatRating::Runs,
            .recommended_mode = 2,
            .note = "Loads a great deal of content up front; a frequent cause of "
                    "out-of-memory kills on 8 GB devices.",
            .memory_heavy = true,
        },
        {
            .title = "Pokemon Sword / Shield",
            .title_id = "0100ABF008968000",
            .rating = CompatRating::Playable,
            .recommended_mode = 1,
            .note = "Runs reasonably. Stability mode helps if it closes unexpectedly.",
            .memory_heavy = false,
        },
        {
            .title = "Mario Kart 8 Deluxe",
            .title_id = "0100152000022000",
            .rating = CompatRating::Playable,
            .recommended_mode = 1,
            .note = "One of the better-behaved titles on mobile hardware.",
            .memory_heavy = false,
        },
        {
            .title = "Metroid Dread",
            .title_id = "010093801237C000",
            .rating = CompatRating::Playable,
            .recommended_mode = 1,
            .note = "Mostly smooth; occasional shader compilation stutter on first play.",
            .memory_heavy = false,
        },
    };

    LOG_INFO(Common, "[Symbiosis] catalogue: {} homebrew, {} compatibility entries",
             homebrew.size(), compatibility.size());
}

const CompatEntry* GameCatalogue::Lookup(std::string_view title_or_id) const {
    if (title_or_id.empty()) {
        return nullptr;
    }
    const std::string needle = Lower(title_or_id);

    // Exact title id first: unambiguous when the caller has one.
    for (const auto& entry : compatibility) {
        if (!entry.title_id.empty() && Lower(entry.title_id) == needle) {
            return &entry;
        }
    }
    // Then a substring match on the title.
    for (const auto& entry : compatibility) {
        const std::string title = Lower(entry.title);
        if (title.find(needle) != std::string::npos ||
            needle.find(title) != std::string::npos) {
            return &entry;
        }
    }
    return nullptr;
}

GameCatalogue& GetGameCatalogue() {
    static GameCatalogue instance;
    return instance;
}

} // namespace Symbiosis
