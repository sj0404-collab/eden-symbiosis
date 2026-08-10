// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <mutex>

#include "common/logging.h"
#include "common/symbiosis/driver_broker.h"
#include "common/symbiosis/memory_governor.h"
#include "common/symbiosis/save_guard.h"
#include "common/symbiosis/symbiosis_log.h"
#include "common/symbiosis/thermal_monitor.h"

namespace Symbiosis {

namespace {

namespace fs = std::filesystem;

constexpr u64 kMiB = 1024ULL * 1024ULL;

std::mutex g_mutex;
std::string g_vault_dir;
u32 g_keep = 5;

u64 NowSeconds() {
    using namespace std::chrono;
    return static_cast<u64>(
        duration_cast<seconds>(system_clock::now().time_since_epoch()).count());
}

/// Backups are named "<title_id>_<timestamp>" so listing is a directory scan
/// and sorting is lexicographic on the timestamp.
std::string MakeName(const std::string& title_id, u64 timestamp) {
    const std::string id = title_id.empty() ? "unknown" : title_id;
    return id + "_" + std::to_string(timestamp);
}

bool ParseName(const std::string& name, std::string& title_id, u64& timestamp) {
    const auto underscore = name.find_last_of('_');
    if (underscore == std::string::npos) {
        return false;
    }
    title_id = name.substr(0, underscore);
    try {
        timestamp = std::stoull(name.substr(underscore + 1));
    } catch (...) {
        return false;
    }
    return true;
}

u64 CopyTree(const fs::path& from, const fs::path& to, u32& files_out) {
    std::error_code ec;
    fs::create_directories(to, ec);
    u64 bytes = 0;

    for (const auto& entry : fs::recursive_directory_iterator{from, ec}) {
        if (ec) {
            break;
        }
        const auto relative = fs::relative(entry.path(), from, ec);
        if (ec) {
            continue;
        }
        const auto target = to / relative;

        if (entry.is_directory(ec)) {
            fs::create_directories(target, ec);
            continue;
        }
        if (!entry.is_regular_file(ec)) {
            continue;
        }
        fs::create_directories(target.parent_path(), ec);
        fs::copy_file(entry.path(), target, fs::copy_options::overwrite_existing, ec);
        if (!ec) {
            bytes += static_cast<u64>(entry.file_size(ec));
            files_out++;
        }
    }
    return bytes;
}

u64 DirectorySize(const fs::path& dir, u32& files_out) {
    std::error_code ec;
    u64 bytes = 0;
    for (const auto& entry : fs::recursive_directory_iterator{dir, ec}) {
        if (ec) {
            break;
        }
        if (entry.is_regular_file(ec)) {
            bytes += static_cast<u64>(entry.file_size(ec));
            files_out++;
        }
    }
    return bytes;
}

} // Anonymous namespace

// ---------------------------------------------------------------------------
// SaveVault
// ---------------------------------------------------------------------------

void SaveVault::Configure(std::string vault_dir, u32 keep_generations) {
    std::scoped_lock lock{g_mutex};
    g_vault_dir = std::move(vault_dir);
    g_keep = std::max<u32>(1, keep_generations);

    std::error_code ec;
    fs::create_directories(g_vault_dir, ec);
    LogInfo(LogArea::General,
            "save vault at " + g_vault_dir + ", keeping " + std::to_string(g_keep) +
                " generation(s)");
}

VaultStatus SaveVault::Status() {
    VaultStatus status{};
    std::string dir;
    {
        std::scoped_lock lock{g_mutex};
        dir = g_vault_dir;
    }
    if (dir.empty()) {
        return status;
    }

    status.enabled = true;
    status.vault_dir = dir;

    std::error_code ec;
    if (!fs::is_directory(dir, ec)) {
        return status;
    }

    for (const auto& entry : fs::directory_iterator{dir, ec}) {
        if (ec || !entry.is_directory(ec)) {
            continue;
        }
        std::string title_id;
        u64 timestamp = 0;
        if (!ParseName(entry.path().filename().string(), title_id, timestamp)) {
            continue;
        }
        u32 files = 0;
        status.total_bytes += DirectorySize(entry.path(), files);
        status.backup_count++;
        status.latest = std::max(status.latest, timestamp);
    }
    return status;
}

u64 SaveVault::Backup(const std::string& save_dir, const std::string& title_id,
                      const std::string& label) {
    std::string vault;
    u32 keep = 5;
    {
        std::scoped_lock lock{g_mutex};
        vault = g_vault_dir;
        keep = g_keep;
    }
    if (vault.empty()) {
        return 0;
    }

    std::error_code ec;
    if (!fs::is_directory(save_dir, ec)) {
        LogWarning(LogArea::General, "no save directory to back up: " + save_dir);
        return 0;
    }

    // Skip empty saves: backing up nothing wastes a generation slot that a
    // real save could have used.
    u32 probe_files = 0;
    if (DirectorySize(save_dir, probe_files) == 0 || probe_files == 0) {
        return 0;
    }

    const u64 timestamp = NowSeconds();
    const fs::path target = fs::path{vault} / MakeName(title_id, timestamp);

    u32 files = 0;
    const u64 bytes = CopyTree(save_dir, target, files);
    if (bytes == 0) {
        fs::remove_all(target, ec);
        return 0;
    }

    if (!label.empty()) {
        std::ofstream{target / ".label"} << label;
    }

    // Trim old generations for this title only, so backing up one game never
    // deletes another game's history.
    std::vector<fs::path> mine;
    for (const auto& entry : fs::directory_iterator{vault, ec}) {
        if (ec || !entry.is_directory(ec)) {
            continue;
        }
        std::string id;
        u64 ts = 0;
        if (ParseName(entry.path().filename().string(), id, ts) && id == title_id) {
            mine.push_back(entry.path());
        }
    }
    std::sort(mine.begin(), mine.end());
    while (mine.size() > keep) {
        fs::remove_all(mine.front(), ec);
        mine.erase(mine.begin());
    }

    LogInfo(LogArea::General, "save backed up: " + std::to_string(bytes / kMiB) + " MiB, " +
                                  std::to_string(files) + " file(s)");
    return bytes;
}

std::vector<SaveBackup> SaveVault::List() {
    std::vector<SaveBackup> out;
    std::string vault;
    {
        std::scoped_lock lock{g_mutex};
        vault = g_vault_dir;
    }
    if (vault.empty()) {
        return out;
    }

    std::error_code ec;
    if (!fs::is_directory(vault, ec)) {
        return out;
    }

    for (const auto& entry : fs::directory_iterator{vault, ec}) {
        if (ec || !entry.is_directory(ec)) {
            continue;
        }
        SaveBackup backup{};
        if (!ParseName(entry.path().filename().string(), backup.title_id, backup.timestamp)) {
            continue;
        }
        backup.path = entry.path().string();
        u32 files = 0;
        backup.size_bytes = DirectorySize(entry.path(), files);
        backup.file_count = files;

        std::ifstream label_file{entry.path() / ".label"};
        if (label_file.is_open()) {
            std::getline(label_file, backup.label);
        }
        out.push_back(std::move(backup));
    }

    std::sort(out.begin(), out.end(),
              [](const SaveBackup& a, const SaveBackup& b) { return a.timestamp > b.timestamp; });
    return out;
}

bool SaveVault::Restore(const SaveBackup& backup, const std::string& save_dir,
                        std::string& error_out) {
    std::error_code ec;
    if (!fs::is_directory(backup.path, ec)) {
        error_out = "That backup no longer exists.";
        return false;
    }

    // Take a safety copy first. Restoring the wrong generation is an easy
    // mistake, and without this it would be unrecoverable.
    if (fs::is_directory(save_dir, ec)) {
        u32 files = 0;
        if (DirectorySize(save_dir, files) > 0) {
            Backup(save_dir, backup.title_id + "-prerestore", "before restore");
        }
    }

    fs::create_directories(save_dir, ec);
    u32 copied = 0;
    const u64 bytes = CopyTree(backup.path, save_dir, copied);
    if (bytes == 0) {
        error_out = "Nothing was restored; the backup appears to be empty.";
        return false;
    }

    LogInfo(LogArea::General, "restored save: " + std::to_string(bytes / kMiB) + " MiB");
    return true;
}

bool SaveVault::Remove(const SaveBackup& backup) {
    std::error_code ec;
    return fs::remove_all(backup.path, ec) > 0 && !ec;
}

std::string SaveVault::Describe() {
    const auto status = Status();
    if (!status.enabled) {
        return "Save vault is not configured.\n";
    }
    std::string out = "Save vault:\n";
    out += "  location: " + status.vault_dir + "\n";
    out += "  backups:  " + std::to_string(status.backup_count) + "\n";
    out += "  size:     " + std::to_string(status.total_bytes / kMiB) + " MiB\n";
    out += "\nThis directory is outside the app's private storage, so clearing app data "
           "does not remove it.\n";
    return out;
}

// ---------------------------------------------------------------------------
// CrashAnalyst
// ---------------------------------------------------------------------------

bool CrashAnalyst::PreviousSessionCrashed() {
    return CrashGuard::PreviousRunCrashed();
}

std::vector<CrashFinding> CrashAnalyst::Analyse() {
    std::vector<CrashFinding> findings;

    const auto entries = GetLog().Entries(LogArea::General, true, LogLevel::Debug);

    // Count what the log actually saw, rather than guessing from the outcome.
    u32 driver_faults = 0;
    u32 quarantines = 0;
    u32 reclaims = 0;
    bool budget_low = false;
    u64 budget_mib = 0;
    bool emulated_fallbacks = false;

    for (const auto& entry : entries) {
        const auto& message = entry.message;
        if (message.find("DEVICE_LOST") != std::string::npos ||
            message.find("fault #") != std::string::npos) {
            driver_faults++;
        }
        if (message.find("quarantin") != std::string::npos) {
            quarantines++;
        }
        if (message.find("reclaimed") != std::string::npos) {
            reclaims++;
        }
        if (message.find("budget ") != std::string::npos) {
            const auto pos = message.find("budget ");
            try {
                budget_mib = std::stoull(message.substr(pos + 7));
                budget_low = budget_mib > 0 && budget_mib < 3500;
            } catch (...) {
                // Not a number we can parse; ignore rather than guess.
            }
        }
        if (message.find("emulated fallback") != std::string::npos) {
            emulated_fallbacks = true;
        }
    }

    // Thermal state is read live: if the device is still hot, it very likely
    // was hot when the crash happened.
    const auto thermal = GetThermalMonitor().Sample();

    if (quarantines > 0) {
        findings.push_back(CrashFinding{
            .confidence = 85,
            .cause = "The graphics driver stopped responding",
            .evidence = std::to_string(quarantines) +
                        " driver quarantine event(s) in the log, after " +
                        std::to_string(driver_faults) + " fault(s).",
            .action = "Switch to Stability mode. If it keeps happening in one game, try "
                      "Compatibility mode for that title.",
        });
    } else if (driver_faults > 0) {
        findings.push_back(CrashFinding{
            .confidence = 70,
            .cause = "The graphics driver reported a device loss",
            .evidence = std::to_string(driver_faults) + " driver fault(s) recorded.",
            .action = "Lower the resolution or switch to Stability mode.",
        });
    }

    if (reclaims >= 3) {
        findings.push_back(CrashFinding{
            .confidence = 75,
            .cause = "The device ran out of memory",
            .evidence = std::to_string(reclaims) +
                        " memory reclaim event(s); the caches were being trimmed "
                        "repeatedly before the failure.",
            .action = "Close background apps. If it persists, use Performance mode, which "
                      "uses a smaller render target and less texture memory.",
        });
    } else if (budget_low) {
        findings.push_back(CrashFinding{
            .confidence = 55,
            .cause = "Very little memory was available to the app",
            .evidence = "Memory budget was only " + std::to_string(budget_mib) + " MiB.",
            .action = "Restart the device or close background apps before launching.",
        });
    }

    if (thermal.state == ThermalState::Throttling ||
        thermal.state == ThermalState::Critical) {
        findings.push_back(CrashFinding{
            .confidence = 45,
            .cause = "The device was overheating",
            .evidence = thermal.summary,
            .action = "Let the device cool for ten minutes. Consider Battery mode for long "
                      "sessions.",
        });
    }

    if (emulated_fallbacks && findings.empty()) {
        findings.push_back(CrashFinding{
            .confidence = 30,
            .cause = "Several graphics features were emulated in software",
            .evidence = "The driver could not provide some capabilities natively.",
            .action = "This is normal on devices limited to their system driver, but it "
                      "makes some titles unstable. Try Compatibility mode.",
        });
    }

    std::sort(findings.begin(), findings.end(),
              [](const CrashFinding& a, const CrashFinding& b) {
                  return a.confidence > b.confidence;
              });
    return findings;
}

std::string CrashAnalyst::Describe(const std::vector<CrashFinding>& findings) {
    if (findings.empty()) {
        return "Nothing in the log points at a specific cause.\n\n"
               "That is genuinely the honest answer here rather than a guess. If the "
               "problem repeats, open the layer log right after it happens and share it: "
               "the entries from the failing session are what make a diagnosis possible.\n";
    }

    std::string out = "Most likely explanation";
    out += findings.size() > 1 ? "s:\n\n" : ":\n\n";
    for (const auto& finding : findings) {
        out += finding.cause + "  (" + std::to_string(finding.confidence) + "% confident)\n";
        out += "  evidence: " + finding.evidence + "\n";
        out += "  do this:  " + finding.action + "\n\n";
    }
    return out;
}

} // namespace Symbiosis
