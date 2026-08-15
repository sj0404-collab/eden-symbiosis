// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import androidx.preference.PreferenceManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.model.Game

/**
 * Одна папка со всеми сейвами, выбранная пользователем.
 *
 * Как ключи и драйвер: указать один раз, дальше не трогать. Каждая игра
 * забирает из этой папки только свой TitleID. Корень данных (ключи,
 * прошивка, моды) при этом не меняется — иначе сейвы «нашлись» бы ценой
 * потерянных ключей.
 *
 * Игра читает только `<data root>/nand/user/save/<user>/<titleid>/`.
 * Поэтому при запуске подходящий сейв копируется туда, если в NAND ещё
 * пустые слоты (NEW GAME). Уже существующий настоящий сейв не затираем.
 */
object SaveSource {

    private const val PREF_PATH = "SymbiosisSavesDir"
    private const val MIN_SAVE_BYTES = 2048L
    private const val MAX_COPY_FILES = 200
    private const val MAX_COPY_BYTES = 64L * 1024L * 1024L

    private val prefs
        get() = PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    var configuredPath: String?
        get() = prefs.getString(PREF_PATH, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(PREF_PATH) else putString(PREF_PATH, value)
            }.apply()
        }

    /** Приводит выбор к каталогу, в котором лежат TitleID. */
    fun normalise(path: String): String {
        val chosen = File(path)
        val nested = listOf(
            "nand/user/save",
            "files/nand/user/save",
            "user/save",
            "save"
        )
        for (rel in nested) {
            val cand = File(chosen, rel)
            if (cand.isDirectory && looksLikeSaveTree(cand)) return cand.absolutePath
        }
        return path
    }

    fun looksLikeSaveTree(dir: File): Boolean {
        val kids = dir.listFiles() ?: return false
        return kids.any { it.isDirectory && (looksLikeTitleId(it.name) || looksLikeUserId(it.name)) }
    }

    fun looksLikeTitleId(name: String): Boolean =
        name.length == 16 && name.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun looksLikeUserId(name: String): Boolean =
        looksLikeTitleId(name) && name != "0000000000000000"

    data class Hit(val titleId: String, val path: String, val bytes: Long)

    /**
     * Ищет сейв TitleID в выбранной папке.
     *
     * Два обычных вида:
     *   `<root>/<titleid>/`
     *   `<root>/<userid>/<titleid>/`  (как nand/user/save)
     *
     * Сейв обновления (`…800`) лежит под базовым TitleID (`…000`).
     */
    fun findForTitle(titleId: String): Hit? {
        val root = configuredPath?.let { File(it) } ?: return null
        if (!root.isDirectory) return null
        val ids = titleAliases(titleId)
        // Прямые дети — TitleID.
        for (id in ids) {
            val direct = File(root, id)
            val b = fileBytes(direct)
            if (b >= MIN_SAVE_BYTES) return Hit(id, direct.absolutePath, b)
        }
        // Дети пользователей.
        root.listFiles()?.forEach { user ->
            if (!user.isDirectory || user.name == "0000000000000000") return@forEach
            for (id in ids) {
                val folder = File(user, id)
                val b = fileBytes(folder)
                if (b >= MIN_SAVE_BYTES) return Hit(id, folder.absolutePath, b)
            }
        }
        return null
    }

    fun titleAliases(titleId: String): List<String> {
        val hex = titleId.trim().uppercase()
        if (!looksLikeTitleId(hex)) return emptyList()
        val base = hex.substring(0, 13) + "000"
        return listOf(hex, base).distinct()
    }

    fun listHits(): List<Hit> {
        val root = configuredPath?.let { File(it) } ?: return emptyList()
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<Hit>()
        val seen = HashSet<String>()
        fun consider(id: String, dir: File) {
            val key = id.uppercase()
            if (!seen.add(key)) return
            val b = fileBytes(dir)
            if (b >= MIN_SAVE_BYTES) out.add(Hit(key, dir.absolutePath, b))
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

    /**
     * Кладёт сейв из выбранной папки в NAND этой установки, если там пусто.
     * Игра иначе рисует NEW GAME, даже когда файлы лежат рядом.
     */
    fun adoptFor(game: Game): String {
        val tid = LivePanel.titleIdHex(game.programId)
        if (tid.isEmpty()) return JSONObject().put("ok", false).put("reason", "нет TitleID").toString()
        return adoptForTitle(tid)
    }

    fun adoptForTitle(titleId: String): String {
        val src = findForTitle(titleId)
            ?: return JSONObject().put("ok", false).put("reason", "в выбранной папке нет сейва").toString()
        val nand = nandSaveRoot()
            ?: return JSONObject().put("ok", false).put("reason", "нет NAND").toString()
        val dest = nandSlot(nand, src.titleId)
        val already = fileBytes(dest)
        if (already >= MIN_SAVE_BYTES) {
            return JSONObject().apply {
                put("ok", true)
                put("copied", false)
                put("reason", "в NAND уже есть сейв")
                put("path", dest.absolutePath)
                put("bytes", already)
            }.toString()
        }
        val same = runCatching {
            dest.canonicalPath == File(src.path).canonicalPath
        }.getOrDefault(dest.absolutePath == src.path)
        if (same) {
            return JSONObject().put("ok", true).put("copied", false).put("path", dest.absolutePath).toString()
        }
        val copied = copyTree(File(src.path), dest)
        return JSONObject().apply {
            put("ok", copied.files > 0)
            put("copied", copied.files > 0)
            put("files", copied.files)
            put("bytes", copied.bytes)
            put("path", dest.absolutePath)
        }.toString()
    }

    private fun nandSaveRoot(): File? {
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull() ?: return null
        val dir = File(root, "nand/user/save")
        if (!dir.exists()) dir.mkdirs()
        return dir.takeIf { it.isDirectory }
    }

    private fun nandSlot(nand: File, titleId: String): File {
        val user = nand.listFiles()
            ?.firstOrNull { it.isDirectory && it.name != "0000000000000000" && looksLikeTitleId(it.name) }
            ?: File(nand, "0000000000000001").also { it.mkdirs() }
        return File(user, titleId)
    }

    data class CopyStat(val files: Int, val bytes: Long)

    internal fun copyTree(src: File, dst: File): CopyStat {
        if (!src.exists()) return CopyStat(0, 0)
        var files = 0
        var bytes = 0L
        val q = ArrayDeque<Pair<File, File>>()
        q.add(src to dst)
        var steps = 0
        while (q.isNotEmpty() && steps < MAX_COPY_FILES && bytes < MAX_COPY_BYTES) {
            steps++
            val (from, to) = q.removeFirst()
            if (from.isDirectory) {
                to.mkdirs()
                from.listFiles()?.forEach { kid ->
                    q.add(kid to File(to, kid.name))
                }
            } else if (from.isFile) {
                to.parentFile?.mkdirs()
                runCatching { from.copyTo(to, overwrite = true) }
                files++
                bytes += from.length()
            }
        }
        return CopyStat(files, bytes)
    }

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

    fun statusJson(): String {
        val path = configuredPath
        val hits = if (path != null) listHits() else emptyList()
        val bytes = hits.sumOf { it.bytes }
        return JSONObject().apply {
            put("path", path ?: "")
            put("name", path?.let { GameFolderScanner.displayNameOf(it) } ?: "")
            put("titles", hits.size)
            put("bytes", bytes)
            put("size", if (bytes > 0) GameFolderScanner.humanSize(bytes) else "")
            val arr = JSONArray()
            hits.forEach { h ->
                arr.put(
                    JSONObject().apply {
                        put("titleId", h.titleId)
                        put("path", h.path)
                        put("bytes", h.bytes)
                        put("size", GameFolderScanner.humanSize(h.bytes))
                    }
                )
            }
            put("items", arr)
        }.toString()
    }
}
