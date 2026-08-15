// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Crash on restart while looking for games.
//
// Two leftovers after the previous fix still walked the tree on every
// launch: the status strip and the folder cards. Worse, a default game
// folder (or a parent stacked on its child) sent that walk through the
// layout ensureLayout() itself creates - nand, load, cache, sdmc - which
// is hundreds of files and the thing that actually killed the process.
//
// These tests pin the rules that replace that:
//   * no folder is invented
//   * a parent of another selected folder is dropped
//   * Eden layout names are never descended into
//   * the status line reads the cache and does not walk disk

import java.util.Locale

/** Names ensureLayout() creates under the data root. Walking them is the crash. */
val LAYOUT = setOf(
    "nand", "load", "cache", "sdmc", "keys", "config",
    "dump", "screenshots", "amiibo", "tas", "icons", "log",
    "play_time", "crash_dumps", "shader", "system", "contents",
    "registered", "user", "save"
)

fun isLayoutName(name: String) = name.lowercase(Locale.ROOT) in LAYOUT

/**
 * The path a SAF tree URI or a file path collapses to, for prefix checks.
 * content://.../tree/primary:Download/ed  ->  download/ed
 * /storage/emulated/0/Download/ed         ->  /storage/emulated/0/download/ed
 */
fun pathOf(uri: String): String {
    val decoded = uri.replace("%3A", ":").replace("%2F", "/").replace("%2f", "/")
    val tail = if (':' in decoded.substringAfter("://", decoded)) {
        decoded.substringAfterLast(':')
    } else {
        decoded
    }
    return tail.trim('/').lowercase(Locale.ROOT)
}

/**
 * Drop a folder that is a parent of another selected folder, and drop
 * exact duplicates keeping the first. Nothing is invented.
 */
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

/** Status line: cache only. Never walks a folder. */
fun statusFromCache(dirs: List<String>, cached: List<String>): Pair<Boolean, String> {
    if (dirs.isEmpty()) return false to "папка не выбрана"
    if (cached.isEmpty()) return false to "потяни вниз, чтобы найти игры"
    return true to "${cached.size} в ${dirs.size} папках"
}

sealed class Node {
    data class Dir(val name: String, val children: MutableList<Node> = mutableListOf()) : Node()
    data class Doc(val name: String) : Node()
}

/** Walk that refuses to enter layout directories. */
fun countSkippingLayout(root: Node.Dir, maxDepth: Int): Int {
    var count = 0
    val q = ArrayDeque<Pair<Node.Dir, Int>>()
    q.add(root to maxDepth)
    while (q.isNotEmpty()) {
        val (dir, depth) = q.removeFirst()
        for (child in dir.children) {
            when (child) {
                is Node.Dir -> if (depth > 1 && !isLayoutName(child.name)) {
                    q.add(child to depth - 1)
                }
                is Node.Doc -> if (child.name.endsWith(".nsp") || child.name.endsWith(".xci")) {
                    count++
                }
            }
        }
    }
    return count
}

var pass = 0
var fail = 0

fun check(name: String, cond: Boolean) {
    if (cond) {
        pass++
        println("  ok  $name")
    } else {
        fail++
        println("  FAIL $name")
    }
}

fun main() {
    println("\nlazy scan / no default folders")

    check("no folder is invented from an empty list", collapseLayers(emptyList()).isEmpty())

    check("a single chosen folder is kept", {
        val one = listOf("content://tree/primary:Download/games")
        collapseLayers(one) == one
    }())

    check("parent stacked on its child is dropped", {
        val parent = "content://tree/primary:Download/ed"
        val child = "content://tree/primary:Download/ed/games"
        collapseLayers(listOf(parent, child)) == listOf(child)
    }())

    check("encoded URI still collapses", {
        val parent = "content://tree/primary%3ADownload%2Fed"
        val child = "content://tree/primary%3ADownload%2Fed%2Fgames"
        collapseLayers(listOf(parent, child)) == listOf(child)
    }())

    check("duplicates collapse to the first", {
        val a = "content://tree/primary:Download/games"
        collapseLayers(listOf(a, a, a)) == listOf(a)
    }())

    check("unrelated folders are all kept", {
        val a = "content://tree/primary:Download/switch"
        val b = "content://tree/primary:SD/roms"
        collapseLayers(listOf(a, b)).toSet() == setOf(a, b)
    }())

    check("nand is a layout name", isLayoutName("nand"))
    check("load is a layout name", isLayoutName("Load"))
    check("cache is a layout name", isLayoutName("cache"))
    check("sdmc is a layout name", isLayoutName("sdmc"))
    check("keys is a layout name", isLayoutName("keys"))
    check("a game folder is not a layout name", !isLayoutName("Blade Chimera"))

    check("walking the data root does not enter nand/load/cache", {
        val root = Node.Dir(
            "files",
            mutableListOf(
                Node.Dir("nand", mutableListOf(Node.Doc("system.nca"))),
                Node.Dir("load", mutableListOf(Node.Doc("mod.nsp"))),
                Node.Dir("cache", mutableListOf(Node.Doc("shader.bin"))),
                Node.Dir("sdmc", mutableListOf(Node.Doc("save.nsp"))),
                Node.Dir("keys"),
                Node.Doc("blade.nsp")
            )
        )
        // Only the loose ROM at the top is a game. The nsp files under
        // load/ and sdmc/ must not be counted: those folders are the
        // emulator's own layout, not a library.
        countSkippingLayout(root, 3) == 1
    }())

    check("a real nested library is still found", {
        val root = Node.Dir(
            "games",
            mutableListOf(
                Node.Dir("Blade", mutableListOf(Node.Doc("blade.nsp"))),
                Node.Dir("Mario", mutableListOf(Node.Doc("mario.xci")))
            )
        )
        countSkippingLayout(root, 3) == 2
    }())

    check("empty dirs → status says no folder, does not scan", {
        val (present, detail) = statusFromCache(emptyList(), emptyList())
        !present && detail.contains("не выбрана")
    }())

    check("chosen folder, empty cache → ask to pull, do not scan", {
        val (present, detail) = statusFromCache(listOf("games"), emptyList())
        !present && detail.contains("потяни вниз")
    }())

    check("cache is enough for a green tick", {
        val (present, detail) = statusFromCache(listOf("games"), listOf("blade.nsp"))
        present && detail.startsWith("1 ")
    }())

    println("\n$pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
