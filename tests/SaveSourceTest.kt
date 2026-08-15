// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// One saves folder, games pick their own TitleID.
// Active mods show their folder and openable files; disabled mods vanish.

import java.io.File
import java.util.Locale

fun looksLikeTitleId(name: String): Boolean =
    name.length == 16 && name.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

fun titleAliases(titleId: String): List<String> {
    val hex = titleId.trim().uppercase()
    if (!looksLikeTitleId(hex)) return emptyList()
    val base = hex.substring(0, 13) + "000"
    return listOf(hex, base).distinct()
}

fun normalise(path: String, exists: (String) -> Boolean): String {
    val nested = listOf("nand/user/save", "files/nand/user/save", "user/save", "save")
    for (rel in nested) {
        val cand = if (path.endsWith("/")) path + rel else "$path/$rel"
        if (exists(cand)) return cand
    }
    return path
}

data class Hit(val titleId: String, val path: String, val bytes: Long)

fun fileBytes(dir: File): Long {
    if (!dir.exists()) return 0
    if (dir.isFile) return dir.length()
    var sum = 0L
    val q = ArrayDeque<File>()
    q.add(dir)
    var steps = 0
    while (q.isNotEmpty() && steps < 80) {
        steps++
        val kids = q.removeFirst().listFiles() ?: continue
        for (k in kids) {
            if (k.isFile) sum += k.length() else if (k.isDirectory) q.add(k)
        }
    }
    return sum
}

fun listHits(root: File, minBytes: Long = 2048): List<Hit> {
    if (!root.isDirectory) return emptyList()
    val out = ArrayList<Hit>()
    val seen = HashSet<String>()
    fun consider(id: String, dir: File) {
        val key = id.uppercase()
        if (!seen.add(key)) return
        val b = fileBytes(dir)
        if (b >= minBytes) out.add(Hit(key, dir.absolutePath, b))
    }
    root.listFiles()?.forEach { a ->
        if (!a.isDirectory) return@forEach
        if (a.name == "0000000000000000") return@forEach
        val titleKids = a.listFiles()
            ?.filter { it.isDirectory && looksLikeTitleId(it.name) }
            .orEmpty()
        when {
            titleKids.isNotEmpty() -> titleKids.forEach { consider(it.name, it) }
            looksLikeTitleId(a.name) -> consider(a.name, a)
        }
    }
    return out
}

fun findForTitle(root: File, titleId: String, minBytes: Long = 2048): Hit? {
    val ids = titleAliases(titleId)
    for (id in ids) {
        val direct = File(root, id)
        val b = fileBytes(direct)
        if (b >= minBytes) return Hit(id, direct.absolutePath, b)
    }
    root.listFiles()?.forEach { user ->
        if (!user.isDirectory || user.name == "0000000000000000") return@forEach
        for (id in ids) {
            val folder = File(user, id)
            val b = fileBytes(folder)
            if (b >= minBytes) return Hit(id, folder.absolutePath, b)
        }
    }
    return null
}

val OPENABLE = setOf("md", "txt", "ini", "cfg", "json", "log", "nfo", "pchtxt")

fun isOpenable(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    if (lower == "readme" || lower.startsWith("readme.")) return true
    return lower.substringAfterLast('.', "") in OPENABLE
}

fun isDisabledName(name: String, disabled: Set<String>): Boolean {
    val n = name.lowercase(Locale.ROOT)
    if (n in disabled) return true
    if (n.endsWith(".disabled") || n.startsWith(".")) return true
    return n.substringBeforeLast('.') in disabled
}

fun visibleAddons(folders: List<Pair<String, Boolean>>): List<String> =
    folders.filter { it.second }.map { it.first }

