// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Load the emulator core from a folder the user put on the device once.
 *
 * WHY THIS EXISTS
 *   The core is 83 MB of compiled C++ (19.6 MB once packed into an APK) and it
 *   almost never changes: patch2 is nine Kotlin files and three XML files, not
 *   a single .cpp. Shipping it inside every APK means re-downloading the same
 *   unchanged 20 MB for a one-line change to a button.
 *
 *   So the core can live in a folder instead. Put it on the device once, and
 *   the APK attaches to it on launch.
 *
 * THE TRAP THAT DECIDES THE WHOLE DESIGN
 *   External storage on Android is mounted **noexec**. A .so sitting in
 *   /sdcard/EdenCore/ CANNOT be loaded, however the path is written - dlopen
 *   refuses it, and it looks like a corrupt file rather than a policy.
 *
 *   The library therefore has to be COPIED into the app's own storage before
 *   it can be loaded. That is not a workaround; it is the only supported way,
 *   because the app directory is executable and external storage is not.
 *
 *   Since Android 14 the file must also be READ-ONLY at the moment of loading,
 *   or System.load throws "Attempt to load writable file". Eden targets SDK 36
 *   today, so this is a live constraint, not a future one.
 *
 * WHAT THIS DOES NOT DO
 *   It does not download anything, does not touch config.ini, and does not
 *   move the user's files. It reads one folder, copies one file, checks it,
 *   loads it.
 */
object CoreFromFolder {

    /** The library Eden's own code asks for: System.loadLibrary("yuzu-android"). */
    const val CORE_NAME = "libyuzu-android.so"

    /**
     * Helper libraries that sit beside the core in a normal build.
     *
     * Read out of a real APK rather than guessed:
     *   libhook_impl.so, libmain_hook.so, libfile_redirect_hook.so,
     *   libgsl_alloc_hook.so, libandroidx.graphics.path.so
     *
     * They are tiny - 4 KB to 350 KB - and are copied too when present. The
     * Vulkan validation layer is deliberately NOT here: it is 24 MB, it is a
     * debugging aid, and it costs frame rate.
     */
    val COMPANIONS = listOf(
        "libhook_impl.so",
        "libmain_hook.so",
        "libfile_redirect_hook.so",
        "libgsl_alloc_hook.so",
        "libandroidx.graphics.path.so"
    )

    /** Folder names looked for, in order, on every storage volume. */
    val SEARCH_NAMES = listOf("EdenCore", "eden-core", "Eden/core")

    sealed class State {
        /** The core is inside the APK; nothing to do. */
        object Builtin : State()

        /** Found in a folder and ready to load. */
        data class Found(val folder: String, val bytes: Long) : State()

        /** No folder anywhere. [looked] is what was checked, for the message. */
        data class Missing(val looked: List<String>) : State()

        /** Present but unusable, with the reason spelled out. */
        data class Broken(val reason: String) : State()
    }

    /** Where the copy lives. Never the cache directory - the system clears it. */
    fun stageDir(context: Context): File =
        File(context.filesDir, "core").apply { mkdirs() }

    fun stagedCore(context: Context): File = File(stageDir(context), CORE_NAME)

    /**
     * Is the core already inside the APK?
     *
     * Asked of the package manager rather than by trying to load it: a failed
     * load leaves the process in a state that is hard to reason about.
     */
    fun builtIn(context: Context): Boolean =
        File(context.applicationInfo.nativeLibraryDir, CORE_NAME).exists()

    /**
     * Every folder worth looking in.
     *
     * getExternalFilesDirs reports the app folder on each volume, including an
     * SD card; four levels up from it is that volume's visible root, which is
     * where a person would actually put a folder.
     */
    fun candidateFolders(context: Context): List<File> {
        val roots = mutableListOf<File>()
        context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            var p: File? = dir
            repeat(4) { p = p?.parentFile }
            p?.let { roots.add(it) }
        }
        @Suppress("DEPRECATION")
        roots.add(android.os.Environment.getExternalStorageDirectory())

