// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context

/**
 * Which engine the next game should start on.
 *
 * Deliberately tiny and dependency-free: this is read on the launch path, and
 * a preference that needs the whole settings stack to be initialised first is
 * a startup crash waiting to happen.
 *
 * The stored value is the engine's id, not its ordinal. An ordinal would
 * silently point at a different engine the day the enum gains an entry.
 */
object EnginePreference {

    private const val FILE = "symbiosis_engine"
    private const val KEY = "selected_engine"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The chosen engine, falling back to Eden.
     *
     * The fallback is not decoration. A downloaded core can be deleted by the
     * system under storage pressure, or fail its hash after a partial write;
     * returning a selection that cannot start would strand the app on a black
     * screen with no way back. Eden is compiled in, so it always works.
     */
    fun selected(context: Context): EngineLoader.Engine {
        val id = prefs(context).getString(KEY, null) ?: return EngineLoader.Engine.EDEN
        val engine = EngineLoader.Engine.values().firstOrNull { it.id == id }
            ?: return EngineLoader.Engine.EDEN

        return when (EngineLoader.state(context, engine)) {
            is EngineLoader.State.Builtin, is EngineLoader.State.Ready -> engine
            else -> EngineLoader.Engine.EDEN
        }
    }

    /** What was chosen, whether or not it is currently usable. */
    fun selectedRaw(context: Context): EngineLoader.Engine {
        val id = prefs(context).getString(KEY, null) ?: return EngineLoader.Engine.EDEN
        return EngineLoader.Engine.values().firstOrNull { it.id == id }
            ?: EngineLoader.Engine.EDEN
    }

    fun select(context: Context, engine: EngineLoader.Engine) {
        prefs(context).edit().putString(KEY, engine.id).apply()
    }

    /**
     * True when the stored choice is not the one that will actually run - so
     * the interface can say why instead of quietly starting the wrong engine.
     */
    fun fellBack(context: Context): Boolean =
        selectedRaw(context) != selected(context)
}
