// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.preference.PreferenceManager
import java.io.File
import java.io.IOException
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.YuzuApplication

/**
 * Lets this build use another Eden installation's data directory instead of
 * keeping a second copy of everything.
 *
 * Why this is possible at all: [DirectoryInitialization] establishes the whole
 * data root with a single call to `NativeLibrary.setAppDirectory`, and the
 * native side derives firmware, keys, saves, shader cache and NAND from that
 * one path. Point it somewhere else and the entire tree follows.
 *
 * Why it is worth doing: a firmware dump is around 300 MB, and games and saves
 * are far larger. Running two Eden builds side by side normally means two of
 * everything. Sharing one directory removes the duplication entirely.
 *
 * The dangerous part is concurrency. Two emulator processes writing the same
 * shader cache and the same save files will corrupt both, and the damage is
 * silent. A lock file guards against that: whoever holds it owns the directory,
 * and a second instance is refused rather than allowed to interleave writes.
 */
object SharedDataDirectory {
    private const val PREF_SHARED_DIR = "SymbiosisSharedDataDir"
    private const val LOCK_FILE = ".eden_session.lock"

    /// Considered stale after this long: a process killed by the OS never gets
    /// to delete its lock, and the user must not be locked out permanently.
    private const val LOCK_STALE_MS = 30L * 60L * 1000L

