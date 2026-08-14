// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import java.io.File
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R

/**
 * Answers "is this thing actually set up?" for the home screen strip.
 *
 * The setup wizard used to claim to answer this and did not: it showed "Done!"
 * whether or not anything had been installed, and said nothing at all once it
 * had been dismissed. Reading the real state on every visit is both simpler
 * and honest - a file either exists or it does not.
 *
 * Every check is a filesystem or native query, never a remembered flag. A
 * remembered flag is what made the app insist a folder was "already added"
 * after the user had removed it.
 */
object SetupStatus {

    data class Item(
        val labelRes: Int,
        val present: Boolean,
        /** Where it lives, or why it is missing. Shown under the label. */
        val detail: String,
        /**
         * Bytes this item occupies, or null when size is meaningless for it
         * (the driver is not a file this build owns).
         *
         * Kept separate from [detail] so the strip can show "Прошивка ✓ 312 MB"
         * without the caller parsing a sentence back apart.
         */
        val bytes: Long? = null
    )

    /** Keys, firmware, driver, games, saves, shader cache - in the order they matter. */
    fun all(context: Context): List<Item> = listOf(
        keys(), firmware(), driver(), games(context), saves(), shaderCache()
    )

    private fun root(): String? = runCatching {
        DirectoryInitialization.userDirectory
    }.getOrNull()

    fun keys(): Item {
        val dir = root()?.let { "$it/keys" }
        val prod = dir?.let { File(it, "prod.keys") }
        val loaded = runCatching { NativeLibrary.areKeysPresent() }.getOrDefault(false)
        // The file existing and the keys being usable are different things: a
        // truncated or wrong-firmware prod.keys is present but useless, and
        // saying "yes" to it sends the user hunting for the wrong problem.
        val onDisk = prod?.isFile == true && (prod.length() > 0)
        return Item(
            labelRes = R.string.status_keys,
            present = onDisk && loaded,
            detail = when {
                !onDisk -> "prod.keys — нет"
                !loaded -> "prod.keys есть, но не читается"
                else -> dir ?: ""
            },
            bytes = if (onDisk) GameFolderScanner.directoryBytes(dir) else null
        )
    }

    fun firmware(): Item {
        val dir = root()?.let { "$it/nand/system/Contents/registered" }
        val count = dir?.let { File(it).listFiles()?.size } ?: 0
        val available = runCatching { NativeLibrary.isFirmwareAvailable() }.getOrDefault(false)
        return Item(
            labelRes = R.string.status_firmware,
            present = available && count > 0,
            detail = if (count > 0) "$count файлов · ${dir ?: ""}" else "не установлена",
            bytes = if (count > 0) GameFolderScanner.directoryBytes(dir) else null
        )
    }

