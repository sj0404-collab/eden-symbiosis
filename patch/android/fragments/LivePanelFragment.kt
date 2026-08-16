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
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import org.json.JSONObject
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.model.GamesViewModel
import org.yuzu.yuzu_emu.ui.main.MainActivity
import org.yuzu.yuzu_emu.utils.Converter
import org.yuzu.yuzu_emu.utils.UserPresets
import org.yuzu.yuzu_emu.utils.CrashReport
import org.yuzu.yuzu_emu.utils.GameAddons
import org.yuzu.yuzu_emu.utils.LivePanel
import org.yuzu.yuzu_emu.utils.PluginPack
import org.yuzu.yuzu_emu.utils.SaveSource
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
            main.postDelayed({
                web?.evaluateJavascript("try{if(typeof onFolderAdded==='function')onFolderAdded()}catch(e){}", null)
            }, 800)
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

    private val pickSavesFolderLauncher =
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
                ?.let { SaveSource.normalise(it) }
            if (path.isNullOrBlank()) {
                main.post {
                    web?.evaluateJavascript(
                        "try{if(typeof onSavesPicked==='function')onSavesPicked(" +
                            JSONObject().put("ok", false)
                                .put("reason", "не удалось прочитать путь к папке")
                                .toString() +
                            ")}catch(e){}",
                        null
                    )
                }
                return@registerForActivityResult
            }
            SaveSource.configuredPath = path
            main.postDelayed({
                web?.evaluateJavascript(
                    "try{if(typeof onSavesPicked==='function')onSavesPicked(" +
                        SaveSource.statusJson() +
                        ")}catch(e){}",
                    null
                )
                reloadPageData()
            }, 200)
        }

    private val pickPluginFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val ctx = context?.applicationContext ?: return@registerForActivityResult
            Thread {
                val last = uris.map { uri ->
                    runCatching { PluginPack.install(ctx, uri) }.getOrDefault(
                        org.json.JSONObject().put("ok", false).put("message", "сбой установки").toString()
                    )
                }.lastOrNull() ?: "{}"
                main.post {
                    web?.evaluateJavascript(
                        "try{if(typeof onPluginsChanged==='function')onPluginsChanged(" +
                            last +
                            ")}catch(e){}",
                        null
                    )
                }
            }.start()
        }

    private val pickConvertFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val ctx = context?.applicationContext ?: return@registerForActivityResult
            Converter.enqueue(ctx, uris)
            fun tick() {
                web?.evaluateJavascript(
                    "try{if(typeof onConvertQueue==='function')onConvertQueue(" +
                        Converter.queueJson() + ")}catch(e){}",
                    null
                )
                if (Converter.busy) main.postDelayed({ tick() }, 700)
            }
            main.post { tick() }
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
        // Отступы делает сама страница (padding-top 48px). Двойной inset
        // прятал заголовок и кнопки под статус-бар на одних прошивках
        // и оставлял дыру на других.
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
        // Сразу assets: Pages без сети мигал пустым экраном.
        view.loadUrl(LivePanel.OFFLINE_URL)
        return view
    }

    override fun onResume() {
        super.onResume()
        // Не перерисовываем страницу: список уже на экране.
        // Перезагрузка после выхода из игры как раз обнуляла кэш в UI.
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
        fun pickSavesFolder() {
            main.post {
                runCatching {
                    pickSavesFolderLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
                }
            }
        }

        @JavascriptInterface
        fun saveSource(): String = runCatching { SaveSource.statusJson() }
            .getOrDefault("""{"path":"","titles":0}""")

        @JavascriptInterface
        fun clearSavesFolder() {
            SaveSource.configuredPath = null
        }

        @JavascriptInterface
        fun adoptSave(path: String, title: String): String = runCatching {
            val cached = org.yuzu.yuzu_emu.utils.GameHelper.cachedGameList
                .firstOrNull { it.path == path }
                ?: LivePanel.gameFrom(path, title)
            SaveSource.adoptFor(cached)
        }.getOrDefault("""{"ok":false}""")

        @JavascriptInterface
        fun readText(path: String): String = runCatching {
            GameAddons.readText(path)
        }.getOrDefault("""{"ok":false,"reason":"ошибка чтения"}""")

        @JavascriptInterface
        fun prepareShaders(): String = runCatching {
            LivePanel.prepareShaders()
        }.getOrDefault("""{"ok":false,"note":"недоступно"}""")

        @JavascriptInterface
        fun plugins(): String = runCatching { PluginPack.listJson() }
            .getOrDefault("""{"items":[],"logs":[]}""")

        @JavascriptInterface
        fun pluginPayload(): String = runCatching { PluginPack.payloadJson() }
            .getOrDefault("""{"css":"","hide":[],"html":[]}""")

        @JavascriptInterface
        fun pickPlugin() {
            main.post {
                runCatching { pickPluginFiles.launch(arrayOf("*/*")) }
            }
        }

        @JavascriptInterface
        fun removePlugin(id: String): String = runCatching {
            PluginPack.remove(id)
        }.getOrDefault("""{"ok":false,"message":"не удалился"}""")

        @JavascriptInterface
        fun enablePlugin(id: String, on: Boolean): String = runCatching {
            PluginPack.setEnabled(id, on)
        }.getOrDefault("""{"ok":false,"message":"не переключился"}""")

        @JavascriptInterface
        fun converterItems(): String = runCatching { Converter.listJson() }
            .getOrDefault("""{"items":[]}""")

        @JavascriptInterface
        fun pickConvert() {
            main.post {
                runCatching { pickConvertFiles.launch(arrayOf("*/*")) }
            }
        }

        @JavascriptInterface
        fun deleteConverted(path: String): String = runCatching {
            Converter.delete(path)
        }.getOrDefault("""{"ok":false}""")

        @JavascriptInterface
        fun canOpen(path: String): Boolean = runCatching {
            Converter.canOpen(path)
        }.getOrDefault(false)

        @JavascriptInterface
        fun applyAaaMode(): String = runCatching {
            val modes = org.yuzu.yuzu_emu.utils.NativeSymbiosis.autoModes()
            val m = modes.firstOrNull { it.key == "aaa" }
                ?: return@runCatching org.json.JSONObject()
                    .put("ok", false).put("message", "режим AAA нет в этой сборке").toString()
            val n = org.yuzu.yuzu_emu.utils.NativeSymbiosis.applyAutoMode(m.enumValue)
            runCatching { org.yuzu.yuzu_emu.utils.NativeConfig.saveGlobalConfig() }
            org.json.JSONObject().put("ok", n > 0).put("applied", n)
                .put("message",
                    "Поздравляю: AAA минимум · $n настроек. 0.25x, без лишней RAM. " +
                        "Не обещает, что открытый мир поедет.")
                .put("where", "настройки эмулятора · resolution 0.25x")
                .toString()
        }.getOrDefault("""{"ok":false,"message":"не применилось"}""")

        @JavascriptInterface
        fun crashReport(): String = runCatching { CrashReport.buildJson() }
            .getOrDefault("""{"ok":false,"title":"отчёт не собрался"}""")

        @JavascriptInterface
        fun memory(): String = runCatching { LivePanel.memoryJson() }
            .getOrDefault("""{"leftMb":0,"warn":false}""")

        @JavascriptInterface
        fun keysOk(): Boolean = LivePanel.keysPresent()

        @JavascriptInterface
        fun presets(): String = runCatching { UserPresets.listJson() }
            .getOrDefault("""{"items":[]}""")

        @JavascriptInterface
        fun savePreset(name: String): String = runCatching { UserPresets.snapshot(name) }
            .getOrDefault("""{"ok":false}""")

        @JavascriptInterface
        fun applyPreset(name: String): String = runCatching { UserPresets.apply(name) }
            .getOrDefault("""{"ok":false}""")

        @JavascriptInterface
        fun removePreset(name: String): String = runCatching { UserPresets.remove(name) }
            .getOrDefault("""{"ok":false}""")

        @JavascriptInterface
        fun convertQueue(): String = runCatching { Converter.queueJson() }
            .getOrDefault("""{"busy":false,"pending":0}""")

        @JavascriptInterface
        fun reloadInterface() {
            main.post {
                runCatching {
                    web?.loadUrl(LivePanel.panelUrl() + "&r=" + System.currentTimeMillis())
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
        fun icon(path: String): String = runCatching {
            LivePanel.iconJpeg(path)
        }.getOrDefault("")

        @JavascriptInterface
        fun openTools() {
            main.post {
                runCatching {
                    findNavController().navigate(org.yuzu.yuzu_emu.R.id.action_global_toolsFragment)
                }
            }
        }

        @JavascriptInterface
        fun openUtilities() {
            main.post {
                runCatching {
                    findNavController().navigate(org.yuzu.yuzu_emu.R.id.action_global_utilitiesFragment)
                }
            }
        }

        @JavascriptInterface
        fun openGameMenu(path: String) {
            if (path.isBlank()) return
            main.post {
                val cached = org.yuzu.yuzu_emu.utils.GameHelper.cachedGameList
                    .firstOrNull { it.path == path }
                    ?: LivePanel.gameFrom(path, "")
                runCatching {
                    findNavController().navigate(
                        HomeNavigationDirections.actionGlobalPerGamePropertiesFragment(cached)
                    )
                }
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
                // Сейв из выбранной папки кладём в NAND до запуска,
                // иначе игра рисует NEW GAME.
                runCatching { SaveSource.adoptFor(game) }
                // Тот же путь, что у списка игр: отдельная Activity + extra.
                // navigate() из WebView-фрагмента молча не открывал игру.
                val launched = runCatching {
                    val intent = android.content.Intent(
                        act,
                        org.yuzu.yuzu_emu.activities.EmulationActivity::class.java
                    ).apply {
                        action = android.content.Intent.ACTION_VIEW
                        data = if (game.path.startsWith("/"))
                            android.net.Uri.fromFile(java.io.File(game.path))
                        else
                            android.net.Uri.parse(game.path)
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
