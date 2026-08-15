// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

/**
 * Counts games and bytes per configured folder.
 *
 * Reads the tree directly through the content resolver instead of going via
 * [GameHelper], which parses every ROM header to build library metadata. That
 * is far too expensive for a screen whose only job is to say "14 games, 22 GB",
 * and it needs the encryption keys loaded; counting files needs neither.
 */
object GameFolderScanner {

    /**
     * Extensions the library will actually import.
     *
     * Must match Game.extensions upstream exactly. It did not: this listed nso
     * and kip as well, so the status strip counted files the emulator then
     * refused to show, and reported "1 game" over an empty list. A count the
     * library cannot reproduce is worse than no count - it sends the user
     * looking for a bug in the wrong place.
     */
    private val ROM_EXTENSIONS = setOf("xci", "nsp", "nca", "nro")

    /**
     * Всё, что человек считает файлом игры - включая то, что сам эмулятор
     * не откроет.
     *
     * ncz/nsz/xcz - сжатые образы. Eden их не запускает, и в списке игр их
     * не будет никогда. Но лежат они в той же папке, занимают место, и
     * пользователь про них знает. Панель "Мои игры" показывает их честно,
     * помечая как незапускаемые, вместо того чтобы делать вид, что папка
     * пуста - именно эта пустота и выглядит как "эмулятор ничего не нашёл".
     */
    private val SHOWABLE_EXTENSIONS =
        ROM_EXTENSIONS + setOf("ncz", "nsz", "xcz", "kip", "nso")

