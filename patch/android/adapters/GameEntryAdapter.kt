// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import org.yuzu.yuzu_emu.databinding.CardGameFolderBinding
import org.yuzu.yuzu_emu.utils.GameFolderScanner
import org.yuzu.yuzu_emu.viewholder.AbstractViewHolder

/**
 * One row per game file inside an opened folder.
 *
 * Reuses the folder card layout on purpose: a folder and a game in it should
 * look like the same kind of thing at two depths, so moving between them feels
 * like one list rather than two screens.
 *
 * The folder view previously ended in a dialog full of text - names and sizes
 * baked into one string, nothing tappable. This exists so the last step is a
 * row that starts the game.
 */
class GameEntryAdapter(
    private val activity: AppCompatActivity,
    entries: List<GameFolderScanner.Entry>,
    private val onClick: (GameFolderScanner.Entry) -> Unit
) : AbstractListAdapter<GameFolderScanner.Entry, GameEntryAdapter.EntryViewHolder>(entries) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder =
        EntryViewHolder(
            CardGameFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    inner class EntryViewHolder(val binding: CardGameFolderBinding) :
        AbstractViewHolder<GameFolderScanner.Entry>(binding) {

        override fun bind(model: GameFolderScanner.Entry) {
            // Strip the extension for the title: "Blade Chimera [0100...].nsp"
            // is what the file is called, not what the game is called, and the
            // bracketed title id is noise at a glance. The full name stays in
            // the second line so nothing is hidden.
            // An unpacked game is a file literally called "main" inside a
            // folder named after the title, so showing the filename would list
            // every such game as "main". The folder is the name in that case.
            val bare = model.name.lowercase() in setOf("main", "00")
            val label = if (bare && model.relativePath.isNotEmpty()) {
                model.relativePath.substringAfterLast('/')
            } else {
                model.name.substringBeforeLast('.')
            }
            binding.textFolderName.text = label
                .replace(Regex("\\s*\\[[^\\]]*\\]"), "")
                .trim()
                .ifBlank { model.name }

            binding.textFolderStats.text = if (model.relativePath.isEmpty()) {
                model.name
            } else {
                model.relativePath + "/" + model.name
            }

            binding.textFolderSize.isVisible = true
            binding.textFolderSize.text = GameFolderScanner.humanSize(model.bytes)

            binding.root.setOnClickListener { onClick(model) }
        }
    }
}