    private val prefs
        get() = PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    /** Directory the user chose, or null when the private one is in use. */
    var configuredPath: String?
        get() = prefs.getString(PREF_SHARED_DIR, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(PREF_SHARED_DIR) else putString(PREF_SHARED_DIR, value)
            }.apply()
        }

    /** The app's own private directory; always available, never shared. */
    fun privatePath(context: Context): String? =
        runCatching { context.getExternalFilesDir(null)?.canonicalPath }.getOrNull()

    /**
     * Resolves the directory to hand to the native layer.
     *
     * Falls back to private storage whenever the configured directory is
     * missing or unwritable, so a removed SD card degrades into "starts with
     * empty data" rather than "will not start".
     */
    fun resolve(context: Context): String? {
        val configured = configuredPath
        if (configured != null) {
            val dir = File(configured)
            if (dir.isDirectory && dir.canWrite()) {
                return configured
            }
            // Do not clear the preference: the volume may simply be unmounted
            // and the user would silently lose their choice.
        }
        return privatePath(context)
    }

    /** True when a directory other than the private one is active. */
    fun isSharing(context: Context): Boolean {
        val configured = configuredPath ?: return false
        return configured != privatePath(context) && File(configured).isDirectory
    }

    // --- Validation -------------------------------------------------------

    enum class Verdict { Usable, NotADirectory, NotWritable, NoPermission, SameAsPrivate }

    data class Check(
        val verdict: Verdict,
        val hasFirmware: Boolean = false,
        val hasKeys: Boolean = false,
        val hasSaves: Boolean = false,
        val firmwareFiles: Int = 0
    ) {
        val ok: Boolean get() = verdict == Verdict.Usable
    }

    /**
     * Inspects a candidate directory before anything is committed to it.
     *
     * Reports what was found rather than only pass/fail: seeing "firmware: 412
     * files, keys: yes" is what tells the user they picked the right folder.
     */
    fun inspect(context: Context, path: String): Check {
        if (path == privatePath(context)) {
            return Check(Verdict.SameAsPrivate)
        }

        val dir = File(path)
        if (!dir.isDirectory) {
            return Check(Verdict.NotADirectory)
        }

        // canWrite() lies on some scoped-storage paths, so prove it by writing.
        val probe = File(dir, ".eden_write_probe")
        val writable = runCatching {
            probe.writeText("1")
            val ok = probe.exists()
            probe.delete()
            ok
        }.getOrDefault(false)

        if (!writable) {
            val verdict = if (needsAllFilesAccess() && !hasAllFilesAccess()) {
                Verdict.NoPermission
            } else {
                Verdict.NotWritable
            }
            return Check(verdict)
        }

        val firmwareDir = File(dir, "nand/system/Contents/registered")
        val firmwareCount = firmwareDir.listFiles()?.size ?: 0

        return Check(
            verdict = Verdict.Usable,
            hasFirmware = firmwareCount > 0,
            hasKeys = File(dir, "keys/prod.keys").let { it.isFile && it.length() > 0 },
            hasSaves = File(dir, "nand/user/save").isDirectory,
            firmwareFiles = firmwareCount
        )
    }

    /**
     * Maps a document tree URI to a real filesystem path.
     *
     * Lived in two fragments in identical form; a divergence between the copies
     * would have meant the setup wizard accepting a folder the utility rejects.
     *
     * Returns null when Android will not allow direct access: Android 11 and
     * newer block Android/data of other apps outright, even with All Files
     * Access, and the honest answer there is "no" rather than a path that
     * fails on first write.
     */
    fun resolveTreePath(uri: android.net.Uri): String? {
        val id = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return null

        val parts = id.split(':')
        if (parts.isEmpty()) return null
        val volume = parts[0]
        val relative = parts.getOrElse(1) { "" }

        val candidates = buildList {
            if (volume == "primary") {
                add("${Environment.getExternalStorageDirectory()}/$relative")
            }
            add("/storage/$volume/$relative")
        }
        return candidates.firstOrNull { File(it).isDirectory }
    }

    /**
     * Points the running process at [path] without a restart.
     *
     * The data root is normally read once, during startup, and every later
     * consumer works from the paths derived then. That is fine when the folder
     * is chosen before anything has started - but during first-run setup the
     * user picks the folder *after* initialisation, and the next screen
     * immediately asks "are the keys present?". Without this, that question is
     * answered against the old, empty directory, and a folder full of firmware
     * and keys looks blank.
     *
     * `SetAppDirectory` reinitialises the whole path manager
     * (path_util.cpp:279), so redirecting is safe as long as no emulation is
     * running. The filesystem factories are then rebuilt so the content
     * provider re-scans the new location.
     *
     * @return true when the redirect was applied.
     */
    fun redirectNow(path: String): Boolean = runCatching {
        // Remember the game folders before touching anything.
        //
        // reloadGlobalConfig() re-reads config.ini from the *new* data root,
        // and AndroidConfig::ReadPathValues() starts with
        // `AndroidSettings::values.game_dirs.clear()` (android_config.cpp:70).
        // A shared folder that has no config.ini of its own therefore replaces
        // the user's game list with an empty one - which is exactly the
        // reported "yesterday it saw my games, today it does not".
        val previousDirs = runCatching { NativeConfig.getGameDirs() }.getOrNull() ?: emptyArray()

        NativeLibrary.setAppDirectory(path)
        ensureLayout(path)
        // Rebuild the filesystem factories against the new root, then reload
        // the configuration that lives inside it. Without the reload the
        // in-memory settings still belong to the previous directory and would
        // be written back over the shared folder's own config on exit.
        NativeLibrary.initializeSystem(true)
        NativeConfig.reloadGlobalConfig()

        // Restore the list if the new root had nothing to say about it. Merging
        // rather than overwriting keeps entries the shared folder does define,
        // so two installations can each contribute a folder.
        val loadedDirs = runCatching { NativeConfig.getGameDirs() }.getOrNull() ?: emptyArray()
        if (previousDirs.isNotEmpty()) {
            val merged = loadedDirs.toMutableList()
            val known = merged.map { it.uriString }.toMutableSet()
            for (dir in previousDirs) {
                if (known.add(dir.uriString)) {
                    merged.add(dir)
                }
            }
            if (merged.size != loadedDirs.size) {
                NativeConfig.setGameDirs(merged.toTypedArray())
                NativeConfig.saveGlobalConfig()
                Log.info(
                    "[SharedData] carried over ${merged.size - loadedDirs.size} game folder(s) " +
                        "that the new data root did not list"
                )
            }
        }

        Log.info("[SharedData] data root redirected to $path")
        true
    }.getOrElse {
        Log.error("[SharedData] could not redirect to $path: ${it.message}")
        false
    }

    /** Summarises what a usable folder contains, for a confirmation prompt. */
    fun summarise(check: Check): String = buildString {
        append("firmware: ")
        append(if (check.hasFirmware) "${check.firmwareFiles} files" else "none")
        append('\n')
        append("keys: ").append(if (check.hasKeys) "yes" else "no")
        append('\n')
        append("saves: ").append(if (check.hasSaves) "yes" else "no")
    }

    fun needsAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    /**
     * A directory outside Android/data that both builds can reach.
     *
     * Android 11 blocks cross-app access to Android/data even with All Files
     * Access, so pointing directly at another app's private folder usually
     * fails. A neutral location on shared storage does work, and is offered as
     * the fallback.
     */
    fun suggestedNeutralPath(): String =
        File(Environment.getExternalStorageDirectory(), "Eden").absolutePath

    /**
     * Creates the subdirectories the emulator expects to exist.
     *
     * On desktop this is done by `Common::FS::CreateEdenPaths()`, but that is
     * only called from the Qt frontend - the Android build never calls it. In
     * private storage the folders appear as a side effect of installing content
     * through the app, so the omission goes unnoticed.
     *
     * It stops being invisible the moment the data root points somewhere new:
     * a fresh shared folder has no `load/` directory, and mods placed there are
     * never found because nothing ever created the tree they belong in.
     */
    fun ensureLayout(path: String) {
        // Mirrors the layout in common/fs/path_util.cpp so a shared folder is
        // indistinguishable from one Eden made itself.
        val required = listOf(
            "load",            // mods and cheats
            "keys",
            "nand",
            "nand/system/Contents/registered",
            "nand/user/save",
            "sdmc",
            "dump",
            "screenshots",
            "amiibo",
            "tas",
            "icons",
            "log",
            "play_time",
            "crash_dumps",
            "cache",
            "cache/shader",
            "config"
        )
        val root = File(path)
        var created = 0
        for (relative in required) {
            val dir = File(root, relative)
            if (!dir.exists() && dir.mkdirs()) {
                created++
            }
        }
        if (created > 0) {
            Log.info("[SharedData] created $created missing folder(s) under $path")
        }
    }

    // --- Session lock -----------------------------------------------------

    /**
     * Claims the directory for this process.
     *
     * @return null on success, or a message explaining who holds it.
     */
    fun acquireLock(path: String): String? {
        val lock = File(path, LOCK_FILE)

        if (lock.isFile) {
            val age = System.currentTimeMillis() - lock.lastModified()
            if (age in 0..LOCK_STALE_MS) {
                val owner = runCatching { lock.readText().trim() }.getOrDefault("another instance")
                return owner
            }
            // Stale: the previous owner died without cleaning up.
            lock.delete()
        }

        return try {
            lock.writeText("${YuzuApplication.appContext.packageName}\n${System.currentTimeMillis()}")
            null
        } catch (e: IOException) {
            "could not create a lock file: ${e.message}"
        }
    }

    /** Releases the claim. Safe to call when no lock is held. */
    fun releaseLock(path: String) {
        runCatching { File(path, LOCK_FILE).delete() }
    }

    /** Keeps the lock fresh so a long session is not mistaken for stale. */
    fun refreshLock(path: String) {
        runCatching {
            val lock = File(path, LOCK_FILE)
            if (lock.isFile) lock.setLastModified(System.currentTimeMillis())
        }
    }
}
