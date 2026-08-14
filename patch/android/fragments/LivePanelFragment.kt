// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import org.yuzu.yuzu_emu.utils.LivePanel

/**
 * Панель, которая обновляется без пересборки APK.
 *
 * ЗАЧЕМ
 *   Каждая правка интерфейса означала новую сборку и новую установку -
 *   по 25 МБ и по двадцать минут ожидания за штуку. Содержимое этой
 *   панели живёт на GitHub Pages: правка страницы видна на телефоне
 *   сразу, APK при этом остаётся тем же.
 *
 * ЧТО ВНУТРИ, А ЧТО СНАРУЖИ
 *   Снаружи только разметка и тексты. Всё, что читает файлы, знает пути и
 *   считает размеры, остаётся в APK - страница спрашивает данные через
 *   [Bridge] и рисует их. Иначе панель зависела бы от сети, чтобы просто
 *   показать, сколько весит папка.
 *
 * ЕСЛИ СЕТИ НЕТ
 *   Показывается встроенная страница из assets с теми же данными. Панель
 *   не обязана быть онлайн, она обязана работать.
 *
 * БЕЗОПАСНОСТЬ
 *   Мост отдаёт только то, что панель и так показывает на экране: состояние
 *   установки, список папок, размеры. Ничего не пишет и ничего не запускает
 *   по просьбе страницы - страница не может ни удалить файл, ни поменять
 *   настройку. Загружается ровно один адрес, свой собственный.
 */
class LivePanelFragment : Fragment() {

    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = WebView(requireContext())
        web = view

        view.settings.javaScriptEnabled = true
        // Файлы с диска странице недоступны: она получает данные только
        // через мост, где решает наш код.
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.domStorageEnabled = true

        view.addJavascriptInterface(Bridge(), "Symbiosis")

        view.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                v: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                // Сеть отвалилась - показываем встроенную копию.
                if (request?.isForMainFrame == true) {
                    v?.loadUrl(LivePanel.OFFLINE_URL)
                }
            }
        }

        view.loadUrl(LivePanel.panelUrl())
        return view
    }

    override fun onDestroyView() {
        // WebView держит ссылку на активность; без этого фрагмент утекает.
        web?.apply {
            loadUrl("about:blank")
            destroy()
        }
        web = null
        super.onDestroyView()
    }

    /**
     * Что страница может спросить у приложения.
     *
     * Только чтение. Каждый метод возвращает JSON строкой - так проще
     * всего передать структуру через мост, и так же это делает панель на
     * компьютере.
     */
    inner class Bridge {

        /** Состояние установки: ключи, прошивка, драйвер, игры, сейвы, шейдеры. */
        @JavascriptInterface
        fun status(): String = runCatching {
            LivePanel.statusJson(requireContext())
        }.getOrDefault("""{"error":"недоступно"}""")

        /** Папки с играми: имя, число файлов, размер. */
        @JavascriptInterface
        fun folders(): String = runCatching {
            LivePanel.foldersJson(requireContext())
        }.getOrDefault("""{"folders":[]}""")

        /** Файлы внутри одной папки - без захода в подпапки. */
        @JavascriptInterface
        fun files(uriString: String): String = runCatching {
            LivePanel.filesJson(requireContext(), uriString)
        }.getOrDefault("""{"files":[]}""")

        /** Версия сборки, чтобы страница могла подстроиться под старый APK. */
        @JavascriptInterface
        fun bridgeVersion(): Int = LivePanel.BRIDGE_VERSION
    }
}
