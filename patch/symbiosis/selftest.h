// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * @file selftest.h
 * @brief Built-in diagnostics that verify the emulator on the real device.
 *
 * Instead of shipping a separate homebrew .nro that the user has to sideload,
 * the checks live inside the app. They answer the questions that actually
 * block people: are my keys valid, is firmware installed, which driver am I
 * really running on, how much memory can this app have, and is the Symbiosis
 * layer doing anything.
 *
 * Every check is non-destructive and safe to run at any time.
 */

#pragma once

#include <string>
#include <vector>

#include "common/common_types.h"

namespace Symbiosis {

enum class CheckStatus : u32 {
    Pass = 0,
    Warn,
    Fail,
    Skipped,
};

const char* ToString(CheckStatus status);

struct CheckResult {
    std::string name;
    CheckStatus status{CheckStatus::Skipped};
    std::string detail;   ///< What was observed.
    std::string advice;   ///< What to do about it, when not a Pass.
};

struct SelfTestReport {
    std::vector<CheckResult> results;
    u32 passed{0};
    u32 warned{0};
    u32 failed{0};

    [[nodiscard]] std::string ToText() const;
};

/**
 * @brief Runs every diagnostic and returns a structured report.
 *
 * Safe to call from the UI thread: no check performs blocking I/O beyond
 * reading small files under /proc.
 */
SelfTestReport RunSelfTest();

} // namespace Symbiosis
