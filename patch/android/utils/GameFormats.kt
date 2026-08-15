// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import java.util.Locale

/**
 * Форматы, которые ядро реально открывает.
 *
 * `Game.extensions` в апстриме — только xci/nsp/nca/nro. Из‑за этого
 * сканер и список расходились, а XCI/NCA/NRO/NSO, которые loader.cpp
 * уже умеет, могли не попасть в библиотеку. Один набор на всех:
 * [GuessFromFilename] в loader.cpp.
 *
 * Сжатые nsz/xcz/ncz ядро не грузит — их показываем, но не запускаем.
 */
object GameFormats {

    /** loader.cpp GuessFromFilename: nro, nso, nca, xci, nsp, kip. */
    val LAUNCHABLE = setOf("xci", "nsp", "nca", "nro", "nso", "kip")

    /** Имя без расширения, которое loader считает образом. */
    val NAMES = setOf("main", "00")

    /** Контейнеры с обновлениями/DLC рядом с игрой. */
    val CONTAINERS = setOf("xci", "nsp", "nca")

    /** Сжатые дампы. Eden их не открывает. */
    val COMPRESSED = setOf("nsz", "xcz", "ncz")

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isLaunchable(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        if (lower in NAMES) return true
        return extensionOf(lower) in LAUNCHABLE
    }

    fun isCompressed(name: String): Boolean = extensionOf(name) in COMPRESSED

    fun isShowable(name: String): Boolean =
        isLaunchable(name) || isCompressed(name)
}
