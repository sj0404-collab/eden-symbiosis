// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <array>
#include <cstring>
#include <filesystem>
#include <chrono>
#include <fstream>

#include "common/logging.h"
#include "common/symbiosis/rom_tools.h"
#include "common/symbiosis/symbiosis_log.h"

namespace Symbiosis {

namespace {

constexpr u64 kMiB = 1024ULL * 1024ULL;

/// Gamecard header layout, as far as this tool needs it. Offsets are from the
/// start of the file; the magic sits at 0x100.
constexpr u64 kGamecardMagicOffset = 0x100;
constexpr u32 kGamecardMagic = 0x44414548;      // "HEAD"
constexpr u64 kValidDataEndOffset = 0x118;      // u64, in 0x200-byte media units
constexpr u64 kMediaUnit = 0x200;

constexpr u32 kPfs0Magic = 0x30534650; // "PFS0"
constexpr u32 kHfs0Magic = 0x30534648; // "HFS0"
constexpr u32 kNca3Magic = 0x3341434E; // "NCA3"
constexpr u32 kNca2Magic = 0x3241434E; // "NCA2"
constexpr u32 kNroMagic  = 0x304F524E; // "NRO0"

bool ReadAt(std::ifstream& file, u64 offset, void* out, std::size_t size) {
    file.clear();
    file.seekg(static_cast<std::streamoff>(offset), std::ios::beg);
    if (!file) {
        return false;
    }
    file.read(static_cast<char*>(out), static_cast<std::streamsize>(size));
    return static_cast<std::size_t>(file.gcount()) == size;
}

std::string LowerExtension(const std::string& path) {
    const auto dot = path.find_last_of('.');
    if (dot == std::string::npos) {
        return {};
    }
    std::string ext = path.substr(dot + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return ext;
}

} // Anonymous namespace

const char* ToString(DumpFormat format) {
    switch (format) {
    case DumpFormat::Xci:
        return "XCI";
    case DumpFormat::Nsp:
        return "NSP";
    case DumpFormat::Nca:
        return "NCA";
    case DumpFormat::Nro:
        return "NRO";
    case DumpFormat::Nsz:
        return "NSZ";
    case DumpFormat::Xcz:
        return "XCZ";
    case DumpFormat::Ncz:
        return "NCZ";
    case DumpFormat::Unknown:
        break;
    }
    return "Unknown";
}

const char* ToString(DumpHealth health) {
    switch (health) {
    case DumpHealth::Good:
        return "Good";
    case DumpHealth::Trimmed:
        return "Trimmed";
    case DumpHealth::Truncated:
        return "Truncated";
    case DumpHealth::Padded:
        return "Has padding";
    case DumpHealth::Corrupt:
        return "Corrupt";
    case DumpHealth::Unknown:
        break;
    }
    return "Unknown";
}

DumpFormat RomTools::DetectFormat(const std::string& path) {
    std::ifstream file{path, std::ios::binary};
    if (!file.is_open()) {
        return DumpFormat::Unknown;
    }

    // Community compressed formats have no distinct magic; the extension is
    // the only signal, and being honest about that is better than guessing.
    const std::string ext = LowerExtension(path);
    if (ext == "nsz") {
        return DumpFormat::Nsz;
    }
    if (ext == "xcz") {
        return DumpFormat::Xcz;
    }
    if (ext == "ncz") {
        return DumpFormat::Ncz;
    }

    u32 magic = 0;
    if (ReadAt(file, kGamecardMagicOffset, &magic, sizeof(magic)) && magic == kGamecardMagic) {
        return DumpFormat::Xci;
    }
    if (ReadAt(file, 0, &magic, sizeof(magic))) {
        if (magic == kPfs0Magic || magic == kHfs0Magic) {
            return DumpFormat::Nsp;
        }
    }
    if (ReadAt(file, 0x200, &magic, sizeof(magic))) {
        if (magic == kNca3Magic || magic == kNca2Magic) {
            return DumpFormat::Nca;
        }
    }
    if (ReadAt(file, 0x10, &magic, sizeof(magic)) && magic == kNroMagic) {
        return DumpFormat::Nro;
    }

    if (ext == "xci") {
        return DumpFormat::Xci;
    }
    if (ext == "nsp") {
        return DumpFormat::Nsp;
    }
    if (ext == "nca") {
        return DumpFormat::Nca;
    }
    if (ext == "nro") {
        return DumpFormat::Nro;
    }
    return DumpFormat::Unknown;
}

DumpReport RomTools::Inspect(const std::string& path) {
    DumpReport report{};
    report.path = path;

    std::error_code ec;
    const std::filesystem::path fs_path{path};
    report.filename = fs_path.filename().string();

    if (!std::filesystem::is_regular_file(fs_path, ec)) {
        report.health = DumpHealth::Corrupt;
        report.summary = "Not a readable file.";
        return report;
    }
    report.file_size = static_cast<u64>(std::filesystem::file_size(fs_path, ec));
    report.format = DetectFormat(path);

    std::ifstream file{path, std::ios::binary};
    if (!file.is_open()) {
        report.health = DumpHealth::Corrupt;
        report.summary = "Could not open the file.";
        return report;
    }

    switch (report.format) {
    case DumpFormat::Xci: {
        u64 units = 0;
        if (!ReadAt(file, kValidDataEndOffset, &units, sizeof(units))) {
            report.health = DumpHealth::Corrupt;
            report.summary = "The gamecard header could not be read.";
            report.advice = "The file is probably incomplete or not really an XCI.";
            break;
        }
        // valid_data_end is the last used media unit, so the content runs one
        // unit past it.
        report.valid_data_end = (units + 1) * kMediaUnit;

        // Sanity-check against the largest cartridge that exists (32 GB) rather
        // than against the file's own size. Comparing to the file size was
        // wrong: a badly truncated dump then looked "implausible" and was
        // reported as Corrupt, which tells the user nothing they can act on,
        // instead of Truncated, which tells them the transfer did not finish.
        constexpr u64 kMaxCartridgeBytes = 34ULL * 1024 * 1024 * 1024;
        if (report.valid_data_end == 0 || report.valid_data_end > kMaxCartridgeBytes) {
            report.health = DumpHealth::Corrupt;
            report.summary = "The header reports an implausible content size.";
            report.advice = "Re-dump this cartridge.";
        } else if (report.file_size < report.valid_data_end) {
            report.health = DumpHealth::Truncated;
            const u64 missing = report.valid_data_end - report.file_size;
            report.summary = "Incomplete: " + std::to_string(missing / kMiB) +
                             " MiB short of what the header declares.";
            report.advice = "This will fail to load. The transfer or dump did not finish.";
        } else if (report.file_size > report.valid_data_end + kMiB) {
            report.health = DumpHealth::Padded;
            report.reclaimable = report.file_size - report.valid_data_end;
            report.summary = "Valid, with " + std::to_string(report.reclaimable / kMiB) +
                             " MiB of cartridge padding.";
            report.advice = "Trim or convert to NSP to reclaim that space.";
        } else {
            report.health = DumpHealth::Trimmed;
            report.summary = "Valid and already trimmed.";
        }
        break;
    }

    case DumpFormat::Nsp: {
        u32 magic = 0;
        u32 file_count = 0;
        if (!ReadAt(file, 0, &magic, sizeof(magic)) ||
            !ReadAt(file, 4, &file_count, sizeof(file_count))) {
            report.health = DumpHealth::Corrupt;
            report.summary = "The PFS0 header could not be read.";
            break;
        }
        if (file_count == 0 || file_count > 10000) {
            report.health = DumpHealth::Corrupt;
            report.summary = "The archive lists an implausible number of files.";
            report.advice = "Re-download or re-dump this file.";
        } else {
            report.health = DumpHealth::Good;
            report.summary = "Valid package containing " + std::to_string(file_count) +
                             " file(s).";
        }
        break;
    }

    case DumpFormat::Nsz:
    case DumpFormat::Xcz:
    case DumpFormat::Ncz:
        report.health = DumpHealth::Unknown;
        report.summary = "Community compressed format.";
        report.advice = "Open the Converter tab and turn this into NSP/NCA. "
                        "Eden cannot launch the compressed file as-is.";
        break;

    case DumpFormat::Nca:
        report.health = DumpHealth::Good;
        report.summary = "Bare content archive.";
        report.advice = "Usually part of a package rather than something to launch.";
        break;

    case DumpFormat::Nro:
        report.health = DumpHealth::Good;
        report.summary = "Homebrew application. No keys required.";
        break;

    default:
        report.health = DumpHealth::Unknown;
        report.summary = "Not a recognised Switch dump.";
        break;
    }

    return report;
}

u64 RomTools::TrimXci(const std::string& path, std::string& error_out) {
    const auto report = Inspect(path);

    if (report.format != DumpFormat::Xci) {
        error_out = "Only XCI files carry cartridge padding.";
        return 0;
    }
    if (report.health == DumpHealth::Truncated) {
        error_out = "This file is incomplete; trimming it would destroy data.";
        return 0;
    }
    if (report.health != DumpHealth::Padded || report.reclaimable == 0) {
        error_out = "Nothing to trim.";
        return 0;
    }

    std::error_code ec;
    std::filesystem::resize_file(path, report.valid_data_end, ec);
    if (ec) {
        error_out = "Could not resize the file: " + ec.message();
        return 0;
    }

    LogInfo(LogArea::General, "trimmed " + report.filename + ": freed " +
                                  std::to_string(report.reclaimable / kMiB) + " MiB");
    return report.reclaimable;
}

u64 RomTools::XciToNsp(const std::string& source, const std::string& destination,
                       std::string& error_out) {
    const auto report = Inspect(source);
    if (report.format != DumpFormat::Xci) {
        error_out = "The source is not an XCI.";
        return 0;
    }
    if (report.health == DumpHealth::Truncated || report.health == DumpHealth::Corrupt) {
        error_out = "The source dump is not intact; fix it before converting.";
        return 0;
    }

    std::ifstream in{source, std::ios::binary};
    if (!in.is_open()) {
        error_out = "Could not open the source file.";
        return 0;
    }

    // An XCI holds several HFS0 partitions; the secure partition contains the
    // content archives that make up the game. Locate it by walking the root
    // HFS0 rather than assuming a fixed offset, because layouts differ.
    u64 hfs0_offset = 0;
    {
        u64 units = 0;
        if (!ReadAt(in, 0x130, &units, sizeof(units))) {
            error_out = "Could not read the partition table offset.";
            return 0;
        }
        hfs0_offset = units * kMediaUnit;
    }

    u32 magic = 0;
    if (!ReadAt(in, hfs0_offset, &magic, sizeof(magic)) || magic != kHfs0Magic) {
        error_out = "The partition table is missing or unreadable. This dump may use a "
                    "layout this tool does not handle.";
        return 0;
    }

    // Copying the secure partition verbatim preserves every content archive
    // and their hashes. Rebuilding a PFS0 from scratch would risk corrupting
    // the very thing we are trying to preserve, so the safe path is a byte
    // copy of the region the header points at.
    u32 partition_count = 0;
    u32 string_table_size = 0;
    if (!ReadAt(in, hfs0_offset + 4, &partition_count, sizeof(partition_count)) ||
        !ReadAt(in, hfs0_offset + 8, &string_table_size, sizeof(string_table_size))) {
        error_out = "The partition header is truncated.";
        return 0;
    }
    if (partition_count == 0 || partition_count > 16) {
        error_out = "Implausible partition count; the dump looks damaged.";
        return 0;
    }

    constexpr u64 kHfs0HeaderSize = 0x10;
    constexpr u64 kHfs0EntrySize = 0x40;
    const u64 entries_offset = hfs0_offset + kHfs0HeaderSize;
    const u64 strings_offset = entries_offset + partition_count * kHfs0EntrySize;
    const u64 data_offset = strings_offset + string_table_size;

    std::vector<char> strings(string_table_size + 1, '\0');
    if (string_table_size > 0 &&
        !ReadAt(in, strings_offset, strings.data(), string_table_size)) {
        error_out = "Could not read the partition name table.";
        return 0;
    }

    u64 secure_offset = 0;
    u64 secure_size = 0;
    for (u32 i = 0; i < partition_count; ++i) {
        const u64 entry = entries_offset + i * kHfs0EntrySize;
        u64 rel_offset = 0;
        u64 size = 0;
        u32 name_offset = 0;
        if (!ReadAt(in, entry, &rel_offset, sizeof(rel_offset)) ||
            !ReadAt(in, entry + 8, &size, sizeof(size)) ||
            !ReadAt(in, entry + 16, &name_offset, sizeof(name_offset))) {
            continue;
        }
        if (name_offset >= string_table_size) {
            continue;
        }
        const std::string name{strings.data() + name_offset};
        if (name == "secure") {
            secure_offset = data_offset + rel_offset;
            secure_size = size;
            break;
        }
    }

    if (secure_size == 0) {
        error_out = "No secure partition found in this XCI.";
        return 0;
    }
    if (secure_offset + secure_size > report.file_size) {
        error_out = "The secure partition extends past the end of the file; the dump is "
                    "incomplete.";
        return 0;
    }

    std::ofstream out{destination, std::ios::binary | std::ios::trunc};
    if (!out.is_open()) {
        error_out = "Could not create the destination file.";
        return 0;
    }

    std::vector<char> buffer(4 << 20);
    u64 remaining = secure_size;
    in.clear();
    in.seekg(static_cast<std::streamoff>(secure_offset), std::ios::beg);

    while (remaining > 0 && in) {
        const auto chunk = static_cast<std::streamsize>(
            std::min<u64>(remaining, static_cast<u64>(buffer.size())));
        in.read(buffer.data(), chunk);
        const auto got = in.gcount();
        if (got <= 0) {
            break;
        }
        out.write(buffer.data(), got);
        remaining -= static_cast<u64>(got);
    }

    out.flush();
    const bool ok = out.good() && remaining == 0;
    out.close();

    if (!ok) {
        std::error_code ec;
        std::filesystem::remove(destination, ec);
        error_out = "The copy did not complete; the partial file was removed.";
        return 0;
    }

    LogInfo(LogArea::General, "converted " + report.filename + " to NSP (" +
                                  std::to_string(secure_size / kMiB) + " MiB)");
    return secure_size;
}

std::string RomTools::Describe(const DumpReport& report) {
    std::string out;
    out += report.filename + "\n";
    out += "  format: ";
    out += ToString(report.format);
    out += "\n  status: ";
    out += ToString(report.health);
    out += "\n  size:   " + std::to_string(report.file_size / kMiB) + " MiB\n";
    if (report.valid_data_end > 0) {
        out += "  content ends at: " + std::to_string(report.valid_data_end / kMiB) + " MiB\n";
    }
    if (report.reclaimable > 0) {
        out += "  reclaimable:     " + std::to_string(report.reclaimable / kMiB) + " MiB\n";
    }
    out += "  " + report.summary + "\n";
    if (!report.advice.empty()) {
        out += "  -> " + report.advice + "\n";
    }
    return out;
}


#if defined(__has_include)
#    if __has_include(<zstd.h>)
#        define SYMBIOSIS_HAS_ZSTD 1
#        include <zstd.h>
#    endif
#endif

namespace {

constexpr u64 kNcaHeaderSize = 0x4000;
constexpr char kNczSectn[] = "NCZSECTN";
constexpr char kNczBlock[] = "NCZBLOCK";
constexpr u64 kMaxOutBytes = 34ULL * 1024ULL * 1024ULL * 1024ULL;
constexpr u32 kMaxPfsFiles = 512;

struct PackFile {
    std::string name;
    std::filesystem::path temp;
    u64 size{0};
};

bool EndsWithInsensitive(const std::string& name, const char* ext) {
    if (name.size() < 4) {
        return false;
    }
    std::string tail = name.substr(name.size() - 4);
    std::transform(tail.begin(), tail.end(), tail.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return tail == ext;
}

/// Uncompressed single-segment zstd frame (used by tests). Real NSZ uses
/// compressed frames and goes through libzstd when the header is present.
bool DecompressZstdRawFrame(const u8* src, std::size_t src_size, std::vector<u8>& out) {
    if (src_size < 9) {
        return false;
    }
    if (!(src[0] == 0x28 && src[1] == 0xB5 && src[2] == 0x2F && src[3] == 0xFD)) {
        return false;
    }
    const u8 fhd = src[4];
    const bool single = (fhd & 0x20) != 0;
    const int fcs_flag = (fhd >> 6) & 0x3;
    if (!single || fcs_flag != 0) {
        return false;
    }
    const u8 fcs = src[5];
    const u32 bh = static_cast<u32>(src[6]) | (static_cast<u32>(src[7]) << 8) |
                   (static_cast<u32>(src[8]) << 16);
    const bool last = (bh & 1u) != 0;
    const u32 type = (bh >> 1) & 0x3u;
    const u32 size = bh >> 3;
    if (!last || type != 0 || size != fcs || src_size < 9 + size) {
        return false;
    }
    out.assign(src + 9, src + 9 + size);
    return true;
}

bool DecompressZstd(const u8* src, std::size_t src_size, std::vector<u8>& out,
                    std::string& error) {
    if (DecompressZstdRawFrame(src, src_size, out)) {
        return true;
    }
#ifdef SYMBIOSIS_HAS_ZSTD
    const unsigned long long bound = ZSTD_getFrameContentSize(src, src_size);
    if (bound == ZSTD_CONTENTSIZE_ERROR) {
        error = "zstd frame is not readable.";
        return false;
    }
    const std::size_t cap = (bound == ZSTD_CONTENTSIZE_UNKNOWN)
                                ? std::min<std::size_t>(src_size * 16 + 64, 64u << 20)
                                : static_cast<std::size_t>(bound);
    if (cap == 0 || cap > kMaxOutBytes) {
        error = "zstd frame reports an implausible size.";
        return false;
    }
    out.resize(cap);
    const std::size_t got = ZSTD_decompress(out.data(), out.size(), src, src_size);
    if (ZSTD_isError(got)) {
        error = std::string("zstd: ") + ZSTD_getErrorName(got);
        out.clear();
        return false;
    }
    out.resize(got);
    return true;
#else
    error = "this build has no zstd decoder for compressed NSZ blocks.";
    return false;
#endif
}

bool WriteAll(std::ofstream& out, const void* data, std::size_t size) {
    out.write(static_cast<const char*>(data), static_cast<std::streamsize>(size));
    return out.good();
}

bool CopyStream(std::ifstream& in, std::ofstream& out, u64 bytes) {
    std::vector<char> buf(1 << 20);
    u64 left = bytes;
    while (left > 0 && in) {
        const auto n = static_cast<std::streamsize>(std::min<u64>(left, buf.size()));
        in.read(buf.data(), n);
        const auto got = in.gcount();
        if (got <= 0) {
            return false;
        }
        out.write(buf.data(), got);
        left -= static_cast<u64>(got);
    }
    return left == 0 && out.good();
}

bool DecompressNczToFile(std::ifstream& in, u64 start, u64 size, const std::filesystem::path& dest,
                         std::string& error) {
    if (size < kNcaHeaderSize + 16) {
        error = "NCZ is shorter than a header.";
        return false;
    }
    std::ofstream out{dest, std::ios::binary | std::ios::trunc};
    if (!out) {
        error = "Could not create the decompressed NCA.";
        return false;
    }
    std::vector<u8> header(kNcaHeaderSize);
    if (!ReadAt(in, start, header.data(), header.size())) {
        error = "Could not read the NCZ header.";
        return false;
    }
    if (!WriteAll(out, header.data(), header.size())) {
        return false;
    }

    u64 cursor = start + kNcaHeaderSize;
    char magic[8]{};
    if (!ReadAt(in, cursor, magic, 8) || std::memcmp(magic, kNczSectn, 8) != 0) {
        // Not actually compressed — copy the rest as a plain NCA.
        in.clear();
        in.seekg(static_cast<std::streamoff>(start + kNcaHeaderSize), std::ios::beg);
        return CopyStream(in, out, size - kNcaHeaderSize);
    }
    cursor += 8;
    u64 section_count = 0;
    if (!ReadAt(in, cursor, &section_count, 8) || section_count > 64) {
        error = "NCZ section table is unreadable.";
        return false;
    }
    cursor += 8 + section_count * 64; // skip section descriptors (64 bytes each)

    if (!ReadAt(in, cursor, magic, 8)) {
        error = "NCZ body is truncated.";
        return false;
    }

    const u64 payload_off = cursor;
    const u64 payload_size = (start + size > payload_off) ? (start + size - payload_off) : 0;
    if (payload_size == 0 || payload_size > kMaxOutBytes) {
        error = "NCZ payload size is implausible.";
        return false;
    }

    // Block-compressed NCZ: each block is its own zstd frame.
    if (std::memcmp(magic, kNczBlock, 8) == 0) {
        u8 meta[16]{};
        if (!ReadAt(in, cursor, meta, 16)) {
            error = "NCZBLOCK header is truncated.";
            return false;
        }
        const u32 block_count = static_cast<u32>(meta[12]) | (static_cast<u32>(meta[13]) << 8) |
                                (static_cast<u32>(meta[14]) << 16) | (static_cast<u32>(meta[15]) << 24);
        if (block_count == 0 || block_count > 1'000'000) {
            error = "NCZBLOCK count is implausible.";
            return false;
        }
        std::vector<u32> sizes(block_count);
        if (!ReadAt(in, cursor + 24, sizes.data(), sizes.size() * 4)) {
            error = "NCZBLOCK size table is truncated.";
            return false;
        }
        u64 off = cursor + 24 + static_cast<u64>(block_count) * 4;
        for (u32 i = 0; i < block_count; ++i) {
            const u32 cs = sizes[i];
            if (cs == 0 || off + cs > start + size) {
                error = "NCZBLOCK extends past the file.";
                return false;
            }
            std::vector<u8> comp(cs);
            if (!ReadAt(in, off, comp.data(), cs)) {
                error = "Could not read an NCZBLOCK.";
                return false;
            }
            std::vector<u8> raw;
            std::string zerr;
            if (!DecompressZstd(comp.data(), comp.size(), raw, zerr)) {
                error = zerr.empty() ? "NCZBLOCK failed to decompress." : zerr;
                return false;
            }
            if (!WriteAll(out, raw.data(), raw.size())) {
                return false;
            }
            off += cs;
        }
        return out.good();
    }

    // Solid zstd of the NCA body.
    std::vector<u8> comp(static_cast<std::size_t>(payload_size));
    if (!ReadAt(in, payload_off, comp.data(), comp.size())) {
        error = "Could not read the NCZ payload.";
        return false;
    }
    std::vector<u8> raw;
    std::string zerr;
    if (!DecompressZstd(comp.data(), comp.size(), raw, zerr)) {
        error = zerr.empty() ? "NCZ payload failed to decompress." : zerr;
        return false;
    }
    return WriteAll(out, raw.data(), raw.size());
}

std::string NcaNameFrom(const std::string& name) {
    if (EndsWithInsensitive(name, ".ncz")) {
        return name.substr(0, name.size() - 4) + ".nca";
    }
    return name;
}

bool WritePfs0(const std::vector<PackFile>& files, const std::string& dest, std::string& error) {
    u32 string_bytes = 0;
    for (const auto& f : files) {
        string_bytes += static_cast<u32>(f.name.size() + 1);
    }
    const u64 header = 16ull + static_cast<u64>(files.size()) * 24ull + string_bytes;
    std::ofstream out{dest, std::ios::binary | std::ios::trunc};
    if (!out) {
        error = "Could not create the output NSP.";
        return false;
    }
    const u32 magic = kPfs0Magic;
    const u32 count = static_cast<u32>(files.size());
    const u32 reserved = 0;
    WriteAll(out, &magic, 4);
    WriteAll(out, &count, 4);
    WriteAll(out, &string_bytes, 4);
    WriteAll(out, &reserved, 4);
    u64 rel = 0;
    u32 name_off = 0;
    for (const auto& f : files) {
        WriteAll(out, &rel, 8);
        WriteAll(out, &f.size, 8);
        WriteAll(out, &name_off, 4);
        WriteAll(out, &reserved, 4);
        rel += f.size;
        name_off += static_cast<u32>(f.name.size() + 1);
    }
    for (const auto& f : files) {
        out.write(f.name.c_str(), static_cast<std::streamsize>(f.name.size() + 1));
    }
    for (const auto& f : files) {
        std::ifstream in{f.temp, std::ios::binary};
        if (!in || !CopyStream(in, out, f.size)) {
            error = "Failed while assembling " + f.name;
            return false;
        }
    }
    return out.good();
}

bool UnpackPfs0(std::ifstream& in, u64 base, u64 span, const std::filesystem::path& work,
                std::vector<PackFile>& out, std::string& error) {
    u32 magic = 0, count = 0, str_size = 0;
    if (!ReadAt(in, base, &magic, 4) || magic != kPfs0Magic ||
        !ReadAt(in, base + 4, &count, 4) || !ReadAt(in, base + 8, &str_size, 4)) {
        error = "PFS0 header is unreadable.";
        return false;
    }
    if (count == 0 || count > kMaxPfsFiles || str_size > 1u << 20) {
        error = "PFS0 file list is implausible.";
        return false;
    }
    std::vector<char> names(str_size + 1, 0);
    const u64 names_off = base + 16 + static_cast<u64>(count) * 24;
    if (str_size > 0 && !ReadAt(in, names_off, names.data(), str_size)) {
        error = "PFS0 name table is truncated.";
        return false;
    }
    const u64 data_off = names_off + str_size;
    for (u32 i = 0; i < count; ++i) {
        const u64 e = base + 16 + static_cast<u64>(i) * 24;
        u64 off = 0, size = 0;
        u32 noff = 0;
        if (!ReadAt(in, e, &off, 8) || !ReadAt(in, e + 8, &size, 8) ||
            !ReadAt(in, e + 16, &noff, 4) || noff >= str_size) {
            error = "PFS0 entry is truncated.";
            return false;
        }
        if (data_off + off + size > base + span) {
            error = "PFS0 file extends past the archive.";
            return false;
        }
        PackFile pf;
        pf.name = NcaNameFrom(std::string{names.data() + noff});
        pf.temp = work / (std::to_string(i) + ".part");
        const bool ncz = EndsWithInsensitive(std::string{names.data() + noff}, ".ncz");
        if (ncz) {
            if (!DecompressNczToFile(in, data_off + off, size, pf.temp, error)) {
                return false;
            }
            std::error_code ec;
            pf.size = static_cast<u64>(std::filesystem::file_size(pf.temp, ec));
        } else {
            std::ofstream part{pf.temp, std::ios::binary | std::ios::trunc};
            in.clear();
            in.seekg(static_cast<std::streamoff>(data_off + off), std::ios::beg);
            if (!part || !CopyStream(in, part, size)) {
                error = "Could not extract " + pf.name;
                return false;
            }
            pf.size = size;
        }
        out.push_back(std::move(pf));
    }
    return true;
}

} // namespace

u64 RomTools::Decompress(const std::string& source, const std::string& destination,
                         std::string& error_out) {
    error_out.clear();
    const auto report = Inspect(source);
    std::ifstream in{source, std::ios::binary};
    if (!in) {
        error_out = "Could not open the source file.";
        return 0;
    }

    const auto cleanup = [&]() {
        std::error_code ec;
        std::filesystem::remove(destination, ec);
    };

    auto finish_ok = [&](u64 bytes) -> u64 {
        LogInfo(LogArea::General, "decompressed " + report.filename + " -> " +
                                      std::to_string(bytes / kMiB) + " MiB");
        return bytes;
    };

    // Already launchable: byte copy, never re-encode.
    if (report.format == DumpFormat::Nsp || report.format == DumpFormat::Xci ||
        report.format == DumpFormat::Nca || report.format == DumpFormat::Nro) {
        std::ofstream out{destination, std::ios::binary | std::ios::trunc};
        if (!out || !CopyStream(in, out, report.file_size) || !out.good()) {
            cleanup();
            error_out = "Could not copy the already-openable file.";
            return 0;
        }
        return finish_ok(report.file_size);
    }

    std::error_code ec;
    const auto work = std::filesystem::temp_directory_path() /
                      ("eden-nsz-" + std::to_string(report.file_size) + "-" +
                       std::to_string(static_cast<unsigned long long>(
                           std::chrono::steady_clock::now().time_since_epoch().count())));
    std::filesystem::create_directories(work, ec);
    const auto wipe_work = [&]() { std::filesystem::remove_all(work, ec); };

    if (report.format == DumpFormat::Ncz) {
        if (!DecompressNczToFile(in, 0, report.file_size, destination, error_out)) {
            cleanup();
            wipe_work();
            return 0;
        }
        wipe_work();
        std::error_code sz;
        return finish_ok(static_cast<u64>(std::filesystem::file_size(destination, sz)));
    }

    if (report.format == DumpFormat::Nsz) {
        std::vector<PackFile> files;
        if (!UnpackPfs0(in, 0, report.file_size, work, files, error_out)) {
            cleanup();
            wipe_work();
            return 0;
        }
        if (!WritePfs0(files, destination, error_out)) {
            cleanup();
            wipe_work();
            return 0;
        }
        wipe_work();
        std::error_code sz;
        return finish_ok(static_cast<u64>(std::filesystem::file_size(destination, sz)));
    }

    if (report.format == DumpFormat::Xcz) {
        // Same walk as XciToNsp, then treat the secure partition as HFS0/PFS0
        // of NCZ files and emit a launchable NSP.
        u64 units = 0;
        if (!ReadAt(in, 0x130, &units, sizeof(units))) {
            error_out = "Could not read the XCZ partition table.";
            return 0;
        }
        const u64 hfs0_offset = units * kMediaUnit;
        u32 magic = 0, partition_count = 0, string_table_size = 0;
        if (!ReadAt(in, hfs0_offset, &magic, 4) || magic != kHfs0Magic ||
            !ReadAt(in, hfs0_offset + 4, &partition_count, 4) ||
            !ReadAt(in, hfs0_offset + 8, &string_table_size, 4) || partition_count == 0 ||
            partition_count > 16) {
            error_out = "XCZ partition table is unreadable.";
            return 0;
        }
        const u64 entries_offset = hfs0_offset + 0x10;
        const u64 strings_offset = entries_offset + partition_count * 0x40ull;
        const u64 data_offset = strings_offset + string_table_size;
        std::vector<char> strings(string_table_size + 1, 0);
        if (string_table_size > 0 &&
            !ReadAt(in, strings_offset, strings.data(), string_table_size)) {
            error_out = "XCZ name table is truncated.";
            return 0;
        }
        u64 secure_offset = 0, secure_size = 0;
        for (u32 i = 0; i < partition_count; ++i) {
            const u64 entry = entries_offset + i * 0x40ull;
            u64 rel = 0, size = 0;
            u32 name_off = 0;
            if (!ReadAt(in, entry, &rel, 8) || !ReadAt(in, entry + 8, &size, 8) ||
                !ReadAt(in, entry + 16, &name_off, 4) || name_off >= string_table_size) {
                continue;
            }
            if (std::string{strings.data() + name_off} == "secure") {
                secure_offset = data_offset + rel;
                secure_size = size;
                break;
            }
        }
        if (secure_size == 0) {
            error_out = "XCZ has no secure partition.";
            return 0;
        }
        // Secure is HFS0 of NCAs; HFS0 starts with HFS0 magic. Some dumps store
        // PFS0. Accept either.
        u32 smagic = 0;
        if (!ReadAt(in, secure_offset, &smagic, 4)) {
            error_out = "XCZ secure partition is empty.";
            return 0;
        }
        std::vector<PackFile> files;
        if (smagic == kPfs0Magic) {
            if (!UnpackPfs0(in, secure_offset, secure_size, work, files, error_out)) {
                cleanup();
                wipe_work();
                return 0;
            }
        } else if (smagic == kHfs0Magic) {
            u32 count = 0, str_size = 0;
            if (!ReadAt(in, secure_offset + 4, &count, 4) ||
                !ReadAt(in, secure_offset + 8, &str_size, 4) || count == 0 ||
                count > kMaxPfsFiles) {
                error_out = "XCZ secure HFS0 is unreadable.";
                cleanup();
                wipe_work();
                return 0;
            }
            std::vector<char> names(str_size + 1, 0);
            const u64 names_off = secure_offset + 0x10 + static_cast<u64>(count) * 0x40;
            if (str_size && !ReadAt(in, names_off, names.data(), str_size)) {
                error_out = "XCZ secure names are truncated.";
                cleanup();
                wipe_work();
                return 0;
            }
            const u64 data = names_off + str_size;
            for (u32 i = 0; i < count; ++i) {
                const u64 e = secure_offset + 0x10 + static_cast<u64>(i) * 0x40;
                u64 off = 0, size = 0;
                u32 noff = 0;
                if (!ReadAt(in, e, &off, 8) || !ReadAt(in, e + 8, &size, 8) ||
                    !ReadAt(in, e + 16, &noff, 4) || noff >= str_size) {
                    continue;
                }
                PackFile pf;
                const std::string raw_name{names.data() + noff};
                pf.name = NcaNameFrom(raw_name);
                pf.temp = work / (std::to_string(i) + ".part");
                if (EndsWithInsensitive(raw_name, ".ncz")) {
                    if (!DecompressNczToFile(in, data + off, size, pf.temp, error_out)) {
                        cleanup();
                        wipe_work();
                        return 0;
                    }
                    pf.size = static_cast<u64>(std::filesystem::file_size(pf.temp, ec));
                } else {
                    std::ofstream part{pf.temp, std::ios::binary | std::ios::trunc};
                    in.clear();
                    in.seekg(static_cast<std::streamoff>(data + off), std::ios::beg);
                    if (!CopyStream(in, part, size)) {
                        error_out = "Could not extract " + pf.name;
                        cleanup();
                        wipe_work();
                        return 0;
                    }
                    pf.size = size;
                }
                files.push_back(std::move(pf));
            }
        } else {
            error_out = "XCZ secure partition is not HFS0/PFS0.";
            return 0;
        }
        if (files.empty()) {
            error_out = "XCZ contained no files.";
            wipe_work();
            return 0;
        }
        if (!WritePfs0(files, destination, error_out)) {
            cleanup();
            wipe_work();
            return 0;
        }
        wipe_work();
        std::error_code sz;
        return finish_ok(static_cast<u64>(std::filesystem::file_size(destination, sz)));
    }

    error_out = "Not an NSZ, XCZ or NCZ file.";
    return 0;
}

std::string RomTools::CompressionNote() {
    return "NSZ / XCZ / NCZ:\\n\\n"
           "The Converter tab decompresses these into NSP or NCA. The compressed "
           "file is left untouched; a half-written output is deleted so it cannot "
           "be launched.\\n\\n"
           "XCI padding can still be trimmed, and an XCI can still be saved as NSP, "
           "without re-encoding anything.\\n";
}

} // namespace Symbiosis
