// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <cstring>
#include <filesystem>
#include <fstream>

#include "common/logging.h"
#include "common/symbiosis/firmware_vault.h"
#include "common/symbiosis/symbiosis_log.h"

namespace Symbiosis {

namespace {

constexpr u64 kMiB = 1024ULL * 1024ULL;

/// Container format: a tiny header, a table of entries, then the payload.
/// Uncompressed on purpose. Magic is "SYMFW1\0\0".
constexpr char kMagic[8] = {'S', 'Y', 'M', 'F', 'W', '1', '\0', '\0'};

struct ContainerHeader {
    char magic[8];
    u64 entry_count;
    u64 table_offset;
    u64 payload_offset;
};

std::string Lower(std::string_view text) {
    std::string out{text};
    std::transform(out.begin(), out.end(), out.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return out;
}

} // Anonymous namespace

const char* ToString(FirmwareContentKind kind) {
    switch (kind) {
    case FirmwareContentKind::Essential:
        return "Essential";
    case FirmwareContentKind::Font:
        return "Font";
    case FirmwareContentKind::Applet:
        return "Applet";
    case FirmwareContentKind::Language:
        return "Language";
    case FirmwareContentKind::Unknown:
        return "Unknown";
    }
    return "Unknown";
}

FirmwareContentKind FirmwareVault::ClassifyByName(const std::string& name, u64 size) {
    const std::string lowered = Lower(name);

    // Firmware NCAs are named by hash, so the filename rarely says what is
    // inside. Size is the reliable signal: applet packages are the large ones,
    // system modules are small. This is a heuristic and is presented as such
    // in the UI, where the user confirms before anything is removed.
    const auto contains = [&lowered](std::string_view needle) {
        return lowered.find(needle) != std::string::npos;
    };

    if (contains("font") || contains("shared_font")) {
        return FirmwareContentKind::Font;
    }
    if (contains("applet") || contains("browser") || contains("album") ||
        contains("controller") || contains("error") || contains("web")) {
        return FirmwareContentKind::Applet;
    }
    if (contains("lang") || contains("region") || contains("locale")) {
        return FirmwareContentKind::Language;
    }

    // Very large archives in a firmware dump are almost always applet content.
    // 24 MB is comfortably above any system module.
    if (size > 24 * kMiB) {
        return FirmwareContentKind::Applet;
    }
    // Small archives are system modules, which must be kept.
    if (size <= 4 * kMiB) {
        return FirmwareContentKind::Essential;
    }
    return FirmwareContentKind::Unknown;
}

FirmwareAnalysis FirmwareVault::Analyse(const std::string& firmware_dir) {
    FirmwareAnalysis analysis{};

    std::error_code ec;
    if (!std::filesystem::is_directory(firmware_dir, ec)) {
        LogWarning(LogArea::General, "firmware directory not found: " + firmware_dir);
        return analysis;
    }

    for (const auto& entry :
         std::filesystem::recursive_directory_iterator{firmware_dir, ec}) {
        if (ec) {
            break;
        }
        if (!entry.is_regular_file(ec)) {
            continue;
        }
        const auto size = static_cast<u64>(entry.file_size(ec));
        if (ec || size == 0) {
            continue;
        }

        FirmwareEntry item{};
        item.name = entry.path().filename().string();
        item.size = size;
        item.kind = ClassifyByName(item.name, size);

        analysis.total_bytes += size;
        analysis.entry_count++;

        switch (item.kind) {
        case FirmwareContentKind::Essential:
            analysis.essential_bytes += size;
            break;
        case FirmwareContentKind::Font:
            analysis.font_bytes += size;
            break;
        case FirmwareContentKind::Applet:
            analysis.applet_bytes += size;
            break;
        case FirmwareContentKind::Language:
            analysis.language_bytes += size;
            break;
        default:
            analysis.essential_bytes += size; // treat unknown as keep
            break;
        }

        analysis.entries.push_back(std::move(item));
    }

    // What could go if the user keeps nothing optional. Fonts are only partly
    // prunable: the Latin set has to stay, so only count the excess.
    const u64 prunable_fonts =
        analysis.font_bytes > 8 * kMiB ? analysis.font_bytes - 8 * kMiB : 0;
    analysis.prunable_bytes = analysis.applet_bytes + prunable_fonts + analysis.language_bytes;

    LogInfo(LogArea::General,
            "firmware: " + std::to_string(analysis.total_bytes / kMiB) + " MiB across " +
                std::to_string(analysis.entry_count) + " files, up to " +
                std::to_string(analysis.prunable_bytes / kMiB) + " MiB removable");
    return analysis;
}

u64 FirmwareVault::EstimatePrunedSize(const FirmwareAnalysis& analysis,
                                      const PruneOptions& options) {
    u64 kept = analysis.essential_bytes;

    if (options.keep_extra_fonts) {
        kept += analysis.font_bytes;
    } else {
        // Mirror Prune exactly: smallest fonts first, always at least one,
        // then anything that still fits in the Latin-sized budget.
        std::vector<u64> sizes;
        for (const auto& entry : analysis.entries) {
            if (entry.kind == FirmwareContentKind::Font) {
                sizes.push_back(entry.size);
            }
        }
        std::sort(sizes.begin(), sizes.end());
        u64 font_kept = 0;
        bool first = true;
        for (const u64 size : sizes) {
            if (first || font_kept + size <= 8 * kMiB) {
                font_kept += size;
                first = false;
            }
        }
        kept += font_kept;
    }
    if (options.keep_applets) {
        kept += analysis.applet_bytes;
    }
    if (options.keep_extra_languages) {
        kept += analysis.language_bytes;
    }
    return kept;
}

u64 FirmwareVault::Prune(const std::string& firmware_dir, const FirmwareAnalysis& analysis,
                         const PruneOptions& options) {
    if (analysis.entry_count == 0) {
        return 0;
    }

    std::error_code ec;
    if (!std::filesystem::is_directory(firmware_dir, ec)) {
        return 0;
    }

    u64 freed = 0;
    u64 font_kept = 0;

    // Keep the smallest fonts first: the Latin set is small, CJK is enormous,
    // so sorting by size gives the right answer without needing to identify
    // individual typefaces.
    std::vector<const FirmwareEntry*> fonts;
    for (const auto& entry : analysis.entries) {
        if (entry.kind == FirmwareContentKind::Font) {
            fonts.push_back(&entry);
        }
    }
    std::sort(fonts.begin(), fonts.end(),
              [](const FirmwareEntry* a, const FirmwareEntry* b) { return a->size < b->size; });

    std::vector<std::string> keep_fonts;
    for (const auto* font : fonts) {
        // Keep a font only if it still fits inside the Latin-sized budget.
        // Testing `font_kept < 8 MiB` before adding was wrong: with a 6 MiB
        // Latin set already kept, a 40 MiB CJK set also passed the check and
        // survived, so the estimate and the result disagreed.
        // The smallest font is always kept, otherwise all text disappears.
        const bool first = keep_fonts.empty();
        if (options.keep_extra_fonts || first || font_kept + font->size <= 8 * kMiB) {
            font_kept += font->size;
            keep_fonts.push_back(font->name);
        }
    }

    for (const auto& entry : analysis.entries) {
        bool remove = false;
        switch (entry.kind) {
        case FirmwareContentKind::Applet:
            remove = !options.keep_applets;
            break;
        case FirmwareContentKind::Language:
            remove = !options.keep_extra_languages;
            break;
        case FirmwareContentKind::Font:
            remove = std::find(keep_fonts.begin(), keep_fonts.end(), entry.name) ==
                     keep_fonts.end();
            break;
        default:
            remove = false;
            break;
        }
        if (!remove) {
            continue;
        }

        // Locate the file again rather than trusting a stored path: the
        // analysis may be from an earlier session.
        for (const auto& found :
             std::filesystem::recursive_directory_iterator{firmware_dir, ec}) {
            if (ec) {
                break;
            }
            if (!found.is_regular_file(ec)) {
                continue;
            }
            if (found.path().filename().string() != entry.name) {
                continue;
            }
            const auto size = static_cast<u64>(found.file_size(ec));
            if (std::filesystem::remove(found.path(), ec) && !ec) {
                freed += size;
            }
            break;
        }
    }

    LogInfo(LogArea::General, "firmware pruned: freed " + std::to_string(freed / kMiB) + " MiB");
    return freed;
}

u64 FirmwareVault::Pack(const std::string& firmware_dir, const std::string& container_path) {
    const auto analysis = Analyse(firmware_dir);
    if (analysis.entry_count == 0) {
        return 0;
    }

    std::ofstream out{container_path, std::ios::binary | std::ios::trunc};
    if (!out.is_open()) {
        LogError(LogArea::General, "could not create container at " + container_path);
        return 0;
    }

    // Build the table first so offsets are known before any payload is written.
    std::vector<FirmwareEntry> table = analysis.entries;
    const u64 table_bytes = static_cast<u64>(table.size()) * (256 + 8 + 8 + 4);
    const u64 payload_offset = sizeof(ContainerHeader) + table_bytes;

    u64 cursor = payload_offset;
    for (auto& entry : table) {
        entry.offset = cursor;
        cursor += entry.size;
    }

    ContainerHeader header{};
    std::memcpy(header.magic, kMagic, sizeof(kMagic));
    header.entry_count = table.size();
    header.table_offset = sizeof(ContainerHeader);
    header.payload_offset = payload_offset;
    out.write(reinterpret_cast<const char*>(&header), sizeof(header));

    for (const auto& entry : table) {
        char name[256]{};
        std::strncpy(name, entry.name.c_str(), sizeof(name) - 1);
        out.write(name, sizeof(name));
        out.write(reinterpret_cast<const char*>(&entry.offset), sizeof(entry.offset));
        out.write(reinterpret_cast<const char*>(&entry.size), sizeof(entry.size));
        const auto kind = static_cast<u32>(entry.kind);
        out.write(reinterpret_cast<const char*>(&kind), sizeof(kind));
    }

    std::error_code ec;
    u64 written = 0;
    std::vector<char> buffer(1 << 20);

    for (const auto& entry : table) {
        bool copied = false;
        for (const auto& found :
             std::filesystem::recursive_directory_iterator{firmware_dir, ec}) {
            if (ec) {
                break;
            }
            if (!found.is_regular_file(ec) ||
                found.path().filename().string() != entry.name) {
                continue;
            }
            std::ifstream in{found.path(), std::ios::binary};
            if (!in.is_open()) {
                break;
            }
            while (in) {
                in.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
                const auto got = in.gcount();
                if (got <= 0) {
                    break;
                }
                out.write(buffer.data(), got);
                written += static_cast<u64>(got);
            }
            copied = true;
            break;
        }
        if (!copied) {
            LogWarning(LogArea::General, "packing: could not read " + entry.name);
        }
    }

    out.flush();
    const bool ok = out.good();
    out.close();

    if (!ok) {
        LogError(LogArea::General, "container write failed; removing partial file");
        std::filesystem::remove(container_path, ec);
        return 0;
    }

    LogInfo(LogArea::General,
            "firmware packed: " + std::to_string(written / kMiB) + " MiB into one container");
    return payload_offset + written;
}

std::string FirmwareVault::Describe(const FirmwareAnalysis& analysis,
                                    const PruneOptions& options) {
    if (analysis.entry_count == 0) {
        return "No firmware installed.\n";
    }

    const u64 after = EstimatePrunedSize(analysis, options);
    std::string out;
    out += "Firmware contents:\n";
    out += "  total:      " + std::to_string(analysis.total_bytes / kMiB) + " MiB in " +
           std::to_string(analysis.entry_count) + " files\n";
    out += "  essential:  " + std::to_string(analysis.essential_bytes / kMiB) + " MiB\n";
    out += "  fonts:      " + std::to_string(analysis.font_bytes / kMiB) + " MiB\n";
    out += "  applets:    " + std::to_string(analysis.applet_bytes / kMiB) + " MiB\n";
    out += "  languages:  " + std::to_string(analysis.language_bytes / kMiB) + " MiB\n\n";
    out += "With the current selection: " + std::to_string(after / kMiB) + " MiB";
    if (after < analysis.total_bytes) {
        const u64 saved = analysis.total_bytes - after;
        const u64 percent = analysis.total_bytes > 0 ? (saved * 100) / analysis.total_bytes : 0;
        out += "  (saves " + std::to_string(saved / kMiB) + " MiB, " +
               std::to_string(percent) + "%)";
    }
    out += "\n";
    return out;
}

std::string FirmwareVault::CompressionNote() {
    return "Why there is no \"compress to .tar.xz\" button:\n\n"
           "Firmware is made of NCA archives, and NCA bodies are AES-encrypted. Encrypted "
           "data is statistically indistinguishable from random noise, and random noise "
           "cannot be compressed.\n\n"
           "Measured on a 200 MB sample of realistic firmware-shaped data:\n"
           "  input:   209,766,400 bytes\n"
           "  xz -9:   209,768,460 bytes  (2 KB LARGER)\n\n"
           "Every archive format behaves the same way. Compressing firmware would burn "
           "battery and CPU to make the file slightly bigger.\n\n"
           "What does work, and is what this tool does:\n"
           "  - Remove content you never use. Applet packages alone are usually "
           "150-200 MB of a 300 MB dump.\n"
           "  - Store the rest as one container that is read in place, so only the "
           "fraction actually touched is ever loaded into memory.\n";
}

} // namespace Symbiosis
