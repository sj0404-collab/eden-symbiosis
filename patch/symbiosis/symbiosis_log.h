// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file symbiosis_log.h
 * @brief In-memory ring buffer of everything the Symbiosis layer decides.
 *
 * The emulator already writes to logcat, but logcat is gone the moment the app
 * is killed, needs a PC to read comfortably, and is drowned out by the rest of
 * the engine. When a user reports "it crashed" or "it got slow", the useful
 * facts are which driver was picked, which capabilities were emulated, what
 * the memory budget was, and how hot the device got.
 *
 * This keeps the last N entries in memory, tagged by subsystem and severity,
 * so the diagnostics screen can show them and the user can copy the lot into a
 * bug report. It mirrors to the normal log as well, so nothing is lost.
 */

#pragma once

#include <array>
#include <mutex>
#include <string>
#include <vector>

#include "common/common_types.h"

namespace Symbiosis {

enum class LogArea : u32 {
    Driver = 0,   ///< Broker, providers, symbol borrowing.
    Memory,       ///< Budget, reclaim, donors.
    Thermal,      ///< Temperature and throttling.
    Profile,      ///< Tuning profiles and launcher presets.
    Render,       ///< Retro shader and presentation.
    Device,       ///< Hardware identification.
    General,
    COUNT,
};

enum class LogLevel : u32 {
    Debug = 0,
    Info,
    Warning,
    Error,
};

const char* ToString(LogArea area);
const char* ToString(LogLevel level);

struct LogEntry {
    /// Milliseconds since the layer started, so entries are orderable without
    /// dragging in a wall-clock dependency.
    u64 timestamp_ms{0};
    LogArea area{LogArea::General};
    LogLevel level{LogLevel::Info};
    std::string message;
};

/**
 * @brief Fixed-capacity ring buffer. Thread-safe.
 */
class SymbiosisLog {
public:
    static constexpr std::size_t kCapacity = 512;

    SymbiosisLog();

    void Write(LogArea area, LogLevel level, std::string message);

    /// Entries in chronological order, optionally filtered.
    [[nodiscard]] std::vector<LogEntry> Entries(LogArea area, bool all_areas,
                                                LogLevel min_level) const;

    /// Formatted dump ready to paste into a bug report.
    [[nodiscard]] std::string Dump(LogArea area, bool all_areas, LogLevel min_level) const;

    void Clear();

    [[nodiscard]] std::size_t Count() const;

    /// Per-area counts of warnings and errors, for the UI badges.
    [[nodiscard]] std::array<u32, static_cast<std::size_t>(LogArea::COUNT)> ProblemCounts() const;

private:
    mutable std::mutex mutex;
    std::array<LogEntry, kCapacity> buffer{};
    std::size_t head{0};  ///< Next slot to write.
    std::size_t size{0};  ///< Number of valid entries.
    u64 start_ms{0};
};

SymbiosisLog& GetLog();

/**
 * @brief Crash guard.
 *
 * Writes a marker file when a session starts using the layer and removes it on
 * a clean shutdown. If the marker is still present at the next start, the
 * previous run died while the layer was active, so the layer disables itself
 * for this run.
 *
 * Without this, a crash inside the layer is unrecoverable from the UI: the app
 * dies during startup every time and clearing app data does not help, because
 * the layer runs before the user can reach any setting.
 */
class CrashGuard {
public:
    /// Points the guard at a writable directory. Call once, early.
    static void SetMarkerDirectory(std::string directory);

    /// True when the previous session crashed with the layer enabled.
    /// The check is performed once and cached.
    [[nodiscard]] static bool PreviousRunCrashed();

    /// Marks a session as started.
    static void BeginSession();

    /// Marks a session as ended cleanly.
    static void EndSession();

    /// Clears the "previous run crashed" state so the user can re-enable the
    /// layer from the UI.
    static void Reset();
};

/// Convenience wrappers. These also forward to the engine log so behaviour is
/// unchanged for anyone reading logcat.
void LogDebug(LogArea area, std::string message);
void LogInfo(LogArea area, std::string message);
void LogWarning(LogArea area, std::string message);
void LogError(LogArea area, std::string message);

} // namespace Symbiosis
