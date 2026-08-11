// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

// The folder screen said "14 games" and then listed none of them.
//
// Two walks over the same tree had drifted apart: the counter descended into
// subdirectories, the lister only ever read the top level. A library kept the
// normal way - one folder per game - therefore counted fine and displayed
// empty, which is exactly "he knows the files are there but cannot see them".
//
// GameFolderScanner itself needs Android's ContentResolver, so these tests
// model the two traversals over an in-memory tree. That is enough to pin the
// property that actually broke: whatever the counter counts, the lister must
// be able to list.

import java.util.Locale

private val ROM_EXTENSIONS = setOf("xci", "nsp", "nca", "nro", "nso", "kip")
private const val MAX_DIRECTORIES = 400

/** Stand-in for a SAF document tree. */
sealed class Node {
    data class Dir(val name: String, val children: MutableList<Node> = mutableListOf()) : Node()
    data class Doc(val name: String, val bytes: Long) : Node()
}

private fun isRom(name: String) =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT) in ROM_EXTENSIONS

/** The counter, as shipped: always recursive. */
fun countGames(root: Node.Dir): Pair<Int, Long> {
    var count = 0
    var bytes = 0L
    var guard = 0
    val queue = ArrayDeque<Node.Dir>()
    queue.add(root)
    while (queue.isNotEmpty() && guard < MAX_DIRECTORIES) {
        guard++
        for (child in queue.removeFirst().children) {
            when (child) {
                is Node.Dir -> queue.add(child)
                is Node.Doc -> if (isRom(child.name)) { count++; bytes += child.bytes }
            }
        }
    }
    return count to bytes
}

data class Entry(val name: String, val bytes: Long, val relativePath: String)

/** The lister, as fixed: recursive, and it records where each file came from. */
fun listGames(root: Node.Dir): List<Entry> {
    val out = mutableListOf<Entry>()
    var guard = 0
    val queue = ArrayDeque<Pair<Node.Dir, String>>()
    queue.add(root to "")
    while (queue.isNotEmpty() && guard < MAX_DIRECTORIES) {
        guard++
        val (dir, prefix) = queue.removeFirst()
        for (child in dir.children) {
            when (child) {
                is Node.Dir -> queue.add(
                    child to if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                )
                is Node.Doc -> if (isRom(child.name)) out.add(Entry(child.name, child.bytes, prefix))
            }
        }
    }
    return out.sortedByDescending { it.bytes }
}

/** The lister as it used to be: top level only. This is the bug. */
fun listGamesTopLevelOnly(root: Node.Dir): List<Entry> =
    root.children.filterIsInstance<Node.Doc>()
        .filter { isRom(it.name) }
        .map { Entry(it.name, it.bytes, "") }

private var passed = 0
private var failed = 0

private fun check(name: String, body: () -> Unit) {
    try { body(); passed++; println("  ok  $name") }
    catch (e: Throwable) { failed++; println("  FAIL $name\n       ${e.message}") }
}

private fun assertEq(expected: Any?, actual: Any?, what: String) {
    if (expected != actual) throw AssertionError("$what: expected $expected, got $actual")
}

private fun assertTrue(cond: Boolean, what: String) {
    if (!cond) throw AssertionError(what)
}

/** One folder per game, which is how people actually organise a library. */
private fun nestedLibrary(): Node.Dir = Node.Dir(
    "games", mutableListOf(
        Node.Dir("Blade Chimera", mutableListOf(Node.Doc("blade.nsp", 2_000_000_000L))),
        Node.Dir("Mario", mutableListOf(Node.Doc("mario.xci", 8_000_000_000L))),
        Node.Dir(
            "Updates", mutableListOf(
                Node.Doc("patch.nca", 500_000_000L),
                Node.Dir("old", mutableListOf(Node.Doc("legacy.nsp", 1_000_000L)))
            )
        ),
        Node.Doc("loose.nro", 40_000_000L),
        Node.Doc("notes.txt", 1_000L)
    )
)

fun main() {
    println("\nfolder scan")

    check("the old lister reproduces the reported bug") {
        val tree = nestedLibrary()
        val (counted, _) = countGames(tree)
        val listed = listGamesTopLevelOnly(tree)
        assertEq(5, counted, "counter finds every ROM in the tree")
        assertEq(1, listed.size, "old lister only saw the top level")
        assertTrue(counted != listed.size, "this mismatch is the bug being fixed")
    }

    check("counter and lister now agree on a nested library") {
        val tree = nestedLibrary()
        val (counted, bytes) = countGames(tree)
        val listed = listGames(tree)
        assertEq(counted, listed.size, "count must equal what can be listed")
        assertEq(bytes, listed.sumOf { it.bytes }, "bytes must match too")
    }

    check("non-ROM files are ignored by both") {
        val tree = Node.Dir("g", mutableListOf(
            Node.Doc("readme.txt", 10L),
            Node.Doc("cover.jpg", 20L),
            Node.Doc("game.nsp", 30L)
        ))
        assertEq(1, countGames(tree).first, "counter")
        assertEq(1, listGames(tree).size, "lister")
    }

    check("extensions match case-insensitively") {
        val tree = Node.Dir("g", mutableListOf(
            Node.Doc("A.XCI", 1L), Node.Doc("b.NsP", 2L), Node.Doc("c.Nca", 3L)
        ))
        assertEq(3, countGames(tree).first, "upper and mixed case are still ROMs")
        assertEq(3, listGames(tree).size, "lister agrees")
    }

    check("each entry reports the subfolder it came from") {
        val listed = listGames(nestedLibrary()).associateBy { it.name }
        assertEq("Blade Chimera", listed["blade.nsp"]?.relativePath, "one level deep")
        assertEq("Updates/old", listed["legacy.nsp"]?.relativePath, "two levels deep")
        assertEq("", listed["loose.nro"]?.relativePath, "top level has no prefix")
    }

    check("entries are ordered largest first") {
        val listed = listGames(nestedLibrary())
        assertEq("mario.xci", listed.first().name, "biggest game leads")
        val sorted = listed.map { it.bytes }.sortedDescending()
        assertEq(sorted, listed.map { it.bytes }, "descending by size")
    }

    check("an empty folder yields zero, not a crash") {
        val tree = Node.Dir("empty")
        assertEq(0, countGames(tree).first, "count")
        assertTrue(listGames(tree).isEmpty(), "list")
    }

    check("a deep tree terminates instead of hanging") {
        // 500 nested directories, past the 400 guard.
        var leaf = Node.Dir("d500", mutableListOf(Node.Doc("deep.nsp", 1L)))
        for (i in 499 downTo 1) leaf = Node.Dir("d$i", mutableListOf(leaf))
        val (count, _) = countGames(leaf)
        assertTrue(count <= 1, "guard stops the walk without throwing")
    }

    check("sizes are summed, not counted") {
        val tree = Node.Dir("g", mutableListOf(
            Node.Doc("a.nsp", 1_073_741_824L),
            Node.Doc("b.nsp", 1_073_741_824L)
        ))
        assertEq(2_147_483_648L, countGames(tree).second, "two 1 GB files are 2 GB")
    }

    println("\n$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