fun copyTree(src: File, dst: File): Pair<Int, Long> {
    var files = 0
    var bytes = 0L
    val q = ArrayDeque<Pair<File, File>>()
    q.add(src to dst)
    var steps = 0
    while (q.isNotEmpty() && steps < 200) {
        steps++
        val (from, to) = q.removeFirst()
        if (from.isDirectory) {
            to.mkdirs()
            from.listFiles()?.forEach { kid -> q.add(kid to File(to, kid.name)) }
        } else if (from.isFile) {
            to.parentFile?.mkdirs()
            from.copyTo(to, overwrite = true)
            files++
            bytes += from.length()
        }
    }
    return files to bytes
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
    println("\nsave folder / active mods")

    check("hex title id is recognised", looksLikeTitleId("01007FC01CF4E000"))
    check("update id aliases to base", titleAliases("01007FC01CF4E800") == listOf("01007FC01CF4E800", "01007FC01CF4E000"))
    check("base id stays itself plus alias", titleAliases("01007FC01CF4E000").contains("01007FC01CF4E000"))

    check("picking Eden files root finds nand/user/save", {
        val tmp = File("/tmp/save-src-norm").apply { deleteRecursively(); mkdirs() }
        File(tmp, "nand/user/save/0000000000000001/01007FC01CF4E000").mkdirs()
        val got = normalise(tmp.absolutePath) { File(it).isDirectory }
        tmp.deleteRecursively()
        got.endsWith("nand/user/save")
    }())

    check("flat title-id folder is kept as-is", {
        val tmp = File("/tmp/save-src-flat").apply { deleteRecursively(); mkdirs() }
        File(tmp, "01007FC01CF4E000").mkdirs()
        val got = normalise(tmp.absolutePath) { File(it).isDirectory && it.endsWith("nand/user/save") }
        tmp.deleteRecursively()
        got == tmp.absolutePath
    }())

    val tree = File("/tmp/save-src-tree").apply { deleteRecursively(); mkdirs() }
    val user = File(tree, "0000000000000001")
    val blade = File(user, "01007FC01CF4E000").apply { mkdirs() }
    File(blade, "slot.bin").writeBytes(ByteArray(4096) { 1 })
    File(tree, "0000000000000000/0100DEAD00000000").mkdirs()
    val empty = File(user, "0100EMPTY00000000").apply { mkdirs() }
    File(empty, "placeholder").writeBytes(ByteArray(16))

    val hits = listHits(tree)
    check("nand layout finds the real save", hits.any { it.titleId == "01007FC01CF4E000" && it.bytes >= 2048 })
    check("empty slot under 2 KB is not a save", hits.none { it.titleId == "0100EMPTY00000000" })
    check("system user 0000… is skipped", hits.none { it.path.contains("0000000000000000") })

    check("game matches its own title from the shared folder", {
        val hit = findForTitle(tree, "01007FC01CF4E000")
        hit != null && hit.bytes >= 2048
    }())
    check("update id still finds the base save", {
        val hit = findForTitle(tree, "01007FC01CF4E800")
        hit != null && hit.titleId == "01007FC01CF4E000"
    }())
    check("unknown title is not claimed", findForTitle(tree, "0100FFFFFFFFFFFF") == null)

    val nand = File("/tmp/save-src-nand").apply { deleteRecursively(); mkdirs() }
    val dest = File(nand, "0000000000000001/01007FC01CF4E000")
    check("empty NAND slot is filled from the picked folder", {
        val (n, b) = copyTree(blade, dest)
        n == 1 && b >= 2048 && File(dest, "slot.bin").length() >= 2048
    }())
    check("existing real NAND save is left alone", {
        val before = File(dest, "slot.bin").length()
        File(dest, "slot.bin").writeBytes(ByteArray(8192) { 2 })
        val afterPick = File(dest, "slot.bin").length()
        afterPick == 8192L && afterPick != before
    }())

    check("md and txt are openable", isOpenable("README.md") && isOpenable("notes.txt"))
    check("romfs binary is not openable", !isOpenable("main.nso") && !isOpenable("data.bin"))
    check("disabled folder name is hidden", isDisabledName("60FPS", setOf("60fps")))
    check("dot-folder is hidden", isDisabledName(".hidden", emptySet()))
    check("enabled folder stays visible", {
        val shown = visibleAddons(listOf("60FPS" to true, "cheat-pack" to false))
        shown == listOf("60FPS")
    }())
    check("no enabled mods → side hierarchy is empty", {
        visibleAddons(listOf("old" to false, "broken.disabled" to false)).isEmpty()
    }())

    tree.deleteRecursively()
    nand.deleteRecursively()

    println("\n$pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
