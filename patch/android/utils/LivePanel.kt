// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Данные для панели, которая живёт на GitHub Pages.
 *
 * Разделение простое: здесь всё, что знает про файлы и пути, а на странице
 * только то, как это выглядит. Значит внешний вид можно менять правкой
 * страницы, без новой сборки APK - а данные при этом читаются локально и
 * не зависят от сети.
 *
 * JSON собирается через org.json, а не склейкой строк: имя файла может
 * содержать кавычку или обратный слэш, и склейка молча даст сломанную
 * страницу.
 */
object LivePanel {

    /**
     * Версия моста.
     *
     * Страница обновляется чаще APK, поэтому она обязана уметь работать со
     * старой сборкой: спрашивает bridgeVersion() и не зовёт того, чего в
     * этой версии ещё нет. Увеличивать при КАЖДОМ добавлении метода.
     */
    const val BRIDGE_VERSION = 1

    /** Страница панели. Меняется правкой в docs/, APK не трогается. */
    private const val PANEL_URL = "https://sj0404-collab.github.io/eden-symbiosis/panel.html"

    /** Встроенная копия на случай отсутствия сети. */
    const val OFFLINE_URL = "file:///android_asset/panel_offline.html"

    /**
     * Адрес с версией моста в запросе.
     *
     * Так страница знает, с какой сборкой говорит, ещё до первого вызова -
     * и может сразу показать нужный вариант вместо того, чтобы гадать.
     */
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

    fun foldersJson(context: Context): String {
        val arr = JSONArray()
        runCatching { GameFolderScanner.scan(context) }.getOrDefault(emptyList()).forEach { f ->
            arr.put(
                JSONObject().apply {
                    put("uri", f.uriString)
                    put("name", f.displayName)
                    put("games", f.gameCount)
                    put("bytes", f.totalBytes)
                    put("size", GameFolderScanner.humanSize(f.totalBytes))
                    put("skipped", f.skipped)
                    put("unreadable", f.unreadable)
                }
            )
        }
        return JSONObject().put("folders", arr).toString()
    }

    /**
     * Файлы ровно в этой папке.
     *
     * Без захода в подпапки и без ограничения по количеству: вопрос "что у
     * меня в папке" не требует обхода дерева, а обход - это десятки
     * запросов к хранилищу и секунды ожидания.
     */
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
                        // Сжатые образы Eden не открывает. Показать и
                        // промолчать хуже, чем не показать: человек будет
                        // искать, почему игра не запускается.
                        put("launchable", GameFolderScanner.isLaunchable(e.name))
                    }
                )
            }
        return JSONObject().put("files", arr).toString()
    }
}
