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
import org.yuzu.yuzu_emu.model.GameDir
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
     * A visible folder for this build's data: /sdcard/Eden Debug.
     *
     * The default lives at Android/data/dev.legacy.eden_emulator.debug/files,
     * which Android 11+ hides from every file manager and which is deleted
     * with the app. A plain folder at the top of shared storage can be opened,
     * backed up and copied to a PC without ADB.
     *
     * Note on what this can and cannot move: the *data* - firmware, keys,
     * saves, shader cache - follows this path, because the native layer
     * derives all of it from the one root. The APK's own native libraries
     * cannot: Android extracts those into /data/app itself, and no application
     * is permitted to relocate them. Anything claiming otherwise would need
     * root.
     */
    fun visibleDefaultPath(): String =
        File(Environment.getExternalStorageDirectory(), "Eden Debug").absolutePath

    /**
     * Uses the visible folder when it is writable, otherwise private storage.
     *
     * Called only when the user has not chosen a folder of their own, so an
     * explicit choice always wins.
     */
    fun preferredDefault(context: Context): String {
        if (!hasAllFilesAccess()) {
            // Without All Files Access a folder outside Android/data cannot be
            // written to, and failing there would mean the emulator does not
            // start at all. Private storage is the safe answer.
            return privatePath(context) ?: visibleDefaultPath()
        }
        val visible = File(visibleDefaultPath())
        val usable = runCatching {
            if (!visible.exists()) visible.mkdirs()
            val probe = File(visible, ".eden_write_probe")
            probe.writeText("1")
            val ok = probe.exists()
            probe.delete()
            ok
        }.getOrDefault(false)
        return if (usable) visible.absolutePath else (privatePath(context) ?: visible.absolutePath)
    }

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
        // Выбора не было - берём то же, что и апстрим: приватную папку
        // приложения.
        //
        // Старая версия подставляла здесь /sdcard/Eden Debug, и это меняло
        // корень данных БЕЗ ведома пользователя: человек ставит сборку, а
        // ключи и прошивка, лежавшие в Android/data, перестают находиться -
        // потому что смотрим уже в другое место. Пустая папка при этом
        // создаётся сама, так что и ошибки никакой не видно.
        //
        // Видимая папка - это то, что пользователь выбирает кнопкой
        // "Сменить папку данных", осознанно. По умолчанию ничего не
        // переносится.
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
        // Snapshot before anything moves: reloadGlobalConfig() below re-reads
        // config.ini from the new root, and ReadPathValues() starts with
        // game_dirs.clear() (android_config.cpp:70).
        rememberGameDirs()

        NativeLibrary.setAppDirectory(path)
        ensureLayout(path)
        // Записывается СРАЗУ, а не после успеха: иначе выбранная папка
        // действует только до перезапуска. DirectoryInitialization при
        // каждом старте безусловно ставит getExternalFilesDir(), поэтому
        // без сохранённого пути приложение молча возвращалось в свою
        // приватную папку - вместе с ключами, прошивкой и списком игр,
        // которые пользователь туда положил.
        configuredPath = path
        // Rebuild the filesystem factories against the new root, then reload
        // the configuration that lives inside it. Without the reload the
        // in-memory settings still belong to the previous directory and would
        // be written back over the shared folder's own config on exit.
        NativeLibrary.initializeSystem(true)
        NativeConfig.reloadGlobalConfig()

        // Put the folders back. Same code path as a cold start, so both cannot
        // drift apart.
        restoreGameDirs()

        Log.info("[SharedData] data root redirected to $path")
        true
    }.getOrElse {
        Log.error("[SharedData] could not redirect to $path: ${it.message}")
        false
    }

    // --- Game folder survival --------------------------------------------
    //
    // The game list lives in config.ini, which belongs to the data root.
    // Switching roots therefore switches game lists, and pointing at a folder
    // with no config of its own empties it - the "yesterday it saw my games,
    // today it does not" report.
    //
    // Keeping a copy in SharedPreferences fixes it for good: preferences belong
    // to the app, not to the data root, so they survive both the redirect and
    // the restart that follows it. Two separate code paths (the setup wizard
    // and the utilities screen) plus the next cold start all funnel through
    // here, which is why the earlier per-path fix was not enough.

    private const val PREF_GAME_DIRS = "SymbiosisGameDirs"
    /// Data root the remembered list belongs to; restoring into a different
    /// root is the only case where the memory is wanted.
    private const val PREF_GAME_DIRS_ROOT = "SymbiosisGameDirsRoot"

    /** Records the current game folders so a later root switch cannot lose them. */
    fun rememberGameDirs() {
        val dirs = runCatching { NativeConfig.getGameDirs() }.getOrNull() ?: return
        if (dirs.isEmpty()) return
        // Newline-separated "uri\tdeepScan"; paths cannot contain a newline.
        val encoded = dirs.joinToString("\n") { "${it.uriString}\t${it.deepScan}" }
        prefs.edit()
            .putString(PREF_GAME_DIRS, encoded)
            .putString(PREF_GAME_DIRS_ROOT, configuredPath ?: "")
            .apply()
    }

    /**
     * Puts back any remembered folder the current config does not list.
     *
     * Merges rather than replaces, so folders that genuinely belong to the new
     * data root are kept and two installations can each contribute one.
     *
     * @return how many folders were restored.
     */
    /**
     * Forgets the remembered folders.
     *
     * Called whenever the user edits the folder list. Without this, removing a
     * folder only removed it from config.ini: the next start restored it from
     * preferences, and adding it again was refused with "already added" for an
     * entry that was not visible anywhere. A remembered value that outlives
     * the user's decision is worse than no memory at all.
     */
    fun forgetGameDirs() {
        prefs.edit().remove(PREF_GAME_DIRS).apply()
    }

    fun restoreGameDirs(): Int {
        val encoded = prefs.getString(PREF_GAME_DIRS, null) ?: return 0
        val remembered = encoded.split('\n').mapNotNull { line ->
            val parts = line.split('\t')
            val uri = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GameDir(uri, parts.getOrNull(1)?.toBoolean() ?: false)
        }
        if (remembered.isEmpty()) return 0

        // config.ini is authoritative while the data root is unchanged. Restoring
        // on top of it resurrects folders the user deliberately removed.
        val root = configuredPath ?: ""
        if (prefs.getString(PREF_GAME_DIRS_ROOT, root) == root) {
            return 0
        }

        val current = runCatching { NativeConfig.getGameDirs() }.getOrNull() ?: return 0
        val merged = current.toMutableList()
        val known = merged.map { it.uriString }.toMutableSet()
        var restored = 0
        for (dir in remembered) {
            if (known.add(dir.uriString)) {
                merged.add(dir)
                restored++
            }
        }
        if (restored > 0) {
            runCatching {
                NativeConfig.setGameDirs(merged.toTypedArray())
                NativeConfig.saveGlobalConfig()
            }.onFailure { return 0 }
            Log.info("[SharedData] restored $restored game folder(s) after a data-root change")
        }
        return restored
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
