// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.CardTuningProfileBinding
import org.yuzu.yuzu_emu.utils.NativeSymbiosis
import org.yuzu.yuzu_emu.viewholder.AbstractViewHolder

/**
 * Shows tuning profiles as expandable cards.
 *
 * Each card lists the individual setting changes with the reason they help on
 * this specific hardware, so applying a profile is never a black box.
 */
class TuningProfileAdapter(
    profiles: List<NativeSymbiosis.Profile>,
    private val onApply: (NativeSymbiosis.Profile) -> Unit
) : AbstractListAdapter<NativeSymbiosis.Profile, TuningProfileAdapter.ProfileViewHolder>(profiles) {

    private val expanded = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        CardTuningProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            .also { return ProfileViewHolder(it) }
    }

    inner class ProfileViewHolder(val binding: CardTuningProfileBinding) :
        AbstractViewHolder<NativeSymbiosis.Profile>(binding) {

        override fun bind(model: NativeSymbiosis.Profile) {
            val context = binding.root.context

            binding.textProfileName.text = model.displayName
            binding.textProfileSummary.text = model.summary
            binding.chipEffect.text = model.expectedEffect
            binding.textNeedsDriver.isVisible = !model.worksOnStockDriver

            // A profile that needs a driver the device cannot install is shown
            // but cannot be applied -- clearer than hiding it silently.
            binding.buttonApply.isEnabled = model.worksOnStockDriver
            binding.buttonApply.text = if (model.worksOnStockDriver) {
                context.getString(R.string.apply_profile)
            } else {
                context.getString(R.string.needs_custom_driver_short)
            }

            val isExpanded = expanded.contains(model.id)
            binding.containerTweaks.isVisible = isExpanded
            binding.iconExpand.rotation = if (isExpanded) 180f else 0f

            if (isExpanded) {
                populateTweaks(model)
            } else {
                binding.containerTweaks.removeAllViews()
            }

            binding.root.setOnClickListener {
                if (expanded.contains(model.id)) {
                    expanded.remove(model.id)
                } else {
                    expanded.add(model.id)
                }
                notifyItemChanged(bindingAdapterPosition)
            }

            binding.buttonApply.setOnClickListener { onApply(model) }
        }

        private fun populateTweaks(model: NativeSymbiosis.Profile) {
            val container: LinearLayout = binding.containerTweaks
            container.removeAllViews()
            val context = container.context
            val inflater = LayoutInflater.from(context)

            model.tweaks.forEach { tweak ->
                val row = inflater.inflate(R.layout.item_tweak_row, container, false)
                row.findViewById<TextView>(R.id.text_tweak_key).text =
                    "${tweak.key} = ${tweak.value}"
                row.findViewById<TextView>(R.id.text_tweak_reason).text = tweak.reason
                container.addView(row)
            }
        }
    }

    /** Replaces the visible profile list, collapsing any expanded cards. */
    override fun replaceList(newList: List<NativeSymbiosis.Profile>) {
        expanded.clear()
        super.replaceList(newList)
    }
}
