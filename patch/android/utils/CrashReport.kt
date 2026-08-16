// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.yuzu.yuzu_emu.YuzuApplication

/**
 * Собирает то, что реально осталось после вылета: лог слоя, файлы
 * log/ и crash_dumps, кусок logcat если система отдаёт.
 * Причину не выдумывает: нет строк — так и пишет.
 */
object CrashReport {

    fun buildJson(): String {
        val findings = runCatching { NativeSymbiosis.analyseCrash() }.getOrDefault("")
        val crashed = runCatching { NativeSymbiosis.previousSessionCrashed() }.getOrDefault(false)
        val layerLog = collectLayerFiles()
        val logcat = readLogcat()
        val blob = (findings + "\n" + layerLog.text + "\n" + logcat.text).lowercase()
        val kind = classify(blob)
        val report = File(YuzuApplication.appContext.filesDir, "last-crash.txt")
        val body = buildString {
            append("crashed=").append(crashed).append('\n')
            append("kind=").append(kind.id).append('\n')
            append(kind.title).append('\n')
            append(kind.detail).append('\n')
            append('\n').append("--- analyst ---\n").append(findings.ifBlank { "(пусто)" })
            append("\n\n--- files ").append(layerLog.where).append(" ---\n")
            append(layerLog.text.ifBlank { "(нет файлов лога)" })
            append("\n\n--- logcat ---\n")
            append(if (logcat.ok) logcat.text else logcat.text)
        }
        runCatching { report.writeText(body) }
        return JSONObject().apply {
            put("ok", true)
            put("crashed", crashed)
            put("kind", kind.id)
            put("title", kind.title)
            put("detail", kind.detail)
            put("analyst", findings)
            put("logFiles", layerLog.where)
            put("logcatOk", logcat.ok)
            put("path", report.absolutePath)
            put("excerpt", body.take(4000))
        }.toString()
    }

    data class Kind(val id: String, val title: String, val detail: String)

    internal fun classify(blob: String): Kind = when {
        blob.contains("device_lost") || blob.contains("vk_error_device_lost") ||
            blob.contains("fault #") || blob.contains("quarantin") ->
            Kind(
                "device_lost",
                "Драйвер GPU отвалился (device lost)",
                "В логе VK_ERROR_DEVICE_LOST или карантин драйвера. На Mali так бывает после тяжёлых шейдеров или выхода из игры."
            )
        blob.contains("out of memory") || blob.contains("outofmemory") ||
            blob.contains("lowmemorykiller") || blob.contains("lmk") ||
            blob.contains("reclaimed") && blob.contains("memory") ->
            Kind(
                "oom",
                "Не хватило памяти (OOM)",
                "Процесс убил lowmemorykiller или слой несколько раз отбирал кэши. Закройте фон или AAA минимум."
            )
        blob.contains("shader") && (blob.contains("fail") || blob.contains("error") ||
            blob.contains("compile") || blob.contains("pipeline")) ->
            Kind(
                "shader",
                "Сбой шейдера / pipeline",
                "В логе ошибка компиляции или pipeline. Диск-кэш шейдеров и повторный заход в то же меню."
            )
        blob.isBlank() || blob.contains("nothing in the log") ->
            Kind(
                "unknown",
                "Причина по логам не видна",
                "Это честный ответ, не заглушка: в логе нет DEVICE_LOST, OOM и shader. Откройте отчёт сразу после следующего вылета."
            )
        else ->
            Kind(
                "other",
                "В логе есть записи, но не OOM/device lost/шейдер",
                "Смотрите полный текст отчёта — там вырезка с диска."
            )
    }

    private data class Chunk(val text: String, val where: String = "", val ok: Boolean = true)

    private fun collectLayerFiles(): Chunk {
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
            ?: return Chunk("", "нет корня данных")
        val dirs = listOf(File(root, "log"), File(root, "crash_dumps"))
        val bits = StringBuilder()
        val names = ArrayList<String>()
        dirs.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.take(4)?.forEach { f ->
                if (!f.isFile || f.length() == 0L) return@forEach
                names += f.absolutePath
                bits.append("## ").append(f.name).append(" (").append(f.length()).append(")\n")
                bits.append(tailFile(f, 24_000)).append('\n')
            }
        }
        return Chunk(bits.toString().take(60_000), names.joinToString(", "))
    }

    private fun tailFile(f: File, max: Int): String = runCatching {
        val raw = f.readBytes()
        val slice = if (raw.size <= max) raw else raw.copyOfRange(raw.size - max, raw.size)
        String(slice, Charsets.UTF_8)
    }.getOrDefault("")

    private fun readLogcat(): Chunk {
        return runCatching {
            val p = ProcessBuilder("logcat", "-d", "-t", "180", "-v", "brief")
                .redirectErrorStream(true)
                .start()
            val text = p.inputStream.bufferedReader().readText().take(20_000)
            val code = p.waitFor()
            if (code != 0 && text.isBlank()) {
                Chunk("logcat код $code — приложению не дали читать буфер", "", false)
            } else {
                Chunk(text.ifBlank { "(пусто)" }, "logcat", true)
            }
        }.getOrElse {
            Chunk("logcat недоступен: ${it.message}", "", false)
        }
    }
}
