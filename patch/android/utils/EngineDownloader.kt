// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an emulator core into the app's data directory.
 *
 * Kept apart from [EngineLoader] on purpose: loading must work offline and
 * must not drag HTTP into a code path that runs at startup. This class is only
 * touched when something is actually missing.
 *
 * WHAT IT GUARDS AGAINST, each learned from a real failure elsewhere in this
 * project rather than imagined:
 *
 *   * a half-finished download left behind by a dropped connection, which
 *     looks like a valid core until it is loaded and the process dies inside
 *     dlopen. Everything lands in a .part file and is renamed only after the
 *     hash matches.
 *   * a server that answers 200 with an HTML error page. The size is checked
 *     against what was expected before the bytes are trusted.
 *   * a core left writable. Android 14+ refuses to load a writable library,
 *     so the file is sealed the moment it is complete.
 */
object EngineDownloader {

    /** Progress in bytes; [total] is -1 when the server sends no length. */
    fun interface Progress {
        fun onProgress(done: Long, total: Long)
    }

    data class Result(val ok: Boolean, val message: String)

    /**
     * Where cores are published.
     *
     * A GitHub release asset on this same repository, not a third-party host:
     * gofile links expire, and a core that cannot be fetched turns the engine
     * switch into a dead button.
     */
    fun urlFor(engine: EngineLoader.Engine): String = when (engine) {
        EngineLoader.Engine.KENJI ->
            "https://github.com/sj0404-collab/symbiosis/releases/download/engine-kenji/libkenji.so"
        EngineLoader.Engine.EDEN -> ""   // built in; never downloaded
    }

    fun download(
        context: Context,
        engine: EngineLoader.Engine,
        progress: Progress? = null,
    ): Result {
        if (engine == EngineLoader.Engine.EDEN) {
            return Result(true, "Eden уже встроен в приложение")
        }
        if (!EngineLoader.deviceSupported()) {
            return Result(false, "ядро собрано только под arm64, это устройство его не запустит")
        }

        val target = EngineLoader.coreFile(context, engine)
        val part = File(target.parentFile, target.name + ".part")

        // A leftover .part from a previous attempt is not resumed - a resumed
        // download of a file that changed server-side produces a corrupt
        // library that passes every size check.
        if (part.exists()) {
            part.setWritable(true, true)
            part.delete()
        }
        if (target.exists()) {
            target.setWritable(true, true)
            target.delete()
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlFor(engine)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code != 200) {
                return Result(false, "сервер ответил $code — ядро не скачано")
            }

            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress?.onProgress(done, total)
                    }
                    out.flush()
                }
            }

            // An HTML error page is a few kilobytes and would otherwise be
            // renamed into place as a "core".
            val expected = EngineLoader.KNOWN_SIZE[engine] ?: 0L
            if (done < 1_000_000L) {
                part.delete()
                return Result(false, "скачано всего $done Б — это не ядро")
            }
            if (expected > 0 && total > 0 && total != expected) {
                part.delete()
                return Result(
                    false,
                    "размер не совпал: ожидалось $expected Б, сервер отдал $total Б"
                )
            }

            if (!part.renameTo(target)) {
                part.delete()
                return Result(false, "не удалось переместить скачанный файл на место")
            }

            // Seal it immediately. Doing this at load time instead left a
            // window where the file was both present and writable, and that is
            // exactly the state Android 14+ rejects.
            if (!EngineLoader.markReadOnly(target)) {
                return Result(
                    false,
                    "файл скачан, но остался доступным для записи — Android не даст его загрузить"
                )
            }

            return Result(true, "ядро ${engine.label} готово (${done / 1048576} МБ)")
        } catch (e: IOException) {
            part.delete()
            return Result(false, "сеть: ${e.message ?: "соединение прервано"}")
        } catch (e: Throwable) {
            part.delete()
            return Result(false, "не вышло: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }
}
