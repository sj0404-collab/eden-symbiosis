// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import org.yuzu.yuzu_emu.databinding.CardLauncherBinding
import org.yuzu.yuzu_emu.utils.NativeSymbiosis
import org.yuzu.yuzu_emu.utils.SymbiosisStrings
import org.yuzu.yuzu_emu.viewholder.AbstractViewHolder

/**
 * Launcher skins, shown as single-selection cards.
 *
 * The swatch is tinted with the skin's own accent colour so the list previews
 * what each option looks like without needing a screenshot per launcher.
 */
class LauncherAdapter(
    launchers: List<NativeSymbiosis.Launcher>,
    private var selectedIndex: Int,
    private val onSelect: (Int, NativeSymbiosis.Launcher) -> Unit
) : AbstractListAdapter<NativeSymbiosis.Launcher, LauncherAdapter.LauncherViewHolder>(launchers) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LauncherViewHolder {
        CardLauncherBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            .also { return LauncherViewHolder(it) }
    }

    fun setSelected(index: Int) {
        val previous = selectedIndex
        selectedIndex = index
        if (previous in currentList.indices) notifyItemChanged(previous)
        if (index in currentList.indices) notifyItemChanged(index)
    }

    inner class LauncherViewHolder(val binding: CardLauncherBinding) :
        AbstractViewHolder<NativeSymbiosis.Launcher>(binding) {

        override fun bind(model: NativeSymbiosis.Launcher) {
            val context = binding.root.context
            binding.textLauncherName.text = model.displayName
            // Native text arrives in English; map it onto resources so the
            // card reads in the user's language. Launcher names themselves are
            // proper nouns and stay as they are.
            binding.textLauncherDesc.text =
                SymbiosisStrings.launcherDescription(context, model.key, model.description)

            // Show the concrete effect on image quality, not just prose: the
            // resolution and colour count are what actually change.
            binding.textLauncherPerf.text = buildString {
                if (model.virtualWidth > 0 && model.virtualHeight > 0) {
                    append("${model.virtualWidth}x${model.virtualHeight}")
                }
                if (model.totalColors > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${model.totalColors} colours")
                }
                if (isNotEmpty()) append(" · ")
                append(SymbiosisStrings.performanceNote(context, model.key, model.performanceNote))
            }

            // Tint the swatch with the launcher's accent.
            (binding.viewSwatch.background as? GradientDrawable)
                ?.mutate()
                ?.let { it as GradientDrawable }
                ?.setColor(model.accentArgb)

            val position = bindingAdapterPosition
            binding.cardLauncher.isChecked = position == selectedIndex
            binding.cardLauncher.setOnClickListener {
                val index = bindingAdapterPosition
                if (index >= 0) {
                    setSelected(index)
                    onSelect(index, model)
                }
            }
        }
    }
}
