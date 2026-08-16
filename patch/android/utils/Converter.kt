// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.yuzu.yuzu_emu.YuzuApplication

/**
 * NSZ/XCZ/NCZ → файл, который ядро умеет открыть.
 *
 * Вкладка «Конвертер»: после работы показывается обложка и либо
 * «Запустить», либо «Удалить». Половинчатый выход удаляется в native.
 */
object Converter {

    fun dir(): File = File(YuzuApplication.appContext.filesDir, "converter").also { it.mkdirs() }

    @Volatile var busy: Boolean = false
        private set
    @Volatile var queueNote: String = ""
        private set
    private val pending = java.util.ArrayDeque<Pair<android.net.Uri, String>>()
    private val done = java.util.ArrayList<org.json.JSONObject>()

    fun queueJson(): String = org.json.JSONObject().apply {
        put("busy", busy)
        put("note", queueNote)
        put("pending", pending.size)
        val arr = org.json.JSONArray()
        done.takeLast(12).forEach { arr.put(it) }
        put("done", arr)
    }.toString()

    fun listJson(): String {
        val arr = JSONArray()
        dir().listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach { f ->
            arr.put(probeFile(f))
        }
        return JSONObject().put("items", arr).put("path", dir().absolutePath).toString()
    }

    fun enqueue(context: Context, uris: List<Uri>): String {
        uris.forEach { uri ->
            pending.addLast(uri to displayName(context, uri))
        }
        queueNote = "в очереди ${pending.size} · не трогайте телефон"
        if (!busy) drain(context)
        return queueJson()
    }

    private fun drain(context: Context) {
        busy = true
        Thread({
            try {
                var i = 0
                val total = pending.size
                while (true) {
                    val item = synchronized(pending) {
                        if (pending.isEmpty()) null else pending.removeFirst()
                    } ?: break
                    i++
                    queueNote = "не трогайте телефон · $i/${i + pending.size} · ${item.second}"
                    val raw = importAndConvert(context, item.first)
                    val obj = runCatching { org.json.JSONObject(raw) }.getOrDefault(
                        org.json.JSONObject().put("ok", false).put("message", raw)
                    )
                    obj.put("source", item.second)
                    synchronized(done) { done.add(obj) }
                }
                queueNote = if (done.isEmpty()) "" else "готово · ${done.size}"
            } finally {
                busy = false
            }
        }, "convert-queue").start()
    }

    fun importAndConvert(context: Context, uri: Uri): String = runCatching {
        val name = displayName(context, uri).ifBlank { "dump.bin" }
        val incoming = File(dir(), "in-${System.currentTimeMillis()}-${safe(name)}")
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            incoming.outputStream().use { input.copyTo(it) }
            incoming.length() > 0
        } ?: false
        if (!copied) {
            incoming.delete()
            return@runCatching fail("не удалось прочитать файл", name)
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        val outName = when (ext) {
            "nsz" -> stem(name) + ".nsp"
            "xcz" -> stem(name) + ".nsp"
            "ncz" -> stem(name) + ".nca"
            "xci", "nsp", "nca", "nro" -> safe(name)
            else -> stem(name) + ".bin"
        }
        val dest = File(dir(), outName)
        val (bytes, error) = NativeSymbiosis.decompress(incoming.absolutePath, dest.absolutePath)
        incoming.delete()
        if (bytes <= 0L || !dest.isFile) {
            dest.delete()
            return@runCatching fail(error.ifBlank { "конвертация не вышла" }, name)
        }
        val card = probeFile(dest)
        card.put("ok", card.optBoolean("canLaunch"))
        card.put("source", name)
        if (!card.optBoolean("canLaunch")) {
            card.put("message", card.optString("reason").ifBlank { "файл есть, но открыть нельзя" })
        } else {
            card.put("message", "Поздравляю: можно открыть · ${card.optString("title")}")
        }
        card.toString()
    }.getOrElse {
        fail(it.message ?: "сбой конвертера", "?")
    }

    fun delete(path: String): String {
        val root = dir().canonicalPath
        val f = File(path)
        if (!f.exists()) return fail("уже нет", path)
        val ok = runCatching { f.canonicalPath.startsWith(root) && f.delete() }.getOrDefault(false)
        return JSONObject().put("ok", ok).put("path", path).toString()
    }

    fun canOpen(path: String): Boolean =
        runCatching { GameMetadata.getIsValid(path) }.getOrDefault(false)

    private fun probeFile(f: File): JSONObject {
        val path = f.absolutePath
        val launch = canOpen(path)
        val title = if (launch) {
            runCatching { GameMetadata.getTitle(path) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: f.name
        } else {
            f.name
        }
        val reason = if (launch) "" else runCatching {
            NativeSymbiosis.romProblem(path)
        }.getOrNull().orEmpty().ifBlank { "ядро не принимает этот файл" }
        return JSONObject().apply {
            put("ok", launch)
            put("canLaunch", launch)
            put("path", path)
            put("name", f.name)
            put("title", title)
            put("bytes", f.length())
            put("size", GameFolderScanner.humanSize(f.length()))
            put("reason", reason)
            put("message", if (launch) "можно открыть" else reason)
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val q = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        return q?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "dump.bin"
    }

    private fun safe(name: String): String =
        name.substringAfterLast('/').map { if (it.isLetterOrDigit() || it in "._-") it else '_' }
            .joinToString("").take(80).ifBlank { "dump.bin" }

    private fun stem(name: String): String {
        val n = safe(name)
        return n.substringBeforeLast('.').ifBlank { n }
    }

    private fun fail(reason: String, file: String): String =
        JSONObject().put("ok", false).put("canLaunch", false).put("message", reason)
            .put("file", file).put("reason", reason).toString()
}
