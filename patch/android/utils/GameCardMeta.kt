// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.yuzu.yuzu_emu.model.Game

/** Время в игре и последний скриншот — только с диска, без выдумки. */
object GameCardMeta {

    data class Meta(val playSeconds: Long, val playLabel: String, val shots: Int, val lastShot: String)

    fun forGame(game: Game): Meta {
        val tid = LivePanel.titleIdHex(game.programId)
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
        val seconds = if (root != null && tid.isNotEmpty()) readPlaySeconds(File(root, "play_time"), tid) else 0L
        val shots = if (root != null) findShots(File(root, "screenshots"), tid, game.title) else 0 to ""
        return Meta(seconds, formatPlay(seconds), shots.first, shots.second)
    }

    fun toJson(m: Meta): JSONObject = JSONObject()
        .put("playSeconds", m.playSeconds)
        .put("play", m.playLabel)
        .put("shots", m.shots)
        .put("lastShot", m.lastShot)

    internal fun parsePlayBytes(raw: ByteArray): Long {
        if (raw.isEmpty()) return 0
        val text = raw.toString(Charsets.UTF_8).trim()
        text.toLongOrNull()?.let { if (it >= 0) return it }
        if (raw.size >= 8) {
            val v = ByteBuffer.wrap(raw, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
            if (v in 1..3600L * 24 * 365 * 20) return v
        }
        if (raw.size >= 4) {
            val v = ByteBuffer.wrap(raw, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            if (v in 1..3600L * 24 * 365 * 20) return v
        }
        return 0
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

    private fun readPlaySeconds(dir: File, tid: String): Long {
        if (!dir.isDirectory) return 0
        val aliases = SaveSource.titleAliases(tid) + tid
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val n = f.name.substringBeforeLast('.').uppercase()
            if (aliases.any { it.equals(n, true) || n.contains(it) }) {
                val v = parsePlayBytes(runCatching { f.readBytes() }.getOrDefault(ByteArray(0)))
                if (v > 0) return v
            }
        }
        return 0
    }

    private fun findShots(dir: File, tid: String, title: String): Pair<Int, String> {
        if (!dir.isDirectory) return 0 to ""
        val want = buildList {
            add(tid.lowercase())
            addAll(SaveSource.titleAliases(tid).map { it.lowercase() })
            title.lowercase().takeIf { it.length >= 4 }?.let { add(it) }
        }
        val imgs = dir.walkTopDown().maxDepth(3).filter { f ->
            f.isFile && f.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp")
        }.toList()
        if (imgs.isEmpty()) return 0 to ""
        val matched = imgs.filter { f ->
            val n = f.name.lowercase()
            want.any { it.isNotBlank() && n.contains(it) }
        }
        val pool = matched.ifEmpty { imgs }
        val last = pool.maxByOrNull { it.lastModified() } ?: return 0 to ""
        return pool.size to last.absolutePath
    }
}
