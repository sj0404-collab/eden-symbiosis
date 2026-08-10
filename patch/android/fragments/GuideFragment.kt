// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.transition.MaterialSharedAxis
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.FragmentGuideBinding
import org.yuzu.yuzu_emu.utils.NativeSymbiosis

/**
 * Reference for every setting: what it does, what it costs, when to leave it
 * alone.
 *
 * The emulator exposes around 110 settings, many of them workarounds for
 * specific driver bugs. Hiding the obscure ones behind a toggle made the app
 * approachable but did not explain anything. This screen does.
 */
class GuideFragment : Fragment() {
    private var _binding: FragmentGuideBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: GuideAdapter
    private var allEntries: List<NativeSymbiosis.GuideEntry> = emptyList()
    private var activeSection: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGuideBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarGuide.setNavigationOnClickListener {
            binding.root.findNavController().popBackStack()
        }

        allEntries = NativeSymbiosis.guideEntries()

        adapter = GuideAdapter(allEntries)
        binding.listGuide.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@GuideFragment.adapter
        }

        buildSectionChips()

        binding.editGuideSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })

        setInsets()
    }

    /** One chip per section, built from the data rather than hard-coded. */
    private fun buildSectionChips() {
        val sections = allEntries.map { it.section }.distinct()
        val group = binding.chipGroupGuide

        val allChip = Chip(requireContext()).apply {
            text = getString(R.string.guide_all)
            isCheckable = true
            isChecked = true
            setOnClickListener {
                activeSection = null
                refresh()
            }
        }
        group.addView(allChip)

        sections.forEach { section ->
            val chip = Chip(requireContext()).apply {
                text = section
                isCheckable = true
                setOnClickListener {
                    activeSection = section
                    refresh()
                }
            }
            group.addView(chip)
        }
    }

    private fun refresh() {
        val query = binding.editGuideSearch.text?.toString()?.trim().orEmpty().lowercase()
        val filtered = allEntries.filter { entry ->
            val sectionOk = activeSection == null || entry.section == activeSection
            val queryOk = query.isEmpty() ||
                entry.title.lowercase().contains(query) ||
                entry.what.lowercase().contains(query) ||
                entry.advice.lowercase().contains(query) ||
                entry.key.lowercase().contains(query)
            sectionOk && queryOk
        }
        adapter.submit(filtered)
    }

    private inner class GuideAdapter(
        private var items: List<NativeSymbiosis.GuideEntry>
    ) : RecyclerView.Adapter<GuideHolder>() {

        private val expanded = mutableSetOf<String>()

        fun submit(newItems: List<NativeSymbiosis.GuideEntry>) {
            items = newItems
            expanded.clear()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = GuideHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.card_guide_entry, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: GuideHolder, position: Int) {
            val item = items[position]
            // Title is unique enough to key expansion state; several concept
            // entries deliberately have no settings key.
            val id = item.title

            holder.title.text = item.title
            holder.risk.text = item.risk
            holder.what.text = item.what
            holder.cost.text = item.cost
            holder.advice.text = item.advice

            holder.key.isVisible = item.key.isNotEmpty()
            holder.key.text = item.key

            val isOpen = expanded.contains(id)
            holder.detail.isVisible = isOpen

            holder.itemView.setOnClickListener {
                if (isOpen) expanded.remove(id) else expanded.add(id)
                notifyItemChanged(holder.bindingAdapterPosition)
            }
        }
    }

    private inner class GuideHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.text_guide_title)
        val risk: Chip = view.findViewById(R.id.chip_guide_risk)
        val what: TextView = view.findViewById(R.id.text_guide_what)
        val cost: TextView = view.findViewById(R.id.text_guide_cost)
        val advice: TextView = view.findViewById(R.id.text_guide_advice)
        val key: TextView = view.findViewById(R.id.text_guide_key)
        val detail: View = view.findViewById(R.id.container_guide_detail)
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _: View, insets: WindowInsetsCompat ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.appbarGuide.updatePadding(
                left = bars.left + cutout.left,
                top = bars.top,
                right = bars.right + cutout.right
            )
            binding.listGuide.updatePadding(bottom = bars.bottom)
            insets
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
