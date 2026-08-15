// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Plugins are DLC: add any file, see a log of what changed and where.
// A bad archive must not crash. Zip-slip paths are dropped.

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun detect(ext: String, head: ByteArray): String {
    if (head.size >= 2 && head[0] == 0x50.toByte() && head[1] == 0x4b.toByte()) return "zip"
    if (head.size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) return "tgz"
    if (head.size >= 6 &&
        head[0] == 0xfd.toByte() && head[1] == '7'.code.toByte() &&
        head[2] == 'z'.code.toByte() && head[3] == 'X'.code.toByte()
    ) return "xz"
    if (head.isNotEmpty() && (head[0] == '{'.code.toByte() || head[0] == '['.code.toByte())) return "json"
    return when (ext) {
        "json" -> "json"
        "css" -> "css"
        "zip", "pkg" -> "zip"
        "tar" -> "tar"
        "gz", "tgz" -> "tgz"
        "xz" -> "xz"
        else -> "file"
    }
}

fun safeRel(name: String): String? {
    val clean = name.replace('\\', '/').trim().trimStart('/')
    if (clean.isEmpty() || clean.startsWith("..") || "/../" in "/$clean/") return null
    if (clean == ".." || clean.contains('\u0000')) return null
    return clean
}

data class Change(val kind: String, val what: String, val where: String)

fun describe(hide: List<String>, css: Boolean): List<Change> {
    val out = ArrayList<Change>()
    hide.forEach { out += Change("hidden", it, "plugin.json · $it") }
    if (css) out += Change("added", "css", "plugin.json")
    return out
}

fun logLine(changes: List<Change>): Pair<String, String> {
    val summary = changes.joinToString(", ") {
        when (it.kind) {
            "hidden" -> "скрыто «${it.what}»"
            "shown" -> "показано «${it.what}»"
            "removed" -> "снято «${it.what}»"
            else -> "добавлено «${it.what}»"
        }
    }
    val where = changes.joinToString(" · ") { it.where }
    return "Поздравляю: $summary" to where
}

fun unpackZip(bytes: ByteArray, dest: File): Int {
    var files = 0
    java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
        while (true) {
            val e = zis.nextEntry ?: break
            if (e.isDirectory) continue
            val rel = safeRel(e.name) ?: continue
            val out = File(dest, rel)
            if (!out.canonicalPath.startsWith(dest.canonicalPath)) continue
            out.parentFile?.mkdirs()
            out.outputStream().use { zis.copyTo(it) }
            files++
            zis.closeEntry()
        }
    }
    return files
}

fun makeZip(entries: Map<String, String>): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zos ->
        for ((name, text) in entries) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(text.toByteArray())
            zos.closeEntry()
        }
    }
    return bos.toByteArray()
}

fun unpackTar(input: ByteArray, dest: File): Int {
    // reuse a tiny copy of the ustar loop for the host test
    val ins = ByteArrayInputStream(input)
    var files = 0
    val hdr = ByteArray(512)
    fun readFully(): Int {
        var off = 0
        while (off < 512) {
            val n = ins.read(hdr, off, 512 - off)
            if (n <= 0) return off
            off += n
        }
        return off
    }
    fun cstr(off: Int, len: Int): String {
        var end = off
        val last = off + len
        while (end < last && hdr[end] != 0.toByte()) end++
        return String(hdr, off, end - off, Charsets.US_ASCII).trim()
    }
    while (true) {
        if (readFully() < 512) break
        if (hdr.all { it == 0.toByte() }) break
        val name = cstr(0, 100)
        val sizeStr = cstr(124, 12)
        val size = sizeStr.toLongOrNull(8) ?: 0L
        val type = hdr[156].toInt().toChar()
        val rel = safeRel(name)
        val skip = (size + 511) / 512 * 512
        if (rel != null && (type == '0' || type == '\u0000') && size >= 0) {
            val out = File(dest, rel)
            out.parentFile?.mkdirs()
            val data = ByteArray(size.toInt())
            var off = 0
            while (off < data.size) {
                val n = ins.read(data, off, data.size - off)
                if (n <= 0) break
                off += n
            }
            out.writeBytes(data)
            val pad = (skip - size).toInt()
            if (pad > 0) ins.skip(pad.toLong())
            files++
            continue
        }
        if (skip > 0) ins.skip(skip)
    }
    return files
}

fun makeTar(name: String, text: String): ByteArray {
    val data = text.toByteArray()
    val hdr = ByteArray(512)
    name.toByteArray().copyInto(hdr, 0)
    val size = data.size.toString(8).padStart(11, '0') + '\u0000'
    size.toByteArray().copyInto(hdr, 124)
    hdr[156] = '0'.code.toByte()
    "ustar".toByteArray().copyInto(hdr, 257)
    // checksum
    for (i in 148 until 156) hdr[i] = ' '.code.toByte()
    var sum = 0
    for (b in hdr) sum += b.toInt() and 0xff
    val chk = sum.toString(8).padStart(6, '0') + '\u0000' + ' '
    chk.toByteArray().copyInto(hdr, 148)
    val pad = ByteArray(((data.size + 511) / 512 * 512) - data.size)
    return hdr + data + pad + ByteArray(1024)
}

var pass = 0
var fail = 0
fun check(name: String, cond: Boolean) {
    if (cond) { pass++; println("  ok  $name") } else { fail++; println("  FAIL $name") }
}

fun main() {
    println("\nplugin pack / dlc")

    check("zip magic", detect("bin", byteArrayOf(0x50, 0x4b, 0x03, 0x04)) == "zip")
    check("json magic", detect("txt", byteArrayOf('{'.code.toByte())) == "json")
    check("xz magic", detect("bin", byteArrayOf(0xfd.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0)) == "xz")
    check("pkg is zip by extension", detect("pkg", byteArrayOf(1, 2)) == "zip")
    check("unknown stays a file", detect("bin", byteArrayOf(0, 1, 2)) == "file")

    check("zip-slip .. is rejected", safeRel("../evil") == null)
    check("zip-slip nested .. is rejected", safeRel("a/../../x") == null)
    check("normal path is kept", safeRel("css/style.css") == "css/style.css")

    val ch = describe(listOf("#btn-tools"), true)
    val (msg, where) = logLine(ch)
    check("log congratulates", msg.startsWith("Поздравляю:"))
    check("log names the hidden control", msg.contains("скрыто «#btn-tools»"))
    check("log says where", where.contains("#btn-tools"))

    val dir = File("/tmp/plug-zip").apply { deleteRecursively(); mkdirs() }
    val zip = makeZip(mapOf(
        "plugin.json" to """{"name":"quiet","hide":["#btn-tools"]}""",
        "style.css" to "body{--pink:#fff}",
        "../escape.txt" to "nope"
    ))
    val n = unpackZip(zip, dir)
    check("zip unpacks safe files", n == 2)
    check("zip-slip file is not written", !File("/tmp/escape.txt").exists() && dir.walkTopDown().none { it.name == "escape.txt" })
    check("plugin.json landed", File(dir, "plugin.json").isFile)

    val tdir = File("/tmp/plug-tar").apply { deleteRecursively(); mkdirs() }
    val tn = unpackTar(makeTar("readme.txt", "hello"), tdir)
    check("tar unpacks a file", tn == 1 && File(tdir, "readme.txt").readText() == "hello")

    dir.deleteRecursively()
    tdir.deleteRecursively()
    println("\n$pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