        val out = mutableListOf<File>()
        val seen = mutableSetOf<String>()
        for (root in roots) {
            if (!seen.add(root.absolutePath)) continue
            for (name in SEARCH_NAMES) out.add(File(root, name))
        }
        return out
    }

    fun locate(context: Context): State {
        if (builtIn(context)) return State.Builtin

        val looked = mutableListOf<String>()
        for (dir in candidateFolders(context)) {
            looked.add(dir.absolutePath)
            val core = File(dir, CORE_NAME)
            if (!core.isFile) continue
            // An HTML error page saved under the right name is a few kilobytes
            // and would otherwise be loaded as a "core".
            if (core.length() < 1_000_000L) {
                return State.Broken("файл ядра обрезан: ${core.length()} Б")
            }
            return State.Found(dir.absolutePath, core.length())
        }
        return State.Missing(looked)
    }

    /**
     * Copy the core into app storage and load it.
     *
     * Returns null on success, or a message fit to show a person. Never throws:
     * this runs on the launch path, and an exception here is a blank screen.
     */
    fun load(context: Context): String? {
        if (builtIn(context)) return null

        val found = when (val s = locate(context)) {
            is State.Found -> s
            is State.Missing -> return "папка с ядром не найдена"
            is State.Broken -> return s.reason
            is State.Builtin -> return null
        }

        val source = File(found.folder, CORE_NAME)
        val target = stagedCore(context)

        return try {
            // Copy only when the source actually changed. Re-copying 83 MB on
            // every launch would add seconds to startup for nothing.
            if (!upToDate(source, target)) {
                target.parentFile?.mkdirs()
                if (target.exists()) {
                    target.setWritable(true, true)
                    target.delete()
                }
                source.copyTo(target, overwrite = true)
                File(target.parentFile, CORE_NAME + ".stamp").writeText(stampOf(source))
            }

            for (name in COMPANIONS) {
                val c = File(found.folder, name)
                if (!c.isFile) continue
                val ct = File(stageDir(context), name)
                if (!upToDate(c, ct)) {
                    if (ct.exists()) {
                        ct.setWritable(true, true)
                        ct.delete()
                    }
                    c.copyTo(ct, overwrite = true)
                    File(ct.parentFile, name + ".stamp").writeText(stampOf(c))
                }
                markReadOnly(ct)
            }

            // Read-only BEFORE loading, or Android 14+ refuses outright.
            if (!markReadOnly(target)) {
                return "не удалось закрыть файл ядра от записи; " +
                    "Android откажется его загрузить"
            }

            // Companions first: the core links against them, and a missing
            // dependency surfaces as an unhelpful "library not found".
            for (name in COMPANIONS) {
                val c = File(stageDir(context), name)
                if (c.isFile) runCatching { System.load(c.absolutePath) }
            }

            System.load(target.absolutePath)
            null
        } catch (e: UnsatisfiedLinkError) {
            val m = e.message.orEmpty()
            when {
                m.contains("writable", ignoreCase = true) ->
                    "Android отклонил ядро: файл доступен для записи"
                m.contains("32-bit") || m.contains("64-bit") ->
                    "ядро собрано под другую архитектуру процессора"
                else -> "ядро не загрузилось: $m"
            }
        } catch (e: Throwable) {
            "не удалось подключить ядро: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Size plus modification time - cheap, and enough to spot a replaced file. */
    fun stampOf(f: File): String = "${f.length()}:${f.lastModified()}"

    fun upToDate(source: File, target: File): Boolean {
        if (!target.isFile) return false
        val stamp = File(target.parentFile, target.name + ".stamp")
        return stamp.isFile && stamp.readText() == stampOf(source)
    }

    /**
     * Clear every write bit, and confirm.
     *
     * setReadOnly() returns false on some filesystems without changing
     * anything, so the result is checked with canWrite() rather than trusted -
     * a library that is still writable fails at load time with a message that
     * points nowhere near the cause.
     */
    fun markReadOnly(file: File): Boolean {
        file.setWritable(false, false)
        file.setReadOnly()
        return !file.canWrite()
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** A sentence for the interface, whatever the state. */
    fun describe(context: Context): String = when (val s = locate(context)) {
        is State.Builtin -> "ядро встроено в приложение"
        is State.Found -> "ядро из папки: ${s.folder} (${s.bytes / 1048576} МБ)"
        is State.Broken -> "ядро непригодно: ${s.reason}"
        is State.Missing ->
            "положите папку EdenCore с файлом " + CORE_NAME + " в память устройства"
    }
}
