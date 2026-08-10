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

    /** Extensions the emulator will attempt to launch. */
    private val ROM_EXTENSIONS = setOf("xci", "nsp", "nca", "nro", "nso", "kip")

    data class Folder(
        val uriString: String,
        /** Last path segment, which is what the user recognises. */
        val displayName: String,
        val gameCount: Int,
        val totalBytes: Long,
        /** True when the folder could not be read at all. */
        val unreadable: Boolean = false
    )

    /**
     * Scans every configured game folder.
     *
     * @param deep when true, descends into subdirectories. Off by default: a
     *   deep scan of a large SD card is slow, and folders configured with
     *   deep_scan already say so themselves.
     */
    fun scan(context: Context): List<Folder> {
        val dirs = runCatching { NativeConfig.getGameDirs() }.getOrNull() ?: return emptyList()
        return dirs.map { dir ->
            scanOne(context, dir.uriString, dir.deepScan)
        }
    }

    private fun scanOne(context: Context, uriString: String, deep: Boolean): Folder {
        val name = displayNameOf(uriString)
        val tree = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return Folder(uriString, name, 0, 0, unreadable = true)

        var count = 0
        var bytes = 0L
        var readable = false

        // Iterative rather than recursive: a pathological tree should not be
        // able to overflow the stack on a screen this trivial.
        val queue = ArrayDeque<Uri>()
        queue.add(childrenUriFor(tree) ?: return Folder(uriString, name, 0, 0, unreadable = true))

        var guard = 0
        while (queue.isNotEmpty() && guard < MAX_DIRECTORIES) {
            guard++
            val children = queue.removeFirst()
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
                        if (deep) {
                            runCatching {
                                queue.add(
                                    DocumentsContract.buildChildDocumentsUriUsingTree(
                                        tree, documentId
                                    )
                                )
                            }
                        }
                        continue
                    }

                    if (displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        in ROM_EXTENSIONS
                    ) {
                        count++
                        bytes += size
                    }
                }
            }
        }

        return Folder(uriString, name, count, bytes, unreadable = !readable)
    }

    data class Entry(val name: String, val bytes: Long)

    /**
     * Lists the ROMs directly inside one folder.
     *
     * Read at the moment of asking rather than served from the library cache,
     * so a file copied over USB a second ago is listed and one deleted a second
     * ago is not.
     */
    fun listGames(context: Context, uriString: String): List<Entry> {
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
                if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) in ROM_EXTENSIONS) {
                    out.add(Entry(name, size))
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

    /** The part of the path a person would call the folder's name. */
    fun displayNameOf(uriString: String): String {
        val decoded = runCatching { Uri.decode(uriString) }.getOrDefault(uriString)
        val tail = decoded.substringAfterLast(':', decoded.substringAfterLast('/'))
        return tail.substringAfterLast('/').ifBlank { decoded }
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