    /**
     * Which Vulkan driver is in use.
     *
     * Never "missing": a device always has the vendor driver. The useful
     * distinction is system versus a user-supplied one, since on Mali a
     * replacement is usually impossible and saying so avoids a pointless hunt.
     */
    fun driver(): Item {
        // installedCustomDriverData parses a JSON file that is absent unless a
        // driver was installed, so both the call and the field can fail.
        val custom = runCatching {
            GpuDriverHelper.installedCustomDriverData.name
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return Item(
            labelRes = R.string.status_driver,
            present = true,
            detail = custom ?: "системный"
        )
    }

    fun games(context: Context): Item {
        val dirs = runCatching { NativeConfig.getGameDirs() }.getOrNull() ?: emptyArray()
        if (dirs.isEmpty()) {
            return Item(R.string.status_games, false, "папка не выбрана")
        }
        // Count what is really on disk now rather than trusting the library
        // cache, which is what made a deleted game keep appearing. One scan
        // yields both the count and the bytes, so the strip and the folder
        // screen cannot report different numbers.
        // Один обход на папку, не два.
        //
        // Раньше здесь звались и listGames, и scanOneFolder - два полных
        // обхода одного дерева ради чисел, которые первый же обход и
        // считает. На папке с играми это удвоенное время и удвоенное
        // число курсоров, а строка состояния обновляется на каждом
        // onResume. scanOneFolder возвращает и количество, и байты, и
        // число пропущенных файлов сразу.
        var games = 0
        var bytes = 0L
        var skipped = 0
        for (dir in dirs) {
            val folder = runCatching {
                // Same depth the library importer uses for this folder, so the
                // strip cannot claim a game the game list will not show.
                GameFolderScanner.scanOneFolder(context, dir.uriString, dir.deepScan)
            }.getOrNull() ?: continue
            games += folder.gameCount
            bytes += folder.totalBytes
            skipped += folder.skipped
        }
        val name = GameFolderScanner.displayNameOf(dirs.first().uriString)

        // A count of files is not a count of games. The library also refuses a
        // ROM whose header will not parse - no keys, wrong firmware, truncated
        // download - and the emulator's own list is the authority on that.
        // Comparing against it turns "I see a file but no game" from a mystery
        // into a sentence.
        val imported = runCatching { GameHelper.cachedGameList.size }.getOrDefault(-1)

        // Storage access is checked before anything else is blamed. The
        // document provider happily lists and counts a folder without it,
        // while the emulator - which opens the file directly - sees nothing.
        // That combination produced a game with a name and no image behind it,
        // and pointed the user at keys and firmware, which were fine.
        val noAccess = SharedDataDirectory.needsAllFilesAccess() &&
            !SharedDataDirectory.hasAllFilesAccess()

        val detail = when {
            noAccess ->
                "нет доступа к файлам — нажми сюда, чтобы выдать «Все файлы»"
            games == 0 && skipped > 0 ->
                "файлы есть ($skipped), но это не игры — нужны .xci .nsp .nca .nro"
            games == 0 ->
                "в «$name» нет файлов игр"
            // Назвать файлы поимённо и сказать про каждый, почему он не
            // подошёл. "Проверь ключи и прошивку" при зелёных галочках на
            // ключах и прошивке - это тупик: человек идёт перепроверять
            // заведомо исправное. Имя файла и его размер говорят больше.
            imported in 0 until games ->
                "$games файлов, распознано $imported. " + whyNotGames(context)
            dirs.size == 1 -> "$games в «$name»"
            else -> "$games в ${dirs.size} папках"
        }
        return Item(
            labelRes = R.string.status_games,
            // Green only when the emulator agrees it has something to launch,
            // and only when it is actually allowed to read the files.
            present = games > 0 && imported != 0 && !noAccess,
            detail = detail,
            bytes = bytes
        )
    }

    /** Save data. Grows quietly and is the thing worth backing up. */
    /**
     * Почему найденные файлы не стали играми.
     *
     * Смотрит на сами файлы, а не на настройки: расширение и размер. Это
     * ровно те две вещи, по которым видно самые частые случаи - сжатый
     * образ, который Eden не открывает, и обновление или DLC, положенное
     * вместо игры.
     */
    private fun whyNotGames(context: Context): String {
        val dirs = runCatching { NativeConfig.getGameDirs() }.getOrNull() ?: return ""
        val out = mutableListOf<String>()
        var rest = 0

        for (dir in dirs) {
            val files = runCatching { GameFolderScanner.listFilesFlat(context, dir.uriString) }
                .getOrDefault(emptyList())

            // Не больше нескольких файлов за раз.
            //
            // diagnoseRom() открывает файл, расшифровывает заголовок и
            // строит загрузчик - это полноценная попытка загрузки, от
            // десятков миллисекунд до секунды на файл, и каждая держит свой
            // кусок памяти. На папке с сотней образов строка состояния
            // уходила в это на минуты, а зовут её из onResume. Восьми имён
            // хватает, чтобы понять картину; остальные считаются скопом.
            if (files.size > MAX_DIAGNOSED) {
                rest += files.size - MAX_DIAGNOSED
            }

            for (e in files.take(MAX_DIAGNOSED)) {
                // Спросить у самого эмулятора, а не гадать.
                //
                // diagnoseRom() проходит ровно тот же путь, что и запуск
                // игры, и возвращает НАСТОЯЩУЮ причину отказа: нет title
                // key, повреждён файл, внутри только обновление, не тот
                // prod.keys. Догадки по размеру уже привели к ошибке -
                // 283 МБ настоящей игры были объявлены "мало для игры,
                // похоже на DLC".
                val uri = GameFolderScanner.childUri(dir.uriString, e)
                val fromCore = uri?.let {
                    runCatching { NativeSymbiosis.romProblem(it.toString()) }.getOrNull()
                }
                if (fromCore != null) {
                    out.add("«${e.name}» — $fromCore")
                }
            }
        }

        if (out.isEmpty()) return "все файлы читаются — открой список игр"
        val tail = if (rest > 0) " (и ещё $rest файлов)" else ""
        return out.joinToString("; ") + tail
    }

    /** Сколько файлов разбирать поимённо, прежде чем считать остальные скопом. */
    private const val MAX_DIAGNOSED = 8

    fun saves(): Item {
        val dir = savesPath().takeIf { it != "—" }
        val bytes = GameFolderScanner.directoryBytes(dir)
        val profiles = dir?.let { File(it).listFiles()?.size } ?: 0
        return Item(
            labelRes = R.string.status_saves,
            present = bytes > 0,
            detail = if (bytes > 0) (dir ?: "") else "пусто",
            bytes = bytes
        )
    }

    /**
     * Compiled shader cache.
     *
     * Never "missing" in a way that matters - it rebuilds itself - but it is
     * often the largest thing on disk after the games, and it is the one item
     * here that is safe to delete when storage runs out.
     */
    fun shaderCache(): Item {
        val dir = root()?.let { "$it/shader" }
        val bytes = GameFolderScanner.directoryBytes(dir)
        return Item(
            labelRes = R.string.status_shaders,
            present = true,
            detail = if (bytes > 0) "можно удалить · ${dir ?: ""}" else "пуст",
            bytes = bytes
        )
    }

    /** Where saves live; worth stating because it moves with the data root. */
    fun savesPath(): String = root()?.let { "$it/nand/user/save" } ?: "—"

    /** The data root every other path is derived from. */
    fun dataRoot(): String = root() ?: "—"

    /** One-line summary for a collapsed strip. */
    fun summary(context: Context): String {
        val missing = all(context).filter { !it.present }
        return if (missing.isEmpty()) "всё на месте"
        else "нет: " + missing.joinToString(", ") { context.getString(it.labelRes).lowercase() }
    }
}
