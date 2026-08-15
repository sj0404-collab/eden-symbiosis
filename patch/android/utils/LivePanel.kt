// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.os.Environment
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.yuzu.yuzu_emu.model.Game

/**
 * Данные для PWA-интерфейса. Страница живёт на GitHub Pages и в assets;
 * APK только читает диск и запускает игру.
 */
object LivePanel {

    const val BRIDGE_VERSION = 4

    private const val PANEL_URL = "https://sj0404-collab.github.io/eden-symbiosis/library.html"

    const val OFFLINE_URL = "file:///android_asset/library.html"

    // Без метки времени: иначе WebView перезагружает страницу каждую минуту
    // и список мигает пустым.
    fun panelUrl(): String = "$PANEL_URL?bridge=$BRIDGE_VERSION"

    fun statusJson(context: Context): String {
        val items = JSONArray()
        runCatching { SetupStatus.all(context) }.getOrDefault(emptyList()).forEach { item ->
            items.put(
                JSONObject().apply {
                    put("label", runCatching { context.getString(item.labelRes) }.getOrDefault("?"))
                    put("present", item.present)
                    put("detail", item.detail)
                    put("bytes", item.bytes ?: 0L)
                    put("size", item.bytes?.takeIf { it > 0 }
                        ?.let { GameFolderScanner.humanSize(it) } ?: "")
                }
            )
        }
        return JSONObject().apply {
            put("items", items)
            put("dataRoot", runCatching { SetupStatus.dataRoot() }.getOrDefault("—"))
        }.toString()
    }

    /** Из кэша и конфига. Диск не обходится. */
    fun foldersJson(context: Context): String {
        val arr = JSONArray()
        val dirs = runCatching { NativeConfig.getGameDirs().toList() }.getOrDefault(emptyList())
        val cached = rememberedGames()
        dirs.forEach { dir ->
            val name = GameFolderScanner.displayNameOf(dir.uriString)
            val prefix = GameFolderScanner.pathOf(dir.uriString)
            val inFolder = cached.filter {
                val p = GameFolderScanner.pathOf(it.path)
                p == prefix || p.startsWith("$prefix/")
            }
            // Размер — список файлов через SAF, без разбора ROM.
            val listed = runCatching {
                GameFolderScanner.listFilesFlat(context, dir.uriString)
            }.getOrDefault(emptyList())
            val bytes = listed.sumOf { it.bytes }
            arr.put(
                JSONObject().apply {
                    put("uri", dir.uriString)
                    put("name", name)
                    put("games", inFolder.size)
                    put("bytes", bytes)
                    put("size", if (bytes > 0) GameFolderScanner.humanSize(bytes) else "")
                    put("skipped", 0)
                    put("unreadable", false)
                }
            )
        }
        return JSONObject().put("folders", arr).toString()
    }

    fun gamesJson(): String {
        val arr = JSONArray()
        runCatching { GameHelper.cachedGameList }.getOrDefault(emptyList()).forEach { g ->
            val saveDir = runCatching { g.saveDir }.getOrNull().orEmpty()
            val hasSave = saveDir.isNotEmpty() && runCatching {
                val f = File(saveDir)
                f.exists() && (f.isFile || (f.listFiles()?.any { it.length() > 0 || (it.isDirectory && (it.list()?.isNotEmpty() == true)) } == true))
            }.getOrDefault(false)
            arr.put(
                JSONObject().apply {
                    put("title", g.title)
                    put("path", g.path)
                    put("programId", g.programId)
                    put("developer", g.developer)
                    put("saveDir", saveDir)
                    put("hasSave", hasSave)
                }
            )
        }
        return JSONObject().put("games", arr).toString()
    }

    /** Обложка JPEG 96px, base64. Пустая строка — нет иконки, не ошибка. */
    fun iconJpeg(path: String): String = runCatching {
        val raw = GameMetadata.getIcon(path)
        if (raw == null || raw.isEmpty()) return@runCatching ""
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching ""
        var sample = 1
        while (bounds.outWidth / sample > 128 || bounds.outHeight / sample > 128) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            ?: return@runCatching ""
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, out)
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrDefault("")

    fun filesJson(context: Context, uriString: String): String {
        val arr = JSONArray()
        runCatching { GameFolderScanner.listFilesFlat(context, uriString) }
            .getOrDefault(emptyList())
            .forEach { e ->
                arr.put(
                    JSONObject().apply {
                        put("name", e.name)
                        put("bytes", e.bytes)
                        put("size", GameFolderScanner.humanSize(e.bytes))
                        put("launchable", GameFolderScanner.isLaunchable(e.name))
                    }
                )
            }
        return JSONObject().put("files", arr).toString()
    }

    fun dataRootJson(context: Context): String {
        val path = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
        val load = path?.let { File(it, "load") }
        val saves = path?.let { File(it, "nand/user/save") }
        return JSONObject().apply {
            put("path", path ?: "")
            put("hasLoad", load?.isDirectory == true)
            put("hasSaves", saves?.isDirectory == true)
            put("loadCount", load?.listFiles()?.size ?: 0)
            put("savesCount", saves?.listFiles()?.size ?: 0)
        }.toString()
    }

