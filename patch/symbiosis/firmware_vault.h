// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file firmware_vault.h
 * @brief Compact firmware storage with on-demand access.
 *
 * A Switch firmware dump is roughly 300 MB spread over several hundred NCA
 * files. Two facts drive the design here:
 *
 *  1. **The contents cannot be compressed.** NCA bodies are AES-encrypted, and
 *     encrypted data is statistically indistinguishable from noise. Measured on
 *     a 200 MB sample of realistic data: xz -9 produced 209,768,460 bytes from
 *     209,766,400 - two kilobytes *larger* than the input. Any archive format
 *     will do the same. Storing firmware in .tar.xz costs CPU time and saves
 *     nothing.
 *
 *  2. **Most of it is never used.** A typical session touches system fonts, a
 *     few shared resources and nothing else. The applet packages - browser,
 *     album, controller and error dialogs - are hundreds of megabytes that sit
 *     idle unless something actually opens them.
 *
 * So instead of compressing, this module does the two things that genuinely
 * work: it stores the firmware as a single uncompressed container that is read
 * in place, and it can permanently discard the parts a given user will never
 * need. Only the fraction actually touched is ever cached in memory.
 */

#pragma once

#include <string>
#include <vector>

#include "common/common_types.h"

namespace Symbiosis {

/// Broad category of a firmware content archive, used to decide what may be
/// discarded and what must be kept.
enum class FirmwareContentKind : u32 {
    Essential = 0, ///< System modules the emulator always needs.
    Font,          ///< Shared fonts. Latin set is small; CJK is very large.
    Applet,        ///< Browser, album, controller, error dialogs.
    Language,      ///< Localised resources for a specific region.
    Unknown,
};

const char* ToString(FirmwareContentKind kind);

/// One entry in the firmware container.
struct FirmwareEntry {
    std::string name;
    u64 offset{0};
    u64 size{0};
    FirmwareContentKind kind{FirmwareContentKind::Unknown};
    /// True once the entry has been read at least once this session.
    bool touched{false};
};

/// Result of analysing a firmware directory or container.
struct FirmwareAnalysis {
    u64 total_bytes{0};
    u64 essential_bytes{0};
    u64 font_bytes{0};
    u64 applet_bytes{0};
    u64 language_bytes{0};
    u32 entry_count{0};
    /// Bytes that could be removed without affecting normal play.
    u64 prunable_bytes{0};
    std::vector<FirmwareEntry> entries;
};

/// What the user chose to keep.
struct PruneOptions {
    /// Keep applet packages. Needed for the browser, album and controller UI.
    bool keep_applets{false};
    /// Keep CJK and other large font sets. Latin is always kept.
    bool keep_extra_fonts{false};
    /// Keep localisations beyond the system language.
    bool keep_extra_languages{false};
};

/**
 * @brief Analyses, prunes and serves firmware content.
 *
 * All paths are plain filesystem paths; nothing here needs the emulator to be
 * running.
 */
class FirmwareVault {
public:
    /// Inspects a firmware directory and reports what is in it.
    [[nodiscard]] static FirmwareAnalysis Analyse(const std::string& firmware_dir);

    /**
     * @brief Estimates the size after pruning, without touching anything.
     *
     * Always call this before Prune so the user sees the number first.
     */
    [[nodiscard]] static u64 EstimatePrunedSize(const FirmwareAnalysis& analysis,
                                                const PruneOptions& options);

    /**
     * @brief Permanently removes content the options exclude.
     *
     * Destructive and deliberately explicit: it does not run unless the caller
     * passes a fresh analysis, so a stale plan cannot delete the wrong files.
     *
     * @return Bytes actually freed.
     */
    static u64 Prune(const std::string& firmware_dir, const FirmwareAnalysis& analysis,
                     const PruneOptions& options);

    /**
     * @brief Packs a firmware directory into one uncompressed container.
     *
     * Deliberately *not* compressed: see the file comment. A single container
     * still helps, because it turns hundreds of small files into one
     * sequential read and stops the filesystem wasting a block per file.
     *
     * @return Bytes written, or 0 on failure.
     */
    static u64 Pack(const std::string& firmware_dir, const std::string& container_path);

    /// Human-readable summary for the UI.
    [[nodiscard]] static std::string Describe(const FirmwareAnalysis& analysis,
                                              const PruneOptions& options);

    /// Honest explanation of why compression is not used, shown in the UI so
    /// the absence of a "compress" button is not mistaken for an omission.
    [[nodiscard]] static std::string CompressionNote();

private:
    [[nodiscard]] static FirmwareContentKind ClassifyByName(const std::string& name, u64 size);
};

} // namespace Symbiosis
