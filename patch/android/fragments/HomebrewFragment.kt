// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.transition.MaterialSharedAxis
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.FragmentHomebrewBinding
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.utils.NativeConfig
import org.yuzu.yuzu_emu.utils.NativeSymbiosis
import org.yuzu.yuzu_emu.utils.SymbiosisStrings

/**
 * Free homebrew catalogue and Mali compatibility advice.
 *
 * The homebrew tab lists programs whose authors publish them for public
 * download; the app opens the official release page rather than fetching files
 * itself, so the user always sees the source and the licence.
 *
 * The compatibility tab is advice only. No commercial game data is bundled,
 * linked or downloaded: dumping a title you own happens outside this app.
 */
class HomebrewFragment : Fragment() {
    private var _binding: FragmentHomebrewBinding? = null
    private val binding get() = _binding!!

    private var showingHomebrew = true

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
        _binding = FragmentHomebrewBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarHomebrew.setNavigationOnClickListener {
            binding.root.findNavController().popBackStack()
        }

        binding.listHomebrew.layoutManager = LinearLayoutManager(requireContext())

        binding.tabsHomebrew.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showingHomebrew = tab.position == 0
                refresh()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        refresh()
        setInsets()
    }

    private fun refresh() {
        if (showingHomebrew) {
            binding.textSectionNote.setText(R.string.homebrew_note)
            binding.listHomebrew.adapter = HomebrewAdapter(NativeSymbiosis.homebrew())
        } else {
            binding.textSectionNote.setText(R.string.compat_note)
            binding.listHomebrew.adapter = CompatAdapter(NativeSymbiosis.compatList())
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Snackbar.make(binding.root, R.string.no_browser, Snackbar.LENGTH_SHORT).show()
        }
    }

    private inner class HomebrewAdapter(private val items: List<NativeSymbiosis.Homebrew>) :
        RecyclerView.Adapter<EntryHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = EntryHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.card_catalogue_entry, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: EntryHolder, position: Int) {
            val item = items[position]
            holder.title.text = item.name
            holder.sub.text = buildString {
                append(item.author)
                append(" · ")
                append(item.license)
                append(" · ")
                append(getString(R.string.no_keys_needed))
                if (item.usesGpu) {
                    append(" · ")
                    append(getString(R.string.tests_gpu))
                }
            }
            holder.desc.text = item.description
            holder.action.setText(R.string.open_release_page)
            holder.action.setOnClickListener { openUrl(item.url) }
        }
    }

    private inner class CompatAdapter(private val items: List<NativeSymbiosis.Compat>) :
        RecyclerView.Adapter<EntryHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = EntryHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.card_catalogue_entry, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: EntryHolder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.sub.text = buildString {
                append(SymbiosisStrings.compatRating(requireContext(), item.rating, item.rating))
                if (item.memoryHeavy) {
                    append(" · ")
                    append(getString(R.string.memory_heavy))
                }
            }
            holder.desc.text = item.note
            holder.action.setText(R.string.apply_recommended_mode)
            holder.action.setOnClickListener {
                // Applying the advice is one tap; the mode engine does the rest.
                val applied = runCatching {
                    NativeSymbiosis.applyAutoMode(item.recommendedMode)
                }.getOrDefault(0)
                if (applied > 0) {
                    runCatching { NativeConfig.saveGlobalConfig() }
                }
                Snackbar.make(
                    binding.root,
                    getString(R.string.mode_applied_for, item.title),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private inner class EntryHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.text_entry_title)
        val sub: TextView = view.findViewById(R.id.text_entry_sub)
        val desc: TextView = view.findViewById(R.id.text_entry_desc)
        val action: MaterialButton = view.findViewById(R.id.button_entry_action)
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _: View, insets: WindowInsetsCompat ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.appbarHomebrew.updatePadding(
                left = bars.left + cutout.left,
                top = bars.top,
                right = bars.right + cutout.right
            )
            binding.scrollHomebrew.updatePadding(
                left = bars.left + cutout.left,
                right = bars.right + cutout.right,
                bottom = bars.bottom
            )
            insets
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
