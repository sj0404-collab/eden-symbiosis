// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.yuzu.yuzu_emu.YuzuApplication

/**
 * Two workspaces that feel like two APKs in one process.
 *
 * WHY SPACES, NOT ANOTHER JNI PROBE
 *   Official Kenji 2.1.0-pr.2 already runs Blade Chimera on this phone.
 *   Our isolated probe crashing is a bug in our bridge, not a hardware
 *   verdict. Treating that crash as "ядро не запускается на этом устройстве"
 *   was a lie. A space is honest: same library, different shell, different
 *   core, and if their APK is installed we hand the game to it.
 *
 *   Symbiosis space is compiled in. Kenji space is a plugin shell (CSS +
 *   Kenji-like grid in library.html) plus either their installed APK or our
 *   :kenji player. The 55 MB .so is still a download, never baked into the
 *   APK.
 */
object Spaces {

    const val SYMBIOSIS = "symbiosis"
    const val KENJI = "kenji"
    const val PLUGIN_ID = "kenji-space"

    private const val PREFS = "symbiosis_spaces"
    private const val KEY_SPACE = "current"
    private const val KEY_EXTERNAL = "prefer_external"

    val OFFICIAL_PACKAGES = listOf(
        "org.kenjinx.android",
        "org.ryujinx.android",
        "org.ryujinx.kenjinx",
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(context: Context): String {
        val id = prefs(context).getString(KEY_SPACE, SYMBIOSIS) ?: SYMBIOSIS
        return if (id == KENJI) KENJI else SYMBIOSIS
    }

    fun preferExternal(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXTERNAL, true)

    fun setPreferExternal(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXTERNAL, on).apply()
    }

    data class Official(val installed: Boolean, val pkg: String, val label: String)

