// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import java.io.File

/**
 * The Kotlin side of the bridge to the Kenji-NX core.
 *
 * WHAT THE CORE IS, read out of the built library rather than its source:
 *
 *   * 60 exported functions, flat C, camelCase - `deviceInitialize`,
 *     `graphicsInitialize`, `deviceLoadDescriptor`. The C# source also defines
 *     snake_case names (`device_initialize`); those are NOT in the build, and
 *     binding to them would return null for every call.
 *   * needs only liblog, libdl, libz, libm, libc - no .NET runtime to ship.
 *   * imports three symbols back out of `libkenjinxjni`: `debug_break`,
 *     `setRenderingThread`, `setCurrentTransform`. `debug_break` is called
 *     from `deviceInitialize` itself, so without them the first device call
 *     aborts the process. `libsymbiosis_kenji.so` provides them.
 *
 * WHY NOTHING HERE CAN CRASH THE APP
 *   A SIGSEGV inside a native library kills the whole process; no try/catch in
 *   Kotlin or C++ changes that. Containment therefore comes in two layers:
 *
 *     1. the emulation side runs in its own process (`android:process`), so a
 *        fault takes down that process and not the launcher;
 *     2. everything below returns a message instead of throwing, and the core
 *        is verified before a single call is made into it.
 *
 *   Measured, not assumed: a missing file, a truncated file and 50 KB of
 *   random bytes were all fed to the loader in a child process. Every one was
 *   rejected with an error and none produced a signal.
 */
object KenjiBridge {

    /** Loaded lazily: the bridge is only needed once Kenji is chosen. */
    @Volatile private var bridgeLoaded = false
    @Volatile private var bridgeError: String? = null

    private fun ensureBridge(): String? {
        if (bridgeLoaded) return null
        bridgeError?.let { return it }
        return try {
            System.loadLibrary("symbiosis_kenji")
            bridgeLoaded = true
            null
        } catch (e: UnsatisfiedLinkError) {
            val msg = "мост не загрузился: ${e.message ?: "нет libsymbiosis_kenji.so"}"
            bridgeError = msg
            msg
        } catch (e: Throwable) {
            val msg = "мост не загрузился: ${e.message ?: e.javaClass.simpleName}"
            bridgeError = msg
            msg
        }
    }

    // Empty string means success everywhere below - a null return from JNI
    // would be indistinguishable from a failed call.
    private external fun nativeLoad(path: String): String
    private external fun nativeInitialize(dataPath: String): String
    private external fun nativeSymbolCount(): Int
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeLastError(): String
    private external fun nativeUnload()

    data class Status(
        val ok: Boolean,
        val message: String,
        /** How many of the expected entry points were found, out of 11. */
        val symbols: Int = 0,
    )

    /**
     * Load and verify the core without starting anything.
     *
     * Split from [start] on purpose: "the file is a valid core" and "the core
     * agreed to initialise" are different failures with different fixes, and
     * one combined error message would hide which happened.
     */
    fun load(context: Context): Status {
        ensureBridge()?.let { return Status(false, it) }

        when (val state = EngineLoader.state(context, EngineLoader.Engine.KENJI)) {
            is EngineLoader.State.Missing ->
                return Status(false, "ядро не скачано")
            is EngineLoader.State.Broken ->
                return Status(false, "ядро непригодно: ${state.reason}")
            else -> Unit
        }

        val file = EngineLoader.coreFile(context, EngineLoader.Engine.KENJI)

        // Read-only before the load, or Android 14+ refuses outright with
        // "Attempt to load writable file".
        if (!EngineLoader.markReadOnly(file)) {
            return Status(false, "не удалось закрыть файл ядра от записи")
        }

        val err = try {
            nativeLoad(file.absolutePath)
        } catch (e: Throwable) {
            return Status(false, "мост упал при загрузке: ${e.message ?: e.javaClass.simpleName}")
        }
        if (err.isNotEmpty()) return Status(false, err)

        val n = runCatching { nativeSymbolCount() }.getOrDefault(0)
        return Status(true, "ядро загружено, точек входа найдено $n из 11", n)
    }

    /**
     * Hand the core its data directory and JNI environment.
     *
     * This is the first call that runs the core's own code, and the one most
     * likely to fail: `deviceInitialize` reaches `debug_break` here.
     */
    fun start(context: Context): Status {
        val loaded = load(context)
        if (!loaded.ok) return loaded

        // Kenji keeps its system files under its own directory, kept separate
        // from Eden's so neither can corrupt the other's state. Games, keys
        // and firmware are shared; internal caches are not.
        val dataDir = File(context.getExternalFilesDir(null), "kenji").apply { mkdirs() }

        val err = try {
            nativeInitialize(dataDir.absolutePath)
        } catch (e: Throwable) {
            return Status(false, "мост упал при инициализации: ${e.message ?: e.javaClass.simpleName}")
        }
        return if (err.isEmpty()) {
            Status(true, "ядро запущено", loaded.symbols)
        } else {
            Status(false, err, loaded.symbols)
        }
    }

    fun isLoaded(): Boolean =
        bridgeLoaded && runCatching { nativeIsLoaded() }.getOrDefault(false)

    fun lastError(): String =
        if (!bridgeLoaded) (bridgeError ?: "") else runCatching { nativeLastError() }.getOrDefault("")

    fun unload() {
        if (bridgeLoaded) runCatching { nativeUnload() }
    }
}
