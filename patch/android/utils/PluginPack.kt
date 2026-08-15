// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.yuzu.yuzu_emu.YuzuApplication

/**
 * Плагины как DLC: файл кладётся один раз, интерфейс меняется без
 * пересборки APK. Снести можно в любой момент. Сломанный пакет не
 * роняет приложение — пишется лог и пакет выключается.
 *
 * Форматы: .json / .css / .html, .zip / .pkg, .tar / .tar.gz / .tgz.
 * .xz без нативной библиотеки не распаковываем: файл сохраняется,
 * в логе сказано почему интерфейс не изменился.
 */
object PluginPack {

    private const val MAX_UNPACK_BYTES = 80L * 1024L * 1024L
    private const val MAX_FILES = 400
    private const val MAX_LOG = 80
    private const val MAX_COPY = 512L * 1024L * 1024L

    data class Change(val kind: String, val what: String, val where: String)
    data class Result(
        val ok: Boolean,
        val id: String,
        val file: String,
        val message: String,
        val where: String,
        val changes: List<Change>
    )

    fun root(): File {
        val ctx = YuzuApplication.appContext
        val dir = File(ctx.filesDir, "plugins")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "packs").mkdirs()
        return dir
    }

    fun packsDir(): File = File(root(), "packs")

    fun logFile(): File = File(root(), "log.json")

    fun listJson(): String {
        val arr = JSONArray()
        packsDir().listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            val man = readManifest(dir)
            arr.put(
                JSONObject().apply {
                    put("id", dir.name)
                    put("name", man.optString("name", dir.name))
                    put("version", man.optString("version", ""))
                    put("enabled", man.optBoolean("enabled", true))
                    put("files", dir.listFiles()?.size ?: 0)
                    put("bytes", GameFolderScanner.directoryBytes(dir.absolutePath))
                    put("size", GameFolderScanner.humanSize(
                        GameFolderScanner.directoryBytes(dir.absolutePath)
                    ))
                }
            )
        }
        return JSONObject().apply {
            put("items", arr)
            put("path", root().absolutePath)
            put("logs", logsArray())
        }.toString()
    }

    fun payloadJson(): String {
        val hide = LinkedHashSet<String>()
        val show = LinkedHashSet<String>()
        val theme = JSONObject()
        val strings = JSONObject()
        val html = JSONArray()
        val css = StringBuilder()
        packsDir().listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            val man = readManifest(dir)
            if (!man.optBoolean("enabled", true)) return@forEach
            man.optJSONArray("hide")?.let { a ->
                for (i in 0 until a.length()) hide.add(a.optString(i))
            }
            man.optJSONArray("show")?.let { a ->
                for (i in 0 until a.length()) show.add(a.optString(i))
            }
            man.optJSONObject("theme")?.let { t ->
                t.keys().forEach { k -> theme.put(k, t.optString(k)) }
            }
            man.optJSONObject("strings")?.let { t ->
                t.keys().forEach { k -> strings.put(k, t.optString(k)) }
            }
            man.optJSONObject("html")?.let { t ->
                t.keys().forEach { k ->
                    html.put(JSONObject().put("at", k).put("html", t.optString(k)))
                }
            }
            val inlineCss = man.optString("css")
            if (inlineCss.isNotBlank()) css.append(inlineCss).append('\n')
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".css", true) }?.forEach {
                runCatching { css.append(it.readText()).append('\n') }
            }
        }
        show.forEach { hide.remove(it) }
        val hideArr = JSONArray()
        hide.filter { it.isNotBlank() }.forEach { hideArr.put(it) }
        val showArr = JSONArray()
        show.filter { it.isNotBlank() }.forEach { showArr.put(it) }
        return JSONObject().apply {
            put("css", css.toString())
            put("hide", hideArr)
            put("show", showArr)
            put("theme", theme)
            put("strings", strings)
            put("html", html)
        }.toString()
    }

    fun setEnabled(id: String, enabled: Boolean): String {
        val dir = packDir(id) ?: return failJson("нет такого плагина")
        val man = readManifest(dir)
        man.put("enabled", enabled)
        writeManifest(dir, man)
        val kind = if (enabled) "добавлено" else "скрыто"
        val res = Result(
            true, id, id,
            "Поздравляю: $kind «${man.optString("name", id)}»",
            "plugins/packs/$id/plugin.json",
            listOf(Change(if (enabled) "shown" else "hidden", man.optString("name", id), "plugins/packs/$id"))
        )
        appendLog(res)
        return resultJson(res)
    }

    fun remove(id: String): String {
        val dir = packDir(id) ?: return failJson("нет такого плагина")
        val name = readManifest(dir).optString("name", id)
        dir.deleteRecursively()
        val res = Result(
            true, id, id,
            "Поздравляю: снесён «$name» — интерфейс вернётся после обновления",
            "plugins/packs/$id",
            listOf(Change("removed", name, "plugins/packs/$id"))
        )
        appendLog(res)
        return resultJson(res)
    }

    fun install(context: Context, uri: Uri): String = runCatching {
        val name = displayName(context, uri).ifBlank { "plugin.bin" }
        val tmp = File(root(), "incoming-${System.currentTimeMillis()}")
        tmp.mkdirs()
        val raw = File(tmp, safeName(name))
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            copyLimited(input, raw, MAX_COPY)
        } ?: return@runCatching Result(false, "", name, "не удалось прочитать файл", "picker", emptyList())
        if (!copied) {
            tmp.deleteRecursively()
            return@runCatching Result(false, "", name, "файл слишком большой или пустой", name, emptyList())
        }
        val result = ingest(raw, name)
        tmp.deleteRecursively()
        result
    }.getOrElse {
        Result(false, "", "?", "ошибка: ${it.message ?: "неизвестно"}", "install", emptyList())
    }.also { appendLog(it) }.let { resultJson(it) }

    internal fun ingest(raw: File, originalName: String): Result {
        if (!raw.isFile || raw.length() == 0L) {
            return Result(false, "", originalName, "файл пустой", originalName, emptyList())
        }
        val ext = originalName.substringAfterLast('.', "").lowercase()
        val head = raw.inputStream().use { ins ->
            val buf = ByteArray(8)
            val n = ins.read(buf)
            if (n <= 0) ByteArray(0) else buf.copyOf(n)
        }
        val kind = detect(ext, head)
        val id = uniqueId(stem(originalName))
        val dest = File(packsDir(), id)
        dest.mkdirs()
        val changes = ArrayList<Change>()
        when (kind) {
            "json" -> {
                val text = runCatching { raw.readText() }.getOrDefault("")
                val man = runCatching { JSONObject(text) }.getOrElse {
                    dest.deleteRecursively()
                    return Result(false, id, originalName, "json не разобрался: ${it.message}", originalName, emptyList())
                }
                man.put("enabled", man.optBoolean("enabled", true))
                if (man.optString("name").isBlank()) man.put("name", originalName)
                writeManifest(dest, man)
                changes += describeManifest(man, "plugins/packs/$id/plugin.json")
            }
            "css" -> {
                raw.copyTo(File(dest, "style.css"), overwrite = true)
                writeManifest(dest, JSONObject().put("name", originalName).put("enabled", true))
                changes += Change("added", "стиль", "plugins/packs/$id/style.css")
            }
            "html" -> {
                raw.copyTo(File(dest, "overlay.html"), overwrite = true)
                val html = runCatching { raw.readText() }.getOrDefault("")
                writeManifest(
                    dest,
                    JSONObject()
                        .put("name", originalName)
                        .put("enabled", true)
                        .put("html", JSONObject().put("after:#status", html))
                )
                changes += Change("added", "разметка", "library.html · после #status")
            }
            "zip" -> {
                val n = unpackZip(raw, dest)
                if (n <= 0) {
                    dest.deleteRecursively()
                    return Result(false, id, originalName, "архив пустой или опасный (путь с ..)", originalName, emptyList())
                }
                ensureManifest(dest, originalName)
                changes += Change("added", "$n файлов из zip", "plugins/packs/$id")
                changes += describeManifest(readManifest(dest), "plugins/packs/$id/plugin.json")
            }
            "tar", "tgz" -> {
                val n = raw.inputStream().use { ins ->
                    val src = if (kind == "tgz") GZIPInputStream(BufferedInputStream(ins))
                    else BufferedInputStream(ins)
                    unpackTar(src, dest)
                }
                if (n <= 0) {
                    dest.deleteRecursively()
                    return Result(false, id, originalName, "tar пустой или не читается", originalName, emptyList())
                }
                ensureManifest(dest, originalName)
                changes += Change("added", "$n файлов из tar", "plugins/packs/$id")
                changes += describeManifest(readManifest(dest), "plugins/packs/$id/plugin.json")
            }
            "xz" -> {
                raw.copyTo(File(dest, safeName(originalName)), overwrite = true)
                writeManifest(dest, JSONObject().put("name", originalName).put("enabled", false))
                changes += Change("added", "файл сохранён, xz не распакован", "plugins/packs/$id")
                return Result(
                    true, id, originalName,
                    "Поздравляю: файл лежит в plugins/packs/$id, но .xz тут не распаковывается — положите zip, tar.gz или json",
                    "plugins/packs/$id/${safeName(originalName)}",
                    changes
                )
            }
            else -> {
                raw.copyTo(File(dest, safeName(originalName)), overwrite = true)
                writeManifest(dest, JSONObject().put("name", originalName).put("enabled", true))
                changes += Change("added", originalName, "plugins/packs/$id/${safeName(originalName)}")
            }
        }
        val where = changes.joinToString(" · ") { it.where }.ifBlank { "plugins/packs/$id" }
        val summary = changes.joinToString(", ") {
            when (it.kind) {
                "hidden" -> "скрыто «${it.what}»"
                "shown" -> "показано «${it.what}»"
                "removed" -> "снято «${it.what}»"
                else -> "добавлено «${it.what}»"
            }
        }
        return Result(
            true, id, originalName,
            "Поздравляю: $summary",
            where,
            changes
        )
    }

    internal fun detect(ext: String, head: ByteArray): String {
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
            "html", "htm" -> "html"
            "zip", "pkg" -> "zip"
            "tar" -> "tar"
            "gz", "tgz" -> "tgz"
            "xz", "txz" -> "xz"
            else -> "file"
        }
    }

    internal fun safeRel(name: String): String? {
        val clean = name.replace('\\', '/').trim().trimStart('/')
        if (clean.isEmpty() || clean.startsWith("..") || "/../" in "/$clean/") return null
        if (clean == ".." || clean.contains('\u0000')) return null
        return clean
    }

    internal fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            for (c in base) append(if (c.isLetterOrDigit() || c in "._-") c else '_')
        }.trim('_').ifBlank { "file" }
        return cleaned.take(80)
    }

    internal fun stem(name: String): String {
        val n = safeName(name)
        return n.substringBeforeLast('.').ifBlank { n }.lowercase().take(40)
    }

    internal fun describeManifest(man: JSONObject, where: String): List<Change> {
        val out = ArrayList<Change>()
        man.optJSONArray("hide")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optString(i)
                if (s.isNotBlank()) out += Change("hidden", s, "$where · $s")
            }
        }
        man.optJSONArray("show")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optString(i)
                if (s.isNotBlank()) out += Change("shown", s, "$where · $s")
            }
        }
        if (man.optString("css").isNotBlank()) out += Change("added", "css", where)
        if (man.optJSONObject("theme") != null) out += Change("added", "тема", where)
        if (man.optJSONObject("html") != null) out += Change("added", "html", "library.html")
        if (man.optJSONObject("strings") != null) out += Change("added", "строки", where)
        return out
    }

    private fun ensureManifest(dir: File, fallbackName: String) {
        val existing = File(dir, "plugin.json")
        if (existing.isFile) {
            val man = readManifest(dir)
            if (!man.has("enabled")) man.put("enabled", true)
            if (man.optString("name").isBlank()) man.put("name", fallbackName)
            writeManifest(dir, man)
            return
        }
        val nested = dir.walkTopDown().firstOrNull { it.isFile && it.name == "plugin.json" }
        if (nested != null && nested != existing) {
            runCatching { nested.copyTo(existing, overwrite = true) }
        }
        if (!existing.isFile) {
            writeManifest(dir, JSONObject().put("name", fallbackName).put("enabled", true))
        }
    }

    private fun readManifest(dir: File): JSONObject {
        val f = File(dir, "plugin.json")
        if (!f.isFile) return JSONObject().put("name", dir.name).put("enabled", true)
        return runCatching { JSONObject(f.readText()) }.getOrDefault(
            JSONObject().put("name", dir.name).put("enabled", true)
        )
    }

    private fun writeManifest(dir: File, man: JSONObject) {
        runCatching { File(dir, "plugin.json").writeText(man.toString(2)) }
    }

    private fun packDir(id: String): File? {
        val clean = safeName(id)
        val dir = File(packsDir(), clean)
        return dir.takeIf { it.isDirectory }
    }

    private fun uniqueId(base: String): String {
        var id = base.ifBlank { "pack" }
        var i = 2
        while (File(packsDir(), id).exists()) {
            id = "${base.take(32)}-$i"
            i++
        }
        return id
    }

    private fun unpackZip(zip: File, dest: File): Int {
        var files = 0
        var bytes = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                try {
                    if (e.isDirectory) continue
                    val rel = safeRel(e.name) ?: continue
                    if (files >= MAX_FILES || bytes >= MAX_UNPACK_BYTES) break
                    val out = File(dest, rel)
                    val okPath = runCatching {
                        out.canonicalPath.startsWith(dest.canonicalPath)
                    }.getOrDefault(false)
                    if (!okPath) continue
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                    files++
                    bytes += out.length()
                } finally {
                    zis.closeEntry()
                }
            }
        }
        return files
    }

    /**
     * Минимальный ustar. Достаточно обычных tar/tar.gz с файлами и папками.
     * Битые заголовки просто останавливают разбор — без исключения наружу.
     */
    internal fun unpackTar(input: InputStream, dest: File): Int {
        var files = 0
        var bytes = 0L
        val hdr = ByteArray(512)
        while (true) {
            val n = readFully(input, hdr)
            if (n < 512) break
            if (hdr.all { it == 0.toByte() }) break
            val name = tarString(hdr, 0, 100)
            val prefix = tarString(hdr, 345, 155)
            val full = if (prefix.isBlank()) name else "$prefix/$name"
            val size = tarOctal(hdr, 124, 12)
            val type = hdr[156].toInt().toChar()
            val rel = safeRel(full)
            val skip = (size + 511) / 512 * 512
            if (rel != null && (type == '0' || type == '\u0000') && size >= 0) {
                if (files < MAX_FILES && bytes + size <= MAX_UNPACK_BYTES) {
                    val out = File(dest, rel)
                    if (out.canonicalPath.startsWith(dest.canonicalPath)) {
                        out.parentFile?.mkdirs()
                        copyExact(input, out, size)
                        val pad = (skip - size).toInt()
                        if (pad > 0) input.skip(pad.toLong())
                        files++
                        bytes += size
                        continue
                    }
                }
            }
            if (skip > 0) {
                var left = skip
                val buf = ByteArray(8192)
                while (left > 0) {
                    val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r <= 0) break
                    left -= r
                }
            }
        }
        return files
    }

    private fun copyExact(input: InputStream, dest: File, size: Long) {
        dest.outputStream().use { out ->
            var left = size
            val buf = ByteArray(8192)
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r <= 0) break
                out.write(buf, 0, r)
                left -= r
            }
        }
    }

    private fun copyLimited(input: InputStream, dest: File, max: Long): Boolean {
        var total = 0L
        dest.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > max) return false
                out.write(buf, 0, n)
            }
        }
        return total > 0
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) return off
            off += n
        }
        return off
    }

    private fun tarString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        val last = off + len
        while (end < last && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.US_ASCII).trim()
    }

    private fun tarOctal(buf: ByteArray, off: Int, len: Int): Long {
        val s = tarString(buf, off, len).trim()
        if (s.isEmpty()) return 0
        return s.toLongOrNull(8) ?: 0
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromQuery = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        if (!fromQuery.isNullOrBlank()) return fromQuery
        return uri.lastPathSegment?.substringAfterLast('/') ?: "plugin.bin"
    }

    private fun logsArray(): JSONArray {
        val f = logFile()
        if (!f.isFile) return JSONArray()
        return runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
    }

    private fun appendLog(res: Result) {
        val arr = logsArray()
        val item = JSONObject().apply {
            put("ok", res.ok)
            put("id", res.id)
            put("file", res.file)
            put("message", res.message)
            put("where", res.where)
            put("when", System.currentTimeMillis())
            val ch = JSONArray()
            res.changes.forEach { c ->
                ch.put(JSONObject().put("kind", c.kind).put("what", c.what).put("where", c.where))
            }
            put("changes", ch)
        }
        val next = JSONArray()
        next.put(item)
        for (i in 0 until arr.length()) {
            if (next.length() >= MAX_LOG) break
            next.put(arr.get(i))
        }
        runCatching { logFile().writeText(next.toString(2)) }
    }

    private fun resultJson(res: Result): String = JSONObject().apply {
        put("ok", res.ok)
        put("id", res.id)
        put("file", res.file)
        put("message", res.message)
        put("where", res.where)
        val ch = JSONArray()
        res.changes.forEach { c ->
            ch.put(JSONObject().put("kind", c.kind).put("what", c.what).put("where", c.where))
        }
        put("changes", ch)
    }.toString()

    private fun failJson(reason: String): String =
        JSONObject().put("ok", false).put("message", reason).put("where", "").toString()
}