    fun modsJson(): String {
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
        val load = root?.let { File(it, "load") }
        val obj = JSONObject()
        obj.put("path", load?.absolutePath ?: "")
        if (load == null || !load.isDirectory) {
            obj.put("emptyReason", "папки load/ нет в корне данных — выберите папку, где лежит официальный Eden (там keys, nand, load)")
            obj.put("items", JSONArray())
            return obj.toString()
        }
        val items = JSONArray()
        // Только папки TitleID, в которых реально лежат файлы.
        // Пустые каталоги от прошлых прогонов и мусор в load/ давали
        // «8 модов» при одном настоящем.
        load.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            if (!looksLikeTitleId(dir.name) && !hasModPayload(dir)) return@forEach
            if (!hasModPayload(dir)) return@forEach
            val kids = dir.listFiles()?.joinToString(", ") { it.name } ?: ""
            items.put(
                JSONObject().apply {
                    put("titleId", dir.name)
                    put("path", dir.absolutePath)
                    put("contents", kids.ifBlank { "пусто" })
                }
            )
        }
        obj.put("items", items)
        if (items.length() == 0) {
            obj.put("emptyReason", null)
        }
        return obj.toString()
    }

    fun savesJson(): String {
        val root = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
        val dir = root?.let { File(it, "nand/user/save") }
        val obj = JSONObject()
        obj.put("path", dir?.absolutePath ?: "")
        if (dir == null || !dir.isDirectory) {
            obj.put("emptyReason", "папки nand/user/save нет. Корень данных сейчас не тот, куда Eden писал сейвы.")
            obj.put("items", JSONArray())
            return obj.toString()
        }
        val items = JSONArray()
        dir.listFiles()?.filter { it.isDirectory }?.forEach { user ->
            if (user.name == "0000000000000000") return@forEach
            user.listFiles()?.filter { it.isDirectory }?.forEach { title ->
                if (!dirHasFiles(title)) return@forEach
                items.put(
                    JSONObject().apply {
                        put("name", title.name)
                        put("detail", "профиль ${user.name.take(8)}…")
                    }
                )
            }
        }
        obj.put("items", items)
        return obj.toString()
    }

    private fun looksLikeTitleId(name: String): Boolean =
        name.length == 16 && name.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun dirHasFiles(dir: File): Boolean {
        val q = ArrayDeque<File>()
        q.add(dir)
        var steps = 0
        while (q.isNotEmpty() && steps < 40) {
            steps++
            val cur = q.removeFirst()
            val kids = cur.listFiles() ?: continue
            for (k in kids) {
                if (k.isFile && k.length() > 0) return true
                if (k.isDirectory) q.add(k)
            }
        }
        return false
    }

    private fun hasModPayload(dir: File): Boolean = dirHasFiles(dir)

    /** Другие установки Eden на устройстве — чтобы не гадать, куда делись моды. */
    fun suggestRootsJson(context: Context): String {
        val current = runCatching { DirectoryInitialization.userDirectory }.getOrNull()
        val candidates = mutableListOf<Pair<String, String>>()
        val sd = Environment.getExternalStorageDirectory()
        candidates += "официальный Eden" to File(sd, "Android/data/dev.eden.eden_emulator/files").absolutePath
        candidates += "Eden legacy" to File(sd, "Android/data/dev.legacy.eden_emulator/files").absolutePath
        candidates += "Eden Debug" to File(sd, "Android/data/dev.legacy.eden_emulator.debug/files").absolutePath
        candidates += "/sdcard/Eden" to File(sd, "Eden").absolutePath
        candidates += "/sdcard/Eden/files" to File(sd, "Eden/files").absolutePath
        candidates += "/sdcard/Eden Debug" to File(sd, "Eden Debug").absolutePath
        runCatching { SharedDataDirectory.privatePath(context) }.getOrNull()?.let {
            candidates += "приватная этого APK" to it
        }

        val arr = JSONArray()
        val seen = HashSet<String>()
        for ((label, path) in candidates) {
            if (!seen.add(path)) continue
            if (path == current) continue
            val dir = File(path)
            if (!dir.isDirectory) continue
            val keys = File(dir, "keys/prod.keys").isFile
            val mods = File(dir, "load").listFiles()?.isNotEmpty() == true
            val saves = File(dir, "nand/user/save").listFiles()?.isNotEmpty() == true
            if (!keys && !mods && !saves) continue
            arr.put(
                JSONObject().apply {
                    put("label", label)
                    put("path", path)
                    put("keys", keys)
                    put("mods", mods)
                    put("saves", saves)
                }
            )
        }
        return JSONObject().put("roots", arr).toString()
    }

    fun gameFrom(path: String, title: String): Game = Game(
        title = title.ifBlank { path.substringAfterLast('/').ifBlank { "game" } },
        path = path
    )
}
