// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import androidx.annotation.StringRes
import org.yuzu.yuzu_emu.R

/**
 * Maps text produced by the native layer onto Android string resources.
 *
 * The C++ side builds its catalogues as plain literals: it has no access to
 * the resource system, and pushing a JNI localisation callback down into it
 * would be far more machinery than the problem deserves. The consequence is
 * that anything crossing JNI arrives in English.
 *
 * This object translates the parts that are user-facing prose. Proper nouns
 * are deliberately left alone: "Steam", "PlayStation", "Dendy", "Mali-G610"
 * and "Valhall" are names, not words to translate, and localising them would
 * make the UI harder to match against documentation, not easier.
 *
 * Anything without a mapping falls through unchanged, so a new entry added on
 * the native side shows up in English rather than disappearing.
 */
object SymbiosisStrings {

    /** Launcher descriptions, keyed by the stable launcher key. */
    private val launcherDescriptions: Map<String, Int> = mapOf(
        "eden" to R.string.launcher_desc_eden,
        "steam" to R.string.launcher_desc_steam,
        "switch" to R.string.launcher_desc_switch,
        "ps1" to R.string.launcher_desc_ps1,
        "ps2" to R.string.launcher_desc_ps2,
        "ps3" to R.string.launcher_desc_ps3,
        "ps4" to R.string.launcher_desc_ps4,
        "ps5" to R.string.launcher_desc_ps5,
        "dendy" to R.string.launcher_desc_dendy,
        "gba" to R.string.launcher_desc_gba,
        "nds" to R.string.launcher_desc_nds,
        "custom" to R.string.launcher_desc_custom
    )

    /** Performance notes attached to launchers. */
    private val performanceNotes: Map<String, Int> = mapOf(
        "eden" to R.string.perf_note_free,
        "steam" to R.string.perf_note_free,
        "switch" to R.string.perf_note_free,
        "ps4" to R.string.perf_note_free,
        "ps5" to R.string.perf_note_free,
        "ps3" to R.string.perf_note_almost_free,
        "ps1" to R.string.perf_note_faster,
        "ps2" to R.string.perf_note_faster,
        "dendy" to R.string.perf_note_much_faster,
        "gba" to R.string.perf_note_dramatically_faster,
        "nds" to R.string.perf_note_dramatically_faster,
        "custom" to R.string.perf_note_depends
    )

    /** Auto-mode names, keyed by the enum ordinal used across JNI. */
    private val modeNames: Map<Int, Int> = mapOf(
        0 to R.string.mode_quality,
        1 to R.string.mode_balanced,
        2 to R.string.mode_performance,
        3 to R.string.mode_stability,
        4 to R.string.mode_compatibility,
        5 to R.string.mode_turbo,
        6 to R.string.mode_custom
    )

    /** Compatibility ratings, as reported by the native catalogue. */
    private val compatRatings: Map<String, Int> = mapOf(
        "Perfect" to R.string.compat_perfect,
        "Playable" to R.string.compat_playable,
        "Runs with issues" to R.string.compat_runs,
        "Intro only" to R.string.compat_intro,
        "Broken" to R.string.compat_broken,
        "Unknown" to R.string.compat_unknown
    )

    fun launcherDescription(context: Context, key: String, fallback: String): String =
        launcherDescriptions[key]?.let { context.getString(it) } ?: fallback

    fun performanceNote(context: Context, key: String, fallback: String): String =
        performanceNotes[key]?.let { context.getString(it) } ?: fallback

    fun modeName(context: Context, ordinal: Int, fallback: String): String =
        modeNames[ordinal]?.let { context.getString(it) } ?: fallback

    fun compatRating(context: Context, rating: String, fallback: String): String =
        compatRatings[rating]?.let { context.getString(it) } ?: fallback

    /**
     * Localises a whole diagnostic report line by line.
     *
     * Reports are assembled natively from many fragments; translating them
     * wholesale is not practical. What matters is that the *labels* a user
     * scans for are in their language, so those are replaced and the technical
     * values are left untouched.
     */
    fun localiseReport(context: Context, report: String): String {
        if (report.isBlank()) return report
        var out = report
        for ((english, resId) in reportLabels) {
            out = out.replace(english, context.getString(resId))
        }
        return out
    }

    private val reportLabels: List<Pair<String, Int>> by lazy {
        listOf(
            "Symbiosis driver topology:" to R.string.rep_driver_topology,
            "Symbiosis memory state:" to R.string.rep_memory_state,
            "Mali tuning:" to R.string.rep_mali_tuning,
            "Firmware contents:" to R.string.rep_firmware_contents,
            "Save vault:" to R.string.rep_save_vault,
            "  total:" to R.string.rep_total,
            "  budget:" to R.string.rep_budget,
            "  used:" to R.string.rep_used,
            "  pressure:" to R.string.rep_pressure,
            "  essential:" to R.string.rep_essential,
            "  fonts:" to R.string.rep_fonts,
            "  applets:" to R.string.rep_applets,
            "  languages:" to R.string.rep_languages,
            "  generation:" to R.string.rep_generation,
            "  device:" to R.string.rep_device,
            "  cores:" to R.string.rep_cores,
            "  backups:" to R.string.rep_backups,
            "  location:" to R.string.rep_location
        )
    }
}
