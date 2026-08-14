// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.util.concurrent.ConcurrentHashMap

/**
 * Which folder each game was found in, kept OUTSIDE the Game class.
 *
 * WHY IT IS NOT A FIELD ON Game ANY MORE
 *   It was, and every build that carried it crashed on the device -
 *   including one built at upstream's own scan depth, so neither the depth
 *   nor the memory it used was the cause. A build with the same interface
 *   changes and NONE of the folder files runs perfectly. That leaves the
 *   four folder files, and Game.kt is the one every other part of Eden
 *   touches.
 *
 *   Game is @Parcelize AND @Serializable, and it is:
 *     - written to SharedPreferences as JSON, read back at every startup
 *     - passed between fragments as a Parcelable through Navigation
 *     - compared with hand-written equals/hashCode
 *     - built by GameHelper and by CustomSettingsHandler
 *
 *   Adding a constructor parameter changes the generated Parcel layout and
 *   the JSON schema at once. A Parcel written by one version of the class
 *   and read by another does not fail cleanly: it reads whatever bytes come
 *   next as the wrong type. That is exactly the kind of fault that shows up
 *   as an instant crash with no useful stack.
 *
 *   So Game goes back to being byte-for-byte upstream, and the folder lives
 *   here instead: a plain map from the game's path to its folder. Nothing
 *   is serialised, nothing crosses a Parcel, nothing changes a class Eden
 *   already knows how to read.
 *
 * WHAT IS LOST
 *   The mapping is in memory only. It is filled by the scan, so it is
 *   correct from the moment the list is built and is simply empty on the
 *   very first frame if the list came from the cache before a scan has
 *   run - in which case games show their developer, exactly as upstream.
 *   That is a fair price for not touching a class the whole app depends on.
 */
object GameFolders {

    /**
     * path -> folder, e.g.
     *   "content://.../Zelda.nsp" -> "RPG/Zelda"
     *
     * Concurrent because the scan runs on a background thread while the
     * list may already be drawing on the main one.
     */
    private val folders = ConcurrentHashMap<String, String>()

    /** Called by the scan for every game it finds. */
    fun remember(path: String, folder: String) {
        if (folder.isEmpty()) {
            folders.remove(path)
        } else {
            folders[path] = folder
        }
    }

    /** "" when the game is at the top level or has not been scanned yet. */
    fun folderOf(path: String): String = folders[path] ?: ""

    /** Just the folder's own name: "RPG/Zelda" -> "Zelda". */
    fun folderNameOf(path: String): String {
        val f = folderOf(path)
        return f.substringAfterLast('/', f)
    }

    /** Dropped before a rescan so a moved game does not keep its old folder. */
    fun clear() = folders.clear()

    /** For tests and for logging - not used by the interface. */
    fun size(): Int = folders.size
}
