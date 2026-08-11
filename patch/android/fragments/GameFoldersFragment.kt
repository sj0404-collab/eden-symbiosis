// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.adapters.GameFolderAdapter
import org.yuzu.yuzu_emu.databinding.FragmentGameFoldersBinding
import org.yuzu.yuzu_emu.utils.GameFolderScanner

/**
 * Lists the folders that hold games, rather than the games themselves.
 *
 * The default screen shows a flat list of every title, which stops being
 * useful once there are more than a handful and says nothing about where they
 * live or what they cost in storage. This shows each folder with its game
 * count and total size, so "which of these is eating my SD card" is answerable
 * at a glance.
 *
 * Nothing is cached. The folder list in `config.ini` is read and the contents
 * counted on every visit: a cache is what made a deleted game keep appearing
 * and a newly copied one stay invisible, and the scan is cheap enough that
 * avoiding it was never worth the confusion.
 */
class GameFoldersFragment : Fragment() {
    private var _binding: FragmentGameFoldersBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGameFoldersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarGameFolders.setNavigationOnClickListener {
            binding.root.findNavController().popBackStack()
        }

        binding.listFolders.layoutManager = LinearLayoutManager(requireContext())
        binding.swipeFolders.setOnRefreshListener { rescan() }

        rescan()
    }

    /** Re-reads the folders from disk. Deliberately not memoised. */
    private fun rescan() {
        binding.swipeFolders.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) {
                GameFolderScanner.scan(requireContext())
            }

            if (_binding == null) return@launch

            binding.swipeFolders.isRefreshing = false
            binding.textNoFolders.isVisible = folders.isEmpty()
            binding.listFolders.isVisible = folders.isNotEmpty()

            val games = folders.sumOf { it.gameCount }
            val bytes = folders.sumOf { it.totalBytes }
            binding.textFoldersSummary.text = getString(
                R.string.folders_summary,
                folders.size,
                games,
                GameFolderScanner.humanSize(bytes)
            )

            binding.listFolders.adapter = GameFolderAdapter(
                requireActivity() as androidx.appcompat.app.AppCompatActivity,
                folders
            ) { folder ->
                // Tapping a folder re-reads it and shows what is inside, so a
                // game copied in a moment ago appears without a restart.
                showFolderContents(folder)
            }
        }
    }

    /** Lists the ROMs in one folder, read fresh at the moment of asking. */
    private fun showFolderContents(folder: GameFolderScanner.Folder) {
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                GameFolderScanner.listGames(
                    requireContext(), folder.uriString, folder.depth
                )
            }
            if (_binding == null) return@launch

            val body = if (entries.isEmpty()) {
                getString(R.string.folder_empty)
            } else {
                // Show the sub-path when there is one: with games kept one per
                // folder, a bare filename does not say where it came from.
                entries.joinToString("\n") { entry ->
                    val where = if (entry.relativePath.isEmpty()) ""
                                else "  (" + entry.relativePath + ")"
                    "${entry.name}$where  —  ${GameFolderScanner.humanSize(entry.bytes)}"
                }
            }

            MessageDialogFragment.newInstance(
                requireActivity(),
                titleString = folder.displayName,
                descriptionString = body
            ).show(childFragmentManager, MessageDialogFragment.TAG)
        }
    }

    override fun onResume() {
        super.onResume()
        // A folder's contents can change while the app is in the background -
        // a game copied over USB, for instance - so re-read rather than trust
        // what was on screen a minute ago.
        if (_binding != null) rescan()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