    fun official(context: Context): Official {
        val pm = context.packageManager
        for (pkg in OFFICIAL_PACKAGES) {
            val info = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
            }.getOrNull() ?: continue
            val label = runCatching {
                val ai = info.applicationInfo
                if (ai != null) pm.getApplicationLabel(ai).toString() else pkg
            }.getOrDefault(pkg)
            return Official(true, pkg, label.ifBlank { pkg })
        }
        return Official(false, "", "")
    }

    fun json(context: Context): String {
        val cur = current(context)
        val ext = official(context)
        val kenjiState = EngineLoader.state(context, EngineLoader.Engine.KENJI)
        val kenjiReady = kenjiState is EngineLoader.State.Ready
        val pluginOn = pluginInstalled() && pluginEnabled()
        val items = JSONArray()
        items.put(
            JSONObject()
                .put("id", SYMBIOSIS)
                .put("label", "Symbiosis")
                .put("shell", "карусель · розово-бирюзовая")
                .put("core", "eden")
                .put("coreLabel", "основное ядро")
                .put("builtin", true)
                .put("ready", true)
                .put("selected", cur == SYMBIOSIS)
        )
        val kenjiNote = when {
            ext.installed && kenjiReady ->
                "оболочка Kenji · их APK «${ext.label}» и наше ядро на месте"
            ext.installed ->
                "оболочка Kenji · их APK «${ext.label}» установлен — игры можно отдать ему"
            kenjiReady ->
                "оболочка Kenji · ядро скачано, их APK нет — наш плеер в :kenji"
            else ->
                "оболочка Kenji · скачайте ядро или поставьте их APK"
        }
        items.put(
            JSONObject()
                .put("id", KENJI)
                .put("label", "Kenji-NX")
                .put("shell", "сетка как у официального Kenji")
                .put("core", "kenji")
                .put("coreLabel", "второе ядро")
                .put("builtin", false)
                .put("ready", kenjiReady || ext.installed)
                .put("selected", cur == KENJI)
                .put("plugin", pluginOn)
                .put("note", kenjiNote)
        )
        return JSONObject()
            .put("current", cur)
            .put("preferExternal", preferExternal(context))
            .put("official", JSONObject()
                .put("installed", ext.installed)
                .put("package", ext.pkg)
                .put("label", ext.label))
            .put("kenjiCore", when (kenjiState) {
                is EngineLoader.State.Ready -> "ready"
                is EngineLoader.State.Missing -> "missing"
                is EngineLoader.State.Broken -> "broken"
                is EngineLoader.State.Builtin -> "builtin"
            })
            .put("plugin", pluginOn)
            .put("items", items)
            .put(
                "note",
                "два пространства, как два APK: одно встроено, второе — оболочка-плагин. " +
                    "Официальный Kenji на этом телефоне уже играет; падение нашей пробы — не приговор железу."
            )
            .toString()
    }

    fun select(context: Context, id: String): String {
        val space = if (id == KENJI) KENJI else SYMBIOSIS
        prefs(context).edit().putString(KEY_SPACE, space).apply()
        if (space == KENJI) {
            ensureKenjiPlugin(context)
            setPluginEnabled(true)
            val st = EngineLoader.state(context, EngineLoader.Engine.KENJI)
            if (st is EngineLoader.State.Ready) {
                EnginePreference.select(context, EngineLoader.Engine.KENJI)
            }
        } else {
            setPluginEnabled(false)
            EnginePreference.select(context, EngineLoader.Engine.EDEN)
        }
        return JSONObject()
            .put("ok", true)
            .put("id", space)
            .put("plugin", pluginInstalled())
            .put(
                "message",
                if (space == KENJI)
                    "пространство Kenji · оболочка как у них, ядро — скачанное или их APK"
                else
                    "пространство Symbiosis · основное ядро"
            )
            .toString()
    }

    fun pluginInstalled(): Boolean =
        File(PluginPack.packsDir(), PLUGIN_ID).let { it.isDirectory && File(it, "plugin.json").isFile }

    fun pluginEnabled(): Boolean {
        val dir = File(PluginPack.packsDir(), PLUGIN_ID)
        if (!dir.isDirectory) return false
        val man = runCatching { JSONObject(File(dir, "plugin.json").readText()) }
            .getOrDefault(JSONObject())
        return man.optBoolean("enabled", true)
    }

    private fun setPluginEnabled(on: Boolean) {
        if (!pluginInstalled()) return
        runCatching { PluginPack.setEnabled(PLUGIN_ID, on) }
    }

    /**
     * Drop the Kenji shell into the plugin directory from assets (or a
     * built-in fallback). Same path as «＋ Файл» in Плагины, so it shows up
     * in the list and can be removed.
     */
    fun ensureKenjiPlugin(context: Context): String {
        val dest = File(PluginPack.packsDir(), PLUGIN_ID)
        dest.mkdirs()
        val manFile = File(dest, "plugin.json")
        val cssFile = File(dest, "kenji.css")
        val json = readAsset(context, "kenji-space.json") ?: DEFAULT_MANIFEST
        val css = readAsset(context, "kenji-space.css") ?: DEFAULT_CSS
        runCatching { manFile.writeText(json) }
        runCatching { cssFile.writeText(css) }
        return JSONObject()
            .put("ok", manFile.isFile)
            .put("id", PLUGIN_ID)
            .put("where", dest.absolutePath)
            .put("message", "оболочка Kenji встроена как плагин «$PLUGIN_ID»")
            .toString()
    }

    fun openOfficial(context: Context, path: String): String {
        val ext = official(context)
        if (!ext.installed) {
            return JSONObject().put("ok", false)
                .put("message", "официальный Kenji не установлен — поставьте их APK или скачайте наше ядро")
                .toString()
        }
        val uri = runCatching {
            if (path.startsWith("/")) Uri.fromFile(File(path)) else Uri.parse(path)
        }.getOrNull()
        if (uri != null && path.isNotBlank()) {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/octet-stream")
                setPackage(ext.pkg)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            val granted = runCatching {
                context.grantUriPermission(
                    ext.pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.isSuccess
            val opened = runCatching { context.startActivity(view) }.isSuccess
            if (opened) {
                return JSONObject().put("ok", true).put("how", "view")
                    .put("package", ext.pkg)
                    .put("granted", granted)
                    .put("message", "открыл «${ext.label}» с этой игрой")
                    .toString()
            }
        }
        val launch = context.packageManager.getLaunchIntentForPackage(ext.pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { context.startActivity(launch) }.isSuccess
            if (opened) {
                return JSONObject().put("ok", true).put("how", "launcher")
                    .put("package", ext.pkg)
                    .put("message", "открыл «${ext.label}» — выберите игру у них")
                    .toString()
            }
        }
        return JSONObject().put("ok", false)
            .put("message", "не вышло открыть ${ext.pkg}")
            .toString()
    }

    fun shouldUseKenjiPlayer(context: Context): Boolean {
        if (current(context) != KENJI &&
            EnginePreference.selectedRaw(context) != EngineLoader.Engine.KENJI
        ) return false
        return EngineLoader.state(context, EngineLoader.Engine.KENJI) is EngineLoader.State.Ready
    }

    fun shouldHandOff(context: Context): Boolean =
        current(context) == KENJI && preferExternal(context) && official(context).installed

    private fun readAsset(context: Context, name: String): String? = runCatching {
        context.assets.open(name).bufferedReader().use { it.readText() }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private val DEFAULT_MANIFEST = """
        {
          "id": "kenji-space",
          "name": "Оболочка Kenji-NX",
          "version": "1",
          "enabled": true,
          "theme": {
            "--bg": "#121214",
            "--card": "#1c1c20",
            "--line": "#2a2a32",
            "--text": "#ececf4",
            "--dim": "#8b8b9c",
            "--pink": "#c45cff",
            "--cyan": "#9aa0ff",
            "--on": "#24242c"
          }
        }
    """.trimIndent()

    private val DEFAULT_CSS = """
        body.space-kenji { --bg:#121214; --card:#1c1c20; --pink:#c45cff; --cyan:#9aa0ff; }
        body.space-kenji header h1 { letter-spacing:.02em; }
    """.trimIndent()
}
