// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.yuzu.yuzu_emu.model.Game

/**
 * Моды и читы одной игры.
 *
 * Включённый мод — папка видна сбоку карточки вместе с файлами, которые
 * человек может открыть (md, txt, ini…). Выключенный мод с диска не
 * удаляется: его папка просто пропадает из иерархии, как просили.
 *
 * «Включён» = есть файлы и имя нет в NativeConfig.getDisabledAddons.
 * Папка с маркером disabled / именем *.disabled тоже считается выключенной.
 */
object GameAddons {

    val OPENABLE = setOf(
        "md", "txt", "ini", "cfg", "json", "log", "nfo",
        "pchtxt", "xml", "html", "csv", "yml", "yaml", "readme"
    )

    private val SKIP_DIRS = setOf(
        "romfs", "exefs", "exefs_patches", "romfs_patches",
        "nso", "nca", "cache"
    )

    data class OpenFile(val name: String, val path: String, val bytes: Long)
    data class Addon(
        val name: String,
        val path: String,
        val kind: String,
        val enabled: Boolean,
        val files: List<OpenFile>
    )

    fun forGame(game: Game): List<Addon> = forTitle(LivePanel.titleIdHex(game.programId), game.programId)

    fun forTitle(titleIdHex: String, programId: String = titleIdHex): List<Addon> {
        if (titleIdHex.isEmpty()) return emptyList()
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull() ?: return emptyList()
        val load = File(root, "load")
        val ids = SaveSource.titleAliases(titleIdHex).ifEmpty { listOf(titleIdHex) }
        val disabled = disabledNames(programId, titleIdHex)
        val out = ArrayList<Addon>()
        for (id in ids) {
            val dir = File(load, id)
            if (!dir.isDirectory) continue
            dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { child ->
                if (!child.isDirectory) {
                    if (isOpenable(child.name) && child.isFile) {
                        // голые txt/md в корне TitleID — обычно чит или заметка
                        val enabled = !isDisabledName(child.name, disabled)
                        if (enabled) {
                            out.add(
                                Addon(
                                    name = child.name,
                                    path = child.absolutePath,
                                    kind = if (child.extension.lowercase() == "txt") "cheat" else "note",
                                    enabled = true,
                                    files = listOf(OpenFile(child.name, child.absolutePath, child.length()))
                                )
                            )
                        }
                    }
                    return@forEach
                }
                if (!hasPayload(child)) return@forEach
                val enabled = !isDisabledName(child.name, disabled) && !hasDisabledMarker(child)
                val kind = when {
                    child.name.equals("cheats", ignoreCase = true) -> "cheat"
                    child.listFiles()?.any { it.isDirectory && it.name.equals("cheats", true) } == true -> "cheat"
                    else -> "mod"
                }
                val files = if (enabled) listOpenable(child) else emptyList()
                out.add(Addon(child.name, child.absolutePath, kind, enabled, files))
            }
        }
        return out
    }

    /** Только то, что рисуем сбоку: включённые. */
    fun visibleFor(game: Game): List<Addon> = forGame(game).filter { it.enabled }

    fun disabledNames(programId: String, titleIdHex: String): Set<String> {
        val names = HashSet<String>()
        val cls = runCatching { Class.forName("org.yuzu.yuzu_emu.utils.NativeConfig") }.getOrNull()
            ?: return names
        val method = cls.methods.firstOrNull {
            it.name == "getDisabledAddons" && it.parameterTypes.size == 1
        } ?: return names
        for (key in listOf(programId, titleIdHex, titleIdHex.lowercase(), titleIdHex.uppercase())) {
            if (key.isBlank()) continue
            val arr = runCatching { method.invoke(null, key) as? Array<*> }.getOrNull() ?: continue
            arr.forEach { v ->
                val s = v?.toString()?.trim().orEmpty()
                if (s.isNotEmpty()) names.add(s.lowercase())
            }
        }
        return names
    }

    fun isDisabledName(name: String, disabled: Set<String>): Boolean {
        val n = name.lowercase()
        if (n in disabled) return true
        if (n.endsWith(".disabled") || n.startsWith(".")) return true
        val noExt = n.substringBeforeLast('.')
        return noExt in disabled
    }

    fun hasDisabledMarker(dir: File): Boolean {
        val marker = File(dir, "disabled")
        val dot = File(dir, ".disabled")
        return (marker.exists() && marker.length() == 0L) || dot.exists()
    }

    fun isOpenable(name: String): Boolean {
        val lower = name.lowercase()
        if (lower == "readme" || lower.startsWith("readme.")) return true
        val ext = lower.substringAfterLast('.', "")
        return ext in OPENABLE
    }

    fun hasPayload(dir: File): Boolean {
        val q = ArrayDeque<File>()
        q.add(dir)
        var steps = 0
        while (q.isNotEmpty() && steps < 40) {
            steps++
            val kids = q.removeFirst().listFiles() ?: continue
            for (k in kids) {
                if (k.isFile && k.length() > 0) return true
                if (k.isDirectory) q.add(k)
            }
        }
        return false
    }

    fun listOpenable(dir: File): List<OpenFile> {
        val out = ArrayList<OpenFile>()
        val q = ArrayDeque<File>()
        q.add(dir)
        var steps = 0
        while (q.isNotEmpty() && steps < 60 && out.size < 24) {
            steps++
            val cur = q.removeFirst()
            val kids = cur.listFiles() ?: continue
            for (k in kids) {
                if (k.isDirectory) {
                    if (k.name.lowercase() in SKIP_DIRS) continue
                    q.add(k)
                } else if (k.isFile && isOpenable(k.name) && k.length() > 0) {
                    out.add(OpenFile(k.name, k.absolutePath, k.length()))
                }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun readText(path: String): String {
        val f = File(path)
        if (!f.isFile) return JSONObject().put("ok", false).put("reason", "нет файла").toString()
        if (!isOpenable(f.name)) {
            return JSONObject().put("ok", false).put("reason", "этот тип не открываем").toString()
        }
        if (f.length() > 200_000) {
            return JSONObject().put("ok", false).put("reason", "файл больше 200 КБ").toString()
        }
        val text = runCatching { f.readText() }.getOrElse {
            return JSONObject().put("ok", false).put("reason", it.message ?: "не прочитался").toString()
        }
        return JSONObject().apply {
            put("ok", true)
            put("name", f.name)
            put("path", f.absolutePath)
            put("text", text)
        }.toString()
    }

    fun toJson(addons: List<Addon>): JSONArray {
        val arr = JSONArray()
        addons.forEach { a ->
            val files = JSONArray()
            a.files.forEach { f ->
                files.put(
                    JSONObject().apply {
                        put("name", f.name)
                        put("path", f.path)
                        put("bytes", f.bytes)
                        put("size", GameFolderScanner.humanSize(f.bytes))
                    }
                )
            }
            arr.put(
                JSONObject().apply {
                    put("name", a.name)
                    put("path", a.path)
                    put("kind", a.kind)
                    put("enabled", a.enabled)
                    put("files", files)
                }
            )
        }
        return arr
    }
}
