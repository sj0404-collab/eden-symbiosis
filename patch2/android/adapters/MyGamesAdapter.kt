// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.CardMyGamesFolderBinding
import org.yuzu.yuzu_emu.utils.GameFolderScanner

/**
 * Панель "Мои игры": папка, её вес, и файлы внутри неё.
 *
 * ЧЕМ ОТЛИЧАЕТСЯ ОТ СПИСКА ИГР
 *   Список игр показывает то, что эмулятор смог разобрать: он читает
 *   заголовок каждого файла, и если ключей нет или формат сжатый - игры в
 *   списке не будет. Отсюда и берётся "папка вроде есть, а пусто".
 *
 *   Здесь наоборот: показывается то, что ЛЕЖИТ в папке. Имя файла, размер,
 *   и пометка, если этот файл эмулятор запустить не сможет. Ничего не
 *   додумывается и не прячется.
 *
 * БЕЗ ГЛУБОКОГО СКАНИРОВАНИЯ
 *   Читается ровно одна папка, без захода в подпапки. Так задумано: вопрос
 *   "что у меня в этой папке" не требует обхода дерева, а обход - это
 *   десятки запросов к системе хранилища и секунды ожидания при запуске.
 *
 * БЕЗ ОГРАНИЧЕНИЯ ПО КОЛИЧЕСТВУ
 *   Сколько файлов лежит, столько и показывается.
 */
class MyGamesAdapter(
    private val context: Context,
    private val folders: List<GameFolderScanner.Folder>,
    private val onHeaderClick: (GameFolderScanner.Folder) -> Unit
) : RecyclerView.Adapter<MyGamesAdapter.Holder>() {

    /** Какие папки развёрнуты. По позиции, список за время жизни не меняется. */
    private val expanded = mutableSetOf<Int>()

    /**
     * Прочитанные файлы, чтобы не опрашивать хранилище при каждой прокрутке.
     * Заполняется лениво - только для той папки, которую действительно
     * развернули.
     */
    private val files = mutableMapOf<Int, List<GameFolderScanner.Entry>>()

    inner class Holder(val binding: CardMyGamesFolderBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        CardMyGamesFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = folders.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val folder = folders[position]
        val b = holder.binding

        b.myGamesName.text = folder.displayName
        b.myGamesSize.text = GameFolderScanner.humanSize(folder.totalBytes)

        val isOpen = position in expanded
        val list = if (isOpen) filesFor(position, folder) else emptyList()

        b.myGamesStats.text = if (isOpen) {
            context.getString(R.string.my_games_tap_to_hide, list.size)
        } else {
            context.getString(R.string.my_games_tap_to_show, folder.gameCount)
        }

        b.myGamesFiles.visibility = if (isOpen) View.VISIBLE else View.GONE
        if (isOpen) {
            b.myGamesFiles.text = if (list.isEmpty()) {
                context.getString(R.string.my_games_empty)
            } else {
                list.joinToString("\n") { entry ->
                    val size = GameFolderScanner.humanSize(entry.bytes)
                    // Сжатые образы (.ncz/.nsz/.xcz) эмулятор не открывает.
                    // Показать и промолчать было бы хуже, чем не показать:
                    // человек будет искать, почему игра не запускается.
                    val mark = if (GameFolderScanner.isLaunchable(entry.name)) {
                        ""
                    } else {
                        "  " + context.getString(R.string.my_games_not_launchable)
                    }
                    "• ${entry.name}  ·  $size$mark"
                }
            }
        }

        b.myGamesHeader.setOnClickListener {
            if (isOpen) expanded.remove(position) else expanded.add(position)
            notifyItemChanged(position)
            onHeaderClick(folder)
        }
    }

    private fun filesFor(
        position: Int,
        folder: GameFolderScanner.Folder
    ): List<GameFolderScanner.Entry> = files.getOrPut(position) {
        runCatching { GameFolderScanner.listFilesFlat(context, folder.uriString) }
            .getOrDefault(emptyList())
    }
}
