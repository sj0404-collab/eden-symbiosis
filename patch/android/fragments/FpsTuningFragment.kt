// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.adapters.TuningProfileAdapter
import org.yuzu.yuzu_emu.databinding.FragmentFpsTuningBinding
import org.yuzu.yuzu_emu.features.settings.model.Settings
import org.yuzu.yuzu_emu.utils.NativeConfig
import org.yuzu.yuzu_emu.utils.NativeSymbiosis

/**
 * Per-device / per-driver FPS tuning.
 *
 * Presents the curated profile catalogue filtered to the hardware actually
 * detected on this device, with every individual setting change visible and
 * explained before it is applied.
 */
class FpsTuningFragment : Fragment() {
    private var _binding: FragmentFpsTuningBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TuningProfileAdapter
    private var allProfiles: List<NativeSymbiosis.Profile> = emptyList()
    private var deviceProfiles: List<NativeSymbiosis.Profile> = emptyList()

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
        _binding = FragmentFpsTuningBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarFpsTuning.setNavigationOnClickListener {
            binding.root.findNavController().popBackStack()
        }

        // Hardware banner. Before a game has been launched the broker has not
        // surveyed anything yet, so say so instead of showing "Unknown".
        val detected = runCatching { NativeSymbiosis.getDetectedGpu() }.getOrDefault("")
        val known = detected.isNotEmpty() && !detected.startsWith("Unknown")
        binding.textDetectedGpu.text = if (known) {
            detected
        } else {
            getString(R.string.hardware_not_detected_yet)
        }
        binding.textDriverNote.text = if (known) {
            getString(R.string.profiles_matched_note)
        } else {
            getString(R.string.launch_game_to_detect)
        }

        deviceProfiles = NativeSymbiosis.profilesForDevice()
        allProfiles = NativeSymbiosis.allProfiles()

        adapter = TuningProfileAdapter(currentSource()) { profile -> applyProfile(profile) }
        binding.listProfiles.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@FpsTuningFragment.adapter
        }

        binding.switchShowAll.setOnCheckedChangeListener { _, _ -> refresh() }
        binding.chipGroupIntent.setOnCheckedStateChangeListener { _, _ -> refresh() }

        updateThermal()

        refresh()
        setInsets()
    }

    override fun onResume() {
        super.onResume()
        // Re-sample on return: the device may have cooled or started throttling
        // while the user was in a game.
        updateThermal()
    }

    /**
     * Surfaces thermal throttling right where profiles are chosen.
     *
     * A user who has already lost 40% of their clocks to heat will get nothing
     * from a Max FPS profile, and telling them that is more useful than
     * letting them try every profile in turn.
     */
    private fun updateThermal() {
        val thermal = NativeSymbiosis.thermal()
        if (!thermal.hasSensors) {
            binding.cardThermal.isVisible = false
            return
        }

        binding.cardThermal.isVisible = true
        binding.textThermalSummary.text = thermal.summary
        binding.textThermalAdvice.isVisible = thermal.advice.isNotEmpty()
        binding.textThermalAdvice.text = thermal.advice

        val colour = if (thermal.isThrottling) {
            com.google.android.material.R.attr.colorError
        } else {
            com.google.android.material.R.attr.colorPrimary
        }
        val typed = android.util.TypedValue()
        requireContext().theme.resolveAttribute(colour, typed, true)
        binding.iconThermal.setColorFilter(typed.data)
    }

    private fun currentSource(): List<NativeSymbiosis.Profile> {
        val source = if (binding.switchShowAll.isChecked) allProfiles else deviceProfiles
        return source.ifEmpty { allProfiles }
    }

    /**
     * Filters by the selected intent. The profile id encodes the intent
     * suffix, which avoids marshalling an extra enum across JNI.
     */
    private fun refresh() {
        val suffix = when (binding.chipGroupIntent.checkedChipId) {
            R.id.chip_fps -> "maxfps"
            R.id.chip_balanced -> "balanced"
            R.id.chip_quality -> "quality"
            R.id.chip_battery -> "battery"
            else -> null
        }
        val filtered = if (suffix == null) {
            currentSource()
        } else {
            currentSource().filter { it.id.endsWith(suffix) }
        }
        adapter.replaceList(filtered)
    }

    private fun applyProfile(profile: NativeSymbiosis.Profile) {
        val changed = runCatching { NativeSymbiosis.applyProfile(profile.id) }.getOrDefault(0)

        if (changed > 0) {
            // Persist immediately so the profile survives a crash or a kill.
            runCatching { NativeConfig.saveGlobalConfig() }
        }

        val message = if (changed > 0) {
            getString(R.string.profile_applied, changed)
        } else {
            getString(R.string.profile_applied_nothing)
        }

        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.root)
            .show()
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _: View, insets: WindowInsetsCompat ->
            val barInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.appbarFpsTuning.updatePadding(
                left = barInsets.left + cutout.left,
                top = barInsets.top,
                right = barInsets.right + cutout.right
            )
            binding.scrollFpsTuning.updatePadding(
                left = barInsets.left + cutout.left,
                right = barInsets.right + cutout.right,
                bottom = barInsets.bottom
            )
            insets
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
