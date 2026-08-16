// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.kenjinx.android

/**
 * Callbacks the Kenji core looks up by this exact class name.
 * Without it javaInitialize / GetSurfacePtr crash or no-op.
 * This is a shim, not Kenji's UI.
 */
object KenjinxNative {
    @Volatile var surfacePtr: Long = -1
    @Volatile var windowHandle: Long = -1

    @JvmStatic
    fun test() { /* official no-op */ }

    @JvmStatic
    fun frameEnded() { /* frame callback */ }

    @JvmStatic
    fun updateProgress(infoPtr: Long, progress: Float) { /* ignore */ }

    @JvmStatic
    fun getSurfacePtr(): Long = surfacePtr

    @JvmStatic
    fun getWindowHandle(): Long = windowHandle

    @JvmStatic
    fun updateUiHandler(
        newTitlePointer: Long,
        newMessagePointer: Long,
        newWatermarkPointer: Long,
        newType: Int,
        min: Int,
        max: Int,
        nMode: Int,
        newSubtitlePointer: Long,
        newInitialTextPointer: Long
    ) { /* software keyboard — ignore */ }
}
