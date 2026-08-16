// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import org.yuzu.yuzu_emu.YuzuApplication

/**
 * Именованные снимки живых BooleanSetting / IntSetting.
 * Пишет то, что реально стоит сейчас, и ставит обратно теми же сеттерами.
 */
object UserPresets {

    private const val PREF = "SymbiosisUserPresets"

    private val prefs
        get() = PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    fun listJson(): String {
        val arr = JSONArray()
        loadAll().forEach { p ->
            arr.put(JSONObject().put("name", p.optString("name")).put("keys", p.optJSONObject("values")?.length() ?: 0))
        }
        return JSONObject().put("items", arr).toString()
    }

    fun snapshot(name: String): String {
        val n = name.trim().ifBlank { return fail("пустое имя") }
        val values = dumpLive()
        if (values.length() == 0) return fail("не удалось прочитать настройки")
        val all = loadAll().filter { it.optString("name") != n }.toMutableList()
        all.add(0, JSONObject().put("name", n).put("values", values).put("when", System.currentTimeMillis()))
        saveAll(all)
        return JSONObject().put("ok", true).put("name", n).put("keys", values.length())
            .put("message", "Поздравляю: пресет «$n» · ${values.length()} ключей").toString()
    }

    fun apply(name: String): String {
        val p = loadAll().firstOrNull { it.optString("name") == name }
            ?: return fail("нет пресета «$name»")
        val values = p.optJSONObject("values") ?: return fail("пресет пустой")
        val n = applyLive(values)
        runCatching { NativeConfig.saveGlobalConfig() }
        return JSONObject().put("ok", n > 0).put("applied", n)
            .put("message", "Поставлен «$name» · $n настроек").toString()
    }

    fun remove(name: String): String {
        saveAll(loadAll().filter { it.optString("name") != name })
        return JSONObject().put("ok", true).put("message", "пресет «$name» снесён").toString()
    }

    internal fun dumpLive(): JSONObject {
        val o = JSONObject()
        walkEnum("org.yuzu.yuzu_emu.features.settings.model.BooleanSetting") { v ->
            val get = v.javaClass.methods.firstOrNull { it.name == "getBoolean" && it.parameterTypes.isEmpty() }
                ?: return@walkEnum
            val value = runCatching { get.invoke(v) as Boolean }.getOrNull() ?: return@walkEnum
            o.put((v as Enum<*>).name, value)
        }
        walkEnum("org.yuzu.yuzu_emu.features.settings.model.IntSetting") { v ->
            val get = v.javaClass.methods.firstOrNull { it.name == "getInt" && it.parameterTypes.isEmpty() }
                ?: return@walkEnum
            val value = runCatching { get.invoke(v) as Int }.getOrNull() ?: return@walkEnum
            o.put((v as Enum<*>).name, value)
        }
        return o
    }

    internal fun applyLive(values: JSONObject): Int {
        var n = 0
        walkEnum("org.yuzu.yuzu_emu.features.settings.model.BooleanSetting") { v ->
            val key = (v as Enum<*>).name
            if (!values.has(key)) return@walkEnum
            val set = v.javaClass.methods.firstOrNull { it.name == "setBoolean" && it.parameterTypes.size == 1 }
                ?: return@walkEnum
            runCatching { set.invoke(v, values.getBoolean(key)); n++ }
        }
        walkEnum("org.yuzu.yuzu_emu.features.settings.model.IntSetting") { v ->
            val key = (v as Enum<*>).name
            if (!values.has(key)) return@walkEnum
            val set = v.javaClass.methods.firstOrNull { it.name == "setInt" && it.parameterTypes.size == 1 }
                ?: return@walkEnum
            runCatching { set.invoke(v, values.getInt(key)); n++ }
        }
        return n
    }

    private fun walkEnum(cls: String, each: (Any) -> Unit) {
        val c = runCatching { Class.forName(cls) }.getOrNull() ?: return
        (c.enumConstants ?: emptyArray()).forEach { each(it) }
    }

    private fun loadAll(): List<JSONObject> {
        val raw = prefs.getString(PREF, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
    }

    private fun saveAll(list: List<JSONObject>) {
        val arr = JSONArray()
        list.take(20).forEach { arr.put(it) }
        prefs.edit().putString(PREF, arr.toString()).apply()
    }

    private fun fail(m: String) = JSONObject().put("ok", false).put("message", m).toString()
}