    /** Файл этого типа эмулятор запустить не сможет. */
    fun isLaunchable(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in ROM_EXTENSIONS ||
            name.lowercase(Locale.ROOT) in ROM_FILENAMES

    /**
     * Плоский список файлов игр ровно в этой папке, без захода вглубь.
     *
     * Отдельно от [listGames], потому что вопросы разные: тот отвечает
     * "что импортирует библиотека", а этот - "что лежит в папке". Без
     * рекурсии, без фильтра по тому, примет ли файл эмулятор, и без
     * ограничения по количеству.
     */
    fun listFilesFlat(context: Context, uriString: String): List<Entry> {
        val tree = runCatching { Uri.parse(uriString) }.getOrNull() ?: return emptyList()
        val children = childrenUriFor(tree) ?: return emptyList()
        val out = mutableListOf<Entry>()
        val cursor = runCatching {
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )
        }.getOrNull() ?: return emptyList()

        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val mime = it.getString(1) ?: ""
                val size = if (it.isNull(2)) 0L else it.getLong(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext in SHOWABLE_EXTENSIONS || name.lowercase(Locale.ROOT) in ROM_FILENAMES) {
                    out.add(Entry(name, size))
                }
            }
        }
        return out.sortedByDescending { it.bytes }
    }

    /**
     * Filenames that are a game without having a game's extension.
     *
     * loader.cpp:GuessFromFilename treats a file literally called "main" as a
     * DeconstructedRomDirectory - an unpacked game, where the folder is the
     * title and "main" is its executable - and "00" as an NCA, which is how a
     * split dump names its first part. Both are ordinary in real libraries and
     * both were invisible here: the scanner only looked at extensions, so an
     * unpacked game showed up as an empty folder.
     */
    private val ROM_FILENAMES = setOf("main", "00")

    /** True when this file is something the emulator can load. */
    private fun isRom(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        if (lower in ROM_FILENAMES) return true
        return lower.substringAfterLast('.', "") in ROM_EXTENSIONS
    }

    /**
     * Extensions that look like content but are never listed as games.
     *
     * Worth naming separately so the folder screen can say "3 files here are
     * not launchable" instead of pretending the folder is empty.
     */
    private val NON_GAME_EXTENSIONS = setOf("nso", "kip", "bin", "zip", "7z", "rar")

    data class Folder(
        val uriString: String,
        /** Last path segment, which is what the user recognises. */
        val displayName: String,
        val gameCount: Int,
        val totalBytes: Long,
        /** Files with a ROM-ish extension the library will not import. */
        val skipped: Int = 0,
        /** Depth this folder was scanned at, so the file list can match. */
        val depth: Int = 1,
        /** True when the folder could not be read at all. */
        val unreadable: Boolean = false
    )

    /**
     * Scans every configured game folder.
     *
     * Always descends into subdirectories, regardless of the folder's
     * `deep_scan` flag. That flag governs what the emulator's own library
     * importer does; using it here made the count depend on a setting the user
     * cannot see from this screen, and let the count disagree with the file
     * list beside it. A storage summary that says "14 games" must mean the same
     * fourteen files the list can show. [MAX_DIRECTORIES] keeps a pathological
     * tree from turning that into a hang.
     */
    fun scan(context: Context): List<Folder> {
        val dirs = runCatching { NativeConfig.getGameDirs() }.getOrNull() ?: return emptyList()
        return dirs.map { dir ->
            scanOne(context, dir.uriString, depthFor(dir.deepScan))
        }
    }

    /**
     * How deep the library looks: GameHelper uses 3 with deep scan on and 1
     * without, counting the folder itself as one level.
     *
     * Matching it matters. This used to walk the whole tree regardless, so a
     * game two directories down was counted here and ignored by the importer,
     * and the strip claimed a game the list could never show.
     */
    fun depthFor(deepScan: Boolean): Int = if (deepScan) 3 else 1

    private val LAYOUT_NAMES = setOf(
        "nand", "load", "cache", "sdmc", "keys", "config",
        "dump", "screenshots", "amiibo", "tas", "icons", "log",
        "play_time", "crash_dumps", "shader", "system", "contents",
        "registered", "user", "save"
    )

    fun isLayoutName(name: String): Boolean =
        name.lowercase(Locale.ROOT) in LAYOUT_NAMES

    fun pathOf(uri: String): String {
        val decoded = runCatching { Uri.decode(uri) }.getOrDefault(uri)
        val afterScheme = decoded.substringAfter("://", decoded)
        val tail = if (':' in afterScheme) decoded.substringAfterLast(':') else decoded
        return tail.trim('/').lowercase(Locale.ROOT)
    }

    fun collapseLayers(uris: List<String>): List<String> {
        val paths = uris.map { pathOf(it) }
        val keep = BooleanArray(uris.size) { true }
        for (i in uris.indices) {
            for (j in uris.indices) {
                if (i == j || !keep[i]) continue
                val a = paths[i]
                val b = paths[j]
                if (a.isEmpty()) continue
                if (b.startsWith("$a/")) keep[i] = false
                if (a == b && j < i) keep[i] = false
            }
        }
        return uris.filterIndexed { i, _ -> keep[i] }
    }

    /** Scan one folder, for callers that have a uri rather than the config. */
    fun scanOneFolder(context: Context, uriString: String, deepScan: Boolean): Folder =
        scanOne(context, uriString, depthFor(deepScan))

    private fun scanOne(context: Context, uriString: String, maxDepth: Int): Folder {
        val name = displayNameOf(uriString)
        val tree = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return Folder(uriString, name, 0, 0, depth = maxDepth, unreadable = true)

        var count = 0
        var bytes = 0L
        var skipped = 0
        var readable = false

        // Iterative rather than recursive: a pathological tree should not be
        // able to overflow the stack on a screen this trivial.
        val queue = ArrayDeque<Pair<Uri, Int>>()
        queue.add((childrenUriFor(tree)
            ?: return Folder(uriString, name, 0, 0, depth = maxDepth, unreadable = true)) to maxDepth)

        var guard = 0
        while (queue.isNotEmpty() && guard < MAX_DIRECTORIES) {
            guard++
            val (children, depth) = queue.removeFirst()
            val cursor = runCatching {
                context.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE
                    ),
                    null, null, null
                )
            }.getOrNull() ?: continue

            readable = true
            cursor.use {
                while (it.moveToNext()) {
                    val documentId = it.getString(0)
                    val displayName = it.getString(1) ?: ""
                    val mimeType = it.getString(2) ?: ""
                    val size = if (it.isNull(3)) 0L else it.getLong(3)

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth > 1 && !isLayoutName(displayName)) {
                            runCatching {
                                queue.add(
                                    DocumentsContract.buildChildDocumentsUriUsingTree(
                                        tree, documentId
                                    ) to depth - 1
                                )
                            }
                        }
                        continue
                    }

                    val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (isRom(displayName)) {
                        count++
                        bytes += size
                    } else if (ext in NON_GAME_EXTENSIONS) {
                        skipped++
                    }
                }
            }
        }

        return Folder(uriString, name, count, bytes, skipped, maxDepth, unreadable = !readable)
    }

    data class Entry(
        val name: String,
        val bytes: Long,
        /** Sub-path below the folder root, empty when the file sits at the top. */
        val relativePath: String = ""
    )

    /**
     * Lists the ROMs inside one folder.
     *
     * Descends into subdirectories, because [scanOne] does. When the two
     * disagreed the screen said "14 games" and then listed none of them: the
     * count walked the whole tree while this only ever read the top level, so
     * a library organised one-folder-per-game - which is the normal way to
     * keep them - looked empty. Anything the counter counts must be listable,
     * or the count is a lie.
     *
     * Read at the moment of asking rather than served from the library cache,
     * so a file copied over USB a second ago is listed and one deleted a second
     * ago is not.
     */
    fun listGames(context: Context, uriString: String, maxDepth: Int = 1): List<Entry> {
        val tree = runCatching { Uri.parse(uriString) }.getOrNull() ?: return emptyList()
        val root = childrenUriFor(tree) ?: return emptyList()
        val out = mutableListOf<Entry>()

        // Same walk, same guard and the same depth as the counter, so the two
        // cannot drift apart again.
        val queue = ArrayDeque<Triple<Uri, String, Int>>()
        queue.add(Triple(root, "", maxDepth))
        var guard = 0

        while (queue.isNotEmpty() && guard < MAX_DIRECTORIES) {
            guard++
            val (children, prefix, depth) = queue.removeFirst()
            val cursor = runCatching {
                context.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE
                    ),
                    null, null, null
                )
            }.getOrNull() ?: continue

            cursor.use {
                while (it.moveToNext()) {
                    val documentId = it.getString(0)
                    val name = it.getString(1) ?: continue
                    val mime = it.getString(2) ?: ""
                    val size = if (it.isNull(3)) 0L else it.getLong(3)

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth > 1) {
                            runCatching {
                                queue.add(
                                    Triple(
                                        DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId),
                                        if (prefix.isEmpty()) name else "$prefix/$name",
                                        depth - 1
                                    )
                                )
                            }
                        }
                        continue
                    }

                    if (isRom(name)) {
                        out.add(Entry(name, size, prefix))
                    }
                }
            }
        }
        return out.sortedByDescending { it.bytes }
    }

    private fun childrenUriFor(tree: Uri): Uri? = runCatching {
        DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
    }.getOrNull()

    /**
     * The document URI of a file this scanner listed.
     *
     * The scanner records a name and a sub-path rather than a URI, because a
     * URI is large and most callers only want to count. Rebuilding it here
     * keeps that trade without making the folder screen re-walk the tree.
     *
     * Document ids under a tree are the tree's own id plus the relative path,
     * which is how the storage framework addresses children; when a provider
     * does not follow that shape the caller gets null and says so rather than
     * launching something wrong.
     */
    fun childUri(treeUriString: String, entry: Entry): Uri? = runCatching {
        val tree = Uri.parse(treeUriString)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val suffix = if (entry.relativePath.isEmpty()) entry.name
                     else entry.relativePath + "/" + entry.name
        val childId = if (rootId.endsWith("/")) rootId + suffix else "$rootId/$suffix"
        DocumentsContract.buildDocumentUriUsingTree(tree, childId)
    }.getOrNull()

    /** The part of the path a person would call the folder's name. */
    fun displayNameOf(uriString: String): String {
        val decoded = runCatching { Uri.decode(uriString) }.getOrDefault(uriString)
        val tail = decoded.substringAfterLast(':', decoded.substringAfterLast('/'))
        return tail.substringAfterLast('/').ifBlank { decoded }
    }

    /**
     * Total bytes under a plain filesystem directory.
     *
     * Keys, firmware, saves and the shader cache live in the data root as
     * ordinary files, not behind a document tree, so they are measured with
     * [java.io.File] rather than the content resolver. Returns 0 for a missing
     * directory, which is the honest answer for "nothing installed".
     */
    fun directoryBytes(path: String?): Long {
        if (path.isNullOrBlank()) return 0L
        val root = java.io.File(path)
        if (!root.exists()) return 0L
        if (root.isFile) return root.length()

        var total = 0L
        var guard = 0
        val queue = ArrayDeque<java.io.File>()
        queue.add(root)
        while (queue.isNotEmpty() && guard < MAX_DIRECTORIES) {
            guard++
            val entries = queue.removeFirst().listFiles() ?: continue
            for (entry in entries) {
                if (entry.isDirectory) queue.add(entry) else total += entry.length()
            }
        }
        return total
    }

    /** Formats bytes the way a storage screen would. */
    fun humanSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val gb = bytes / 1_073_741_824.0
        if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
        val mb = bytes / 1_048_576.0
        if (mb >= 1.0) return String.format(Locale.US, "%.0f MB", mb)
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    /** Stops a symlink loop or an absurd tree from hanging the scan. */
    private const val MAX_DIRECTORIES = 400
}
