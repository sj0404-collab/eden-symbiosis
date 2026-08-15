// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.model.GamesViewModel
import org.yuzu.yuzu_emu.ui.main.MainActivity
import org.yuzu.yuzu_emu.utils.LivePanel
import org.yuzu.yuzu_emu.utils.SharedDataDirectory

/**
 * Весь интерфейс — страница. APK запускает игру.
 *
 * Краш при выходе из игры часто убивал список игр (RecyclerView + иконки +
 * скан). WebView остаётся живым, страница не пересобирается, диск не
 * читается. Правка HTML на GitHub Pages видна без новой сборки.
 */
class LivePanelFragment : Fragment() {

    private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private val gamesViewModel: GamesViewModel by activityViewModels()

    private val pickGamesFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            (activity as? MainActivity)?.processGamesDir(uri, true)
            main.postDelayed({ reloadPageData() }, 400)
        }

    private val pickDataFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = context ?: return@registerForActivityResult
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val path = SharedDataDirectory.resolveTreePath(uri)
                ?.let { SharedDataDirectory.normaliseRoot(it) }
                ?: return@registerForActivityResult
            SharedDataDirectory.configuredPath = path
            SharedDataDirectory.redirectNow(path)
            main.postDelayed({ reloadPageData() }, 400)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = WebView(requireContext())
        web = view
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = true
        view.settings.allowContentAccess = false
        view.addJavascriptInterface(Bridge(), "Symbiosis")
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        view.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                v: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    v?.loadUrl(LivePanel.OFFLINE_URL)
                }
            }
        }
        view.loadUrl(LivePanel.panelUrl())
        return view
    }

    override fun onResume() {
        super.onResume()
        // Вернулись из игры — страница уже на экране, скан не нужен.
        reloadPageData()
    }

    override fun onDestroyView() {
        web?.apply {
            removeJavascriptInterface("Symbiosis")
            loadUrl("about:blank")
            destroy()
        }
        web = null
        super.onDestroyView()
    }

    private fun reloadPageData() {
        web?.evaluateJavascript(
            "try{if(typeof loadGames==='function')loadGames();if(typeof loadStatus==='function')loadStatus();}catch(e){}",
            null
        )
    }

    inner class Bridge {

        @JavascriptInterface
        fun bridgeVersion(): Int = LivePanel.BRIDGE_VERSION

        @JavascriptInterface
        fun status(): String = runCatching {
            LivePanel.statusJson(requireContext().applicationContext)
        }.getOrDefault("""{"error":"недоступно"}""")

        @JavascriptInterface
        fun folders(): String = runCatching {
            LivePanel.foldersJson(requireContext().applicationContext)
        }.getOrDefault("""{"folders":[]}""")

        @JavascriptInterface
        fun games(): String = runCatching {
            LivePanel.gamesJson()
        }.getOrDefault("""{"games":[]}""")

        @JavascriptInterface
        fun files(uriString: String): String = runCatching {
            LivePanel.filesJson(requireContext().applicationContext, uriString)
        }.getOrDefault("""{"files":[]}""")

        @JavascriptInterface
        fun dataRoot(): String = runCatching {
            LivePanel.dataRootJson(requireContext().applicationContext)
        }.getOrDefault("""{"path":""}""")

        @JavascriptInterface
        fun mods(): String = runCatching { LivePanel.modsJson() }
            .getOrDefault("""{"items":[],"emptyReason":"ошибка чтения"}""")

        @JavascriptInterface
        fun saves(): String = runCatching { LivePanel.savesJson() }
            .getOrDefault("""{"items":[],"emptyReason":"ошибка чтения"}""")

        @JavascriptInterface
        fun suggestRoots(): String = runCatching {
            LivePanel.suggestRootsJson(requireContext().applicationContext)
        }.getOrDefault("""{"roots":[]}""")

        @JavascriptInterface
        fun pickFolder() {
            main.post {
                runCatching {
                    pickGamesFolder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
                }
            }
        }

        @JavascriptInterface
        fun pickDataRoot() {
            main.post {
                runCatching {
                    pickDataFolder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
                }
            }
        }

        @JavascriptInterface
        fun rescan() {
            main.post {
                runCatching { gamesViewModel.reloadGames(false) }
            }
        }

        @JavascriptInterface
        fun openSettings() {
            main.post {
                runCatching {
                    findNavController().navigate(
                        HomeNavigationDirections.actionGlobalSettingsActivity(
                            null,
                            org.yuzu.yuzu_emu.features.settings.model.Settings.MenuTag.SECTION_ROOT
                        )
                    )
                }
            }
        }

        @JavascriptInterface
        fun launch(path: String, title: String) {
            if (path.isBlank()) return
            main.post {
                val act = activity ?: return@post
                val cached = org.yuzu.yuzu_emu.utils.GameHelper.cachedGameList
                    .firstOrNull { it.path == path }
                val game = cached ?: LivePanel.gameFrom(path, title)
                // Тот же путь, что у списка игр: отдельная Activity + extra.
                // navigate() из WebView-фрагмента молча не открывал игру.
                val launched = runCatching {
                    val intent = android.content.Intent(
                        act,
                        org.yuzu.yuzu_emu.activities.EmulationActivity::class.java
                    ).apply {
                        action = android.content.Intent.ACTION_VIEW
                        data = android.net.Uri.parse(game.path)
                        putExtra("SelectedGame", game)
                        putExtra("game", game)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    act.startActivity(intent)
                }.isSuccess
                if (!launched) {
                    runCatching {
                        findNavController().navigate(
                            HomeNavigationDirections.actionGlobalEmulationActivity(game)
                        )
                    }
                }
            }
        }
    }
}
