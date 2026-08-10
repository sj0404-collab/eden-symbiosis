// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <chrono>
#include <filesystem>
#include <fstream>
#include <mutex>

#include "common/logging.h"
#include "common/symbiosis/symbiosis_log.h"

namespace Symbiosis {

namespace {

u64 NowMs() {
    using namespace std::chrono;
    return static_cast<u64>(
        duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count());
}

} // Anonymous namespace

const char* ToString(LogArea area) {
    switch (area) {
    case LogArea::Driver:
        return "Driver";
    case LogArea::Memory:
        return "Memory";
    case LogArea::Thermal:
        return "Thermal";
    case LogArea::Profile:
        return "Profile";
    case LogArea::Render:
        return "Render";
    case LogArea::Device:
        return "Device";
    case LogArea::General:
        return "General";
    case LogArea::COUNT:
        break;
    }
    return "?";
}

const char* ToString(LogLevel level) {
    switch (level) {
    case LogLevel::Debug:
        return "DBG";
    case LogLevel::Info:
        return "INF";
    case LogLevel::Warning:
        return "WRN";
    case LogLevel::Error:
        return "ERR";
    }
    return "?";
}

SymbiosisLog::SymbiosisLog() : start_ms{NowMs()} {}

void SymbiosisLog::Write(LogArea area, LogLevel level, std::string message) {
    std::scoped_lock lock{mutex};
    buffer[head] = LogEntry{
        .timestamp_ms = NowMs() - start_ms,
        .area = area,
        .level = level,
        .message = std::move(message),
    };
    head = (head + 1) % kCapacity;
    if (size < kCapacity) {
        size++;
    }
}

std::vector<LogEntry> SymbiosisLog::Entries(LogArea area, bool all_areas,
                                            LogLevel min_level) const {
    std::scoped_lock lock{mutex};
    std::vector<LogEntry> out;
    out.reserve(size);

    // Walk from the oldest surviving entry so the result is chronological even
    // after the buffer has wrapped.
    const std::size_t first = (size == kCapacity) ? head : 0;
    for (std::size_t i = 0; i < size; ++i) {
        const auto& entry = buffer[(first + i) % kCapacity];
        if (!all_areas && entry.area != area) {
            continue;
        }
        if (static_cast<u32>(entry.level) < static_cast<u32>(min_level)) {
            continue;
        }
        out.push_back(entry);
    }
    return out;
}

std::string SymbiosisLog::Dump(LogArea area, bool all_areas, LogLevel min_level) const {
    const auto entries = Entries(area, all_areas, min_level);
    if (entries.empty()) {
        return "(no entries)\n";
    }

    std::string out;
    out.reserve(entries.size() * 80);
    for (const auto& entry : entries) {
        // [   12.345] WRN Driver: message
        const u64 seconds = entry.timestamp_ms / 1000;
        const u64 millis = entry.timestamp_ms % 1000;
        out += '[';
        const std::string secs = std::to_string(seconds);
        for (std::size_t pad = secs.size(); pad < 5; ++pad) {
            out += ' ';
        }
        out += secs;
        out += '.';
        if (millis < 100) {
            out += '0';
        }
        if (millis < 10) {
            out += '0';
        }
        out += std::to_string(millis);
        out += "] ";
        out += ToString(entry.level);
        out += ' ';
        out += ToString(entry.area);
        out += ": ";
        out += entry.message;
        out += '\n';
    }
    return out;
}

void SymbiosisLog::Clear() {
    std::scoped_lock lock{mutex};
    head = 0;
    size = 0;
    start_ms = NowMs();
}

std::size_t SymbiosisLog::Count() const {
    std::scoped_lock lock{mutex};
    return size;
}

std::array<u32, static_cast<std::size_t>(LogArea::COUNT)> SymbiosisLog::ProblemCounts() const {
    std::scoped_lock lock{mutex};
    std::array<u32, static_cast<std::size_t>(LogArea::COUNT)> counts{};
    const std::size_t first = (size == kCapacity) ? head : 0;
    for (std::size_t i = 0; i < size; ++i) {
        const auto& entry = buffer[(first + i) % kCapacity];
        if (entry.level == LogLevel::Warning || entry.level == LogLevel::Error) {
            const auto index = static_cast<std::size_t>(entry.area);
            if (index < counts.size()) {
                counts[index]++;
            }
        }
    }
    return counts;
}

namespace {

std::mutex g_guard_mutex;
std::string g_marker_dir;
bool g_checked = false;
bool g_previous_crashed = false;

std::string MarkerPath() {
    if (g_marker_dir.empty()) {
        return {};
    }
    return g_marker_dir + "/symbiosis_session.lock";
}

} // Anonymous namespace

void CrashGuard::SetMarkerDirectory(std::string directory) {
    std::scoped_lock lock{g_guard_mutex};
    g_marker_dir = std::move(directory);
    g_checked = false;
}

bool CrashGuard::PreviousRunCrashed() {
    std::scoped_lock lock{g_guard_mutex};
    if (g_checked) {
        return g_previous_crashed;
    }
    const auto path = MarkerPath();
    if (path.empty()) {
        return false;
    }
    std::error_code ec;
    g_previous_crashed = std::filesystem::exists(path, ec) && !ec;
    g_checked = true;
    if (g_previous_crashed) {
        LOG_WARNING(Common,
                    "[Symbiosis] previous session did not shut down cleanly; "
                    "disabling the layer for this run");
    }
    return g_previous_crashed;
}

void CrashGuard::BeginSession() {
    std::scoped_lock lock{g_guard_mutex};
    const auto path = MarkerPath();
    if (path.empty()) {
        return;
    }
    std::ofstream marker{path, std::ios::trunc};
    if (marker.is_open()) {
        marker << "1";
    }
}

void CrashGuard::EndSession() {
    std::scoped_lock lock{g_guard_mutex};
    const auto path = MarkerPath();
    if (path.empty()) {
        return;
    }
    std::error_code ec;
    std::filesystem::remove(path, ec);
}

void CrashGuard::Reset() {
    std::scoped_lock lock{g_guard_mutex};
    const auto path = MarkerPath();
    if (!path.empty()) {
        std::error_code ec;
        std::filesystem::remove(path, ec);
    }
    g_checked = false;
    g_previous_crashed = false;
}

SymbiosisLog& GetLog() {
    static SymbiosisLog instance;
    return instance;
}

void LogDebug(LogArea area, std::string message) {
    LOG_DEBUG(Common, "[Symbiosis/{}] {}", ToString(area), message);
    GetLog().Write(area, LogLevel::Debug, std::move(message));
}

void LogInfo(LogArea area, std::string message) {
    LOG_INFO(Common, "[Symbiosis/{}] {}", ToString(area), message);
    GetLog().Write(area, LogLevel::Info, std::move(message));
}

void LogWarning(LogArea area, std::string message) {
    LOG_WARNING(Common, "[Symbiosis/{}] {}", ToString(area), message);
    GetLog().Write(area, LogLevel::Warning, std::move(message));
}

void LogError(LogArea area, std::string message) {
    LOG_ERROR(Common, "[Symbiosis/{}] {}", ToString(area), message);
    GetLog().Write(area, LogLevel::Error, std::move(message));
}

} // namespace Symbiosis
