// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Живые тумблеры лаунчера. Только ключи BooleanSetting / IntSetting, которые
 * реально есть в Eden. Неизвестный ключ не создаётся и не рисуется как вкл.
 */
object LauncherSettings {

    data class Toggle(val key: String, val label: String, val hint: String)

    val BOOLS = listOf(
        Toggle("RENDERER_USE_DISK_SHADER_CACHE", "Диск-кэш шейдеров", "второй запуск берёт готовые пайплайны"),
        Toggle("RENDERER_ASYNCHRONOUS_SHADERS", "Async-шейдеры", "на Mali-G57 часто подвисает"),
        Toggle("USE_DOCKED_MODE", "Режим док", "больше пикселей и RAM — на 8 ГБ обычно хуже"),
        Toggle("RENDERER_USE_SPEED_LIMIT", "Лимит скорости", "не разгонять выше потолка"),
        Toggle("SHOW_PERFORMANCE_OVERLAY", "Оверлей FPS", "в игре"),
        Toggle("SHOW_FPS", "Показывать FPS", ""),
        Toggle("SHOW_APP_RAM_USAGE", "RAM приложения", "в оверлее"),
        Toggle("SHOW_INPUT_OVERLAY", "Оверлей кнопок", ""),
        Toggle("HAPTIC_FEEDBACK", "Виброотклик", ""),
        Toggle("PICTURE_IN_PICTURE", "Картинка в картинке", ""),
        Toggle("AUDIO_MUTED", "Без звука", ""),
        Toggle("TOUCHSCREEN", "Тач как тач", "")
    )

    val RES_LABELS = listOf(
        "0.25x", "0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x", "3x", "4x"
    )

    /** Из PWA можно ставить 0.25x…1x. Выше — только полные настройки Eden. */
    const val RES_MAX_SAFE = 3

    fun json(): String {
        val toggles = JSONArray()
        BOOLS.forEach { t ->
            val v = readBool(t.key)
            if (v == null) return@forEach
            toggles.put(
                JSONObject()
                    .put("key", t.key)
                    .put("label", t.label)
                    .put("hint", t.hint)
                    .put("on", v)
            )
        }
        val res = readInt("RENDERER_RESOLUTION")
        return JSONObject()
            .put("toggles", toggles)
            .put("resolution", res)
            .put("resolutionLabel", resLabel(res))
            .put("resolutionNote", resNote(res))
            .put("ok", toggles.length() > 0)
            .toString()
    }

    fun setBool(key: String, on: Boolean): String {
        if (BOOLS.none { it.key == key }) {
            return fail("ключа «$key» нет в списке лаунчера")
        }
        if (!writeBool(key, on)) return fail("настройка «$key» не записалась")
        runCatching { NativeConfig.saveGlobalConfig() }
        return JSONObject().put("ok", true).put("key", key).put("on", on)
            .put("message", (if (on) "вкл" else "выкл") + " · " + (BOOLS.first { it.key == key }.label))
            .toString()
    }

    fun setResolution(index: Int): String {
        if (index !in 0..RES_MAX_SAFE) {
            return fail("из лаунчера только 0.25x…1x; 1.25x и выше — в настройках Eden")
        }
        if (!writeInt("RENDERER_RESOLUTION", index)) return fail("масштаб не записался")
        runCatching { NativeConfig.saveGlobalConfig() }
        return JSONObject().put("ok", true).put("resolution", index)
            .put("resolutionLabel", resLabel(index))
            .put("message", "масштаб ${resLabel(index)}")
            .toString()
    }

    internal fun resLabel(index: Int): String =
        RES_LABELS.getOrNull(index) ?: if (index < 0) "" else "значение $index"

    internal fun resNote(index: Int): String = when {
        index < 0 -> "масштаб не прочитался"
        index <= 3 -> "для 8 ГБ Mali лучше не выше 1x"
        else -> "${resLabel(index)} — много пикселей для 8 ГБ, опустите или откройте настройки Eden"
    }

    internal fun allowedBool(key: String): Boolean = BOOLS.any { it.key == key }

    internal fun allowedRes(index: Int): Boolean = index in 0..RES_MAX_SAFE

    private fun readBool(name: String): Boolean? = runCatching {
        val cls = Class.forName("org.yuzu.yuzu_emu.features.settings.model.BooleanSetting")
        val v = (cls.enumConstants ?: emptyArray()).firstOrNull { (it as Enum<*>).name == name }
            ?: return@runCatching null
        val get = v.javaClass.methods.firstOrNull { it.name == "getBoolean" && it.parameterTypes.isEmpty() }
            ?: return@runCatching null
        get.invoke(v) as Boolean
    }.getOrNull()

    private fun writeBool(name: String, on: Boolean): Boolean = runCatching {
        val cls = Class.forName("org.yuzu.yuzu_emu.features.settings.model.BooleanSetting")
        val v = (cls.enumConstants ?: emptyArray()).firstOrNull { (it as Enum<*>).name == name }
            ?: return@runCatching false
        val set = v.javaClass.methods.firstOrNull { it.name == "setBoolean" && it.parameterTypes.size == 1 }
            ?: return@runCatching false
        set.invoke(v, on)
        true
    }.getOrDefault(false)

    private fun readInt(name: String): Int = runCatching {
        val cls = Class.forName("org.yuzu.yuzu_emu.features.settings.model.IntSetting")
        val v = (cls.enumConstants ?: emptyArray()).firstOrNull { (it as Enum<*>).name == name }
            ?: return@runCatching -1
        val get = v.javaClass.methods.firstOrNull { it.name == "getInt" && it.parameterTypes.isEmpty() }
            ?: return@runCatching -1
        get.invoke(v) as Int
    }.getOrDefault(-1)

    private fun writeInt(name: String, value: Int): Boolean = runCatching {
        val cls = Class.forName("org.yuzu.yuzu_emu.features.settings.model.IntSetting")
        val v = (cls.enumConstants ?: emptyArray()).firstOrNull { (it as Enum<*>).name == name }
            ?: return@runCatching false
        val set = v.javaClass.methods.firstOrNull { it.name == "setInt" && it.parameterTypes.size == 1 }
            ?: return@runCatching false
        set.invoke(v, value)
        true
    }.getOrDefault(false)

    private fun fail(m: String) = JSONObject().put("ok", false).put("message", m).toString()
}
