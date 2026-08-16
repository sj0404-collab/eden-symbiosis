// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.model.Game

/**
 * Время в игре и последний скриншот — только с диска / из PlayTimeManager.
 * Eden пишет play_time/<user-uuid>.bin: записи по 16 байт (u64 titleId + u64 секунды, LE).
 * Отдельный файл с именем TitleID — редкий запасной формат, не основной.
 */
object GameCardMeta {

    data class Meta(val playSeconds: Long, val playLabel: String, val shots: Int, val lastShot: String)

    fun forGame(game: Game): Meta {
        val tid = LivePanel.titleIdHex(game.programId)
        val native = nativePlaySeconds(game.programId)
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
        val disk = if (root != null && tid.isNotEmpty()) {
            readPlaySeconds(File(root, "play_time"), tid)
        } else {
            0L
        }
        val seconds = maxOf(native, disk)
        val shots = if (root != null) findShots(File(root), tid, game.title) else 0 to ""
        return Meta(seconds, formatPlay(seconds), shots.first, shots.second)
    }

    fun toJson(m: Meta): JSONObject = JSONObject()
        .put("playSeconds", m.playSeconds)
        .put("play", m.playLabel)
        .put("shots", m.shots)
        .put("lastShot", m.lastShot)

    /** Native ждёт десятичный programId (как в Game.programId), не hex. */
    internal fun nativePlaySeconds(programId: String): Long {
        if (programId.isBlank()) return 0
        return runCatching {
            NativeLibrary.playTimeManagerGetPlayTime(programId).coerceAtLeast(0L)
        }.getOrDefault(0L)
    }

    internal fun parsePlayBytes(raw: ByteArray): Long {
        if (raw.isEmpty()) return 0
        val text = raw.toString(Charsets.UTF_8).trim()
        text.toLongOrNull()?.let { if (it >= 0) return it }
        if (raw.size >= 8) {
            val v = ByteBuffer.wrap(raw, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
            if (v in 1..MAX_PLAY) return v
        }
        if (raw.size >= 4) {
            val v = ByteBuffer.wrap(raw, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            if (v in 1..MAX_PLAY) return v
        }
        return 0
    }

    /** Основной формат Eden/yuzu: N записей [u64 programId][u64 seconds]. */
    internal fun secondsFromRecords(raw: ByteArray, wantIds: Set<Long>): Long {
        if (raw.size < 16 || wantIds.isEmpty()) return 0
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val n = raw.size / 16
        var best = 0L
        repeat(n) {
            val id = bb.long
            val sec = bb.long
            if (id in wantIds && sec in 1..MAX_PLAY && sec > best) best = sec
        }
        return best
    }

    internal fun titleIdsOf(tid: String): Set<Long> {
        val out = HashSet<Long>()
        (SaveSource.titleAliases(tid) + tid).forEach { alias ->
            alias.toULongOrNull(16)?.toLong()?.takeIf { it != 0L }?.let { out.add(it) }
        }
        return out
    }

    internal fun formatPlay(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> "${h} ч ${m} мин"
            m > 0 -> "${m} мин"
            else -> "${seconds} с"
        }
    }

    /**
     * Кадр после паузы, до stop(). Не трогает Vulkan после teardown.
     * Пишет screenshots/<titleid>/last.jpg — это и есть фото на карточке.
     */
    fun captureCurrentFrame(game: Game?): String {
        if (game == null) return ""
        val tid = LivePanel.titleIdHex(game.programId)
        if (tid.isEmpty()) return ""
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull() ?: return ""
        val data = runCatching { NativeLibrary.getAppletCaptureBuffer() }.getOrNull() ?: return ""
        val width = runCatching { NativeLibrary.getAppletCaptureWidth() }.getOrDefault(0)
        val height = runCatching { NativeLibrary.getAppletCaptureHeight() }.getOrDefault(0)
        if (data.isEmpty() || width <= 8 || height <= 8) return ""
        val expected = width * height * 4
        if (data.size < expected) return ""
        return runCatching {
            val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            src.copyPixelsFromBuffer(ByteBuffer.wrap(data, 0, expected))
            val scaled = scaleDown(src, 640)
            if (scaled !== src) src.recycle()
            val dir = File(File(root, "screenshots"), tid)
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, "last.jpg")
            dest.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            }
            scaled.recycle()
            dest.absolutePath
        }.getOrDefault("")
    }

    fun shotJpeg(path: String): String = runCatching {
        if (!allowedShot(path)) return@runCatching ""
        val file = File(path)
        if (!file.isFile || file.length() < 32L || file.length() > 8L * 1024 * 1024) {
            return@runCatching ""
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching ""
        var sample = 1
        while (bounds.outWidth / sample > 240 || bounds.outHeight / sample > 160) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: return@runCatching ""
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        bmp.recycle()
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrDefault("")

    private fun allowedShot(path: String): Boolean {
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull() ?: return false
        val canon = runCatching { File(path).canonicalPath }.getOrDefault("")
        if (canon.isEmpty()) return false
        val base = File(root).canonicalPath
        return canon.startsWith("$base/screenshots/") ||
            canon.startsWith("$base/dump/screenshots/")
    }

    private fun readPlaySeconds(dir: File, tid: String): Long {
        if (!dir.isDirectory) return 0
        val aliases = SaveSource.titleAliases(tid) + tid
        val want = titleIdsOf(tid)
        var best = 0L
        dir.listFiles()?.forEach { f ->
            if (!f.isFile || f.length() <= 0L || f.length() > 2L * 1024 * 1024) return@forEach
            val raw = runCatching { f.readBytes() }.getOrDefault(ByteArray(0))
            val rec = secondsFromRecords(raw, want)
            if (rec > best) best = rec
            val n = f.name.substringBeforeLast('.').uppercase()
            if (aliases.any { it.equals(n, true) || n.contains(it) }) {
                val v = parsePlayBytes(raw)
                if (v > best) best = v
            }
        }
        return best
    }

    private fun findShots(root: File, tid: String, title: String): Pair<Int, String> {
        val dirs = listOf(File(root, "screenshots"), File(root, "dump/screenshots"))
            .filter { it.isDirectory }
        if (dirs.isEmpty()) return 0 to ""
        val want = buildList {
            add(tid.lowercase())
            addAll(SaveSource.titleAliases(tid).map { it.lowercase() })
            title.lowercase().trim().takeIf { it.length >= 4 }?.let { add(it) }
        }.filter { it.isNotBlank() }
        if (want.isEmpty()) return 0 to ""
        val matched = ArrayList<File>()
        dirs.forEach { dir ->
            dir.walkTopDown().maxDepth(3).forEach { f ->
                if (!f.isFile) return@forEach
                if (f.extension.lowercase() !in IMAGE_EXT) return@forEach
                val hay = (f.name + " " + (f.parentFile?.name ?: "")).lowercase()
                if (want.any { hay.contains(it) }) matched.add(f)
            }
        }
        if (matched.isEmpty()) return 0 to ""
        val last = matched.maxByOrNull { it.lastModified() } ?: return 0 to ""
        return matched.size to last.absolutePath
    }

    private fun scaleDown(src: Bitmap, maxW: Int): Bitmap {
        if (src.width <= maxW) return src
        val h = (src.height.toLong() * maxW / src.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, maxW, h, true)
    }

    private const val MAX_PLAY = 3600L * 24 * 365 * 20
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp")
}
