// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file save_guard.h
 * @brief Automatic save backups and post-crash analysis.
 *
 * Two tools that exist because of things that actually went wrong:
 *
 *  **Save vault.** Save data lives inside the app's private storage. Clearing
 *  app data - the first thing anyone tries when an emulator misbehaves - wipes
 *  every save with it, and there is no undo. The vault keeps rotating copies
 *  outside that directory so a desperate "clear data" is survivable.
 *
 *  **Crash analyst.** When a game dies, the interesting facts are already in
 *  the layer log: which driver was in use, what the memory budget was, whether
 *  the device was throttling, which capabilities were emulated. Reading that
 *  by hand needs knowledge the user should not need. The analyst correlates
 *  the log with the crash and states a likely cause and a concrete next step.
 */

#pragma once

#include <string>
#include <vector>

#include "common/common_types.h"

namespace Symbiosis {

/// One stored backup.
struct SaveBackup {
    std::string path;
    std::string title_id;
    std::string label;      ///< Human-friendly name, when known.
    u64 size_bytes{0};
    u64 timestamp{0};       ///< Seconds since the epoch.
    u32 file_count{0};
};

struct VaultStatus {
    bool enabled{false};
    std::string vault_dir;
    u32 backup_count{0};
    u64 total_bytes{0};
    /// Newest backup timestamp, 0 when none.
    u64 latest{0};
};

/**
 * @brief Keeps copies of save data outside the app's private storage.
 */
class SaveVault {
public:
    /// Points the vault at a directory that survives "clear app data".
    static void Configure(std::string vault_dir, u32 keep_generations);

    [[nodiscard]] static VaultStatus Status();

    /**
     * @brief Copies the current save tree into a new generation.
     *
     * Oldest generations beyond the configured limit are removed, so the vault
     * cannot grow without bound on a device that is already short of space.
     *
     * @return Bytes written, 0 when nothing was copied.
     */
    static u64 Backup(const std::string& save_dir, const std::string& title_id,
                      const std::string& label);

    /// Every stored backup, newest first.
    [[nodiscard]] static std::vector<SaveBackup> List();

    /**
     * @brief Restores a backup over the live save directory.
     *
     * Takes a safety copy of the current state first, so an accidental restore
     * is itself reversible.
     */
    static bool Restore(const SaveBackup& backup, const std::string& save_dir,
                        std::string& error_out);

    static bool Remove(const SaveBackup& backup);

    [[nodiscard]] static std::string Describe();
};

/// A plausible explanation for a failure.
struct CrashFinding {
    /// 0-100. How confident the analysis is, stated so the user can weigh it.
    u32 confidence{0};
    std::string cause;
    std::string evidence;   ///< The log lines that led here.
    std::string action;     ///< What to change.
};

/**
 * @brief Turns the layer log into an explanation.
 */
class CrashAnalyst {
public:
    /// True when the previous session ended abnormally.
    [[nodiscard]] static bool PreviousSessionCrashed();

    /**
     * @brief Examines the layer log and returns findings, most likely first.
     *
     * Returns an empty list when nothing in the log suggests a cause, rather
     * than inventing one: a confident wrong answer wastes more of the user's
     * time than an honest "not sure".
     */
    [[nodiscard]] static std::vector<CrashFinding> Analyse();

    [[nodiscard]] static std::string Describe(const std::vector<CrashFinding>& findings);
};

} // namespace Symbiosis
