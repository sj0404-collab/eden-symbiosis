// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.adapters.LauncherAdapter
import org.yuzu.yuzu_emu.databinding.FragmentLaunchersBinding
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.utils.NativeConfig
import org.yuzu.yuzu_emu.utils.NativeSymbiosis

/**
 * Launcher skins and the retro rendering preset attached to each.
 *
 * Selecting a launcher changes both the look of the app and how the emulated
 * frame is presented: the retro presets drive a real fragment shader that
 * lowers the effective resolution and quantises colour, so a modern game can
 * be made to output like an 8- or 16-bit machine.
 */
class LaunchersFragment : Fragment() {
    private var _binding: FragmentLaunchersBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: LauncherAdapter
    private var launchers: List<NativeSymbiosis.Launcher> = emptyList()

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
        _binding = FragmentLaunchersBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarLaunchers.setNavigationOnClickListener {
            binding.root.findNavController().popBackStack()
        }

        launchers = NativeSymbiosis.launchers()
        val active = runCatching { NativeSymbiosis.getActiveLauncher() }.getOrDefault(0)

        adapter = LauncherAdapter(launchers, active) { index, launcher ->
            applyLauncher(index, launcher)
        }
        binding.listLaunchers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@LaunchersFragment.adapter
        }

        setupSliders()
        updateCustomVisibility(launchers.getOrNull(active)?.isCustom == true)
        setInsets()
    }

    private fun applyLauncher(index: Int, launcher: NativeSymbiosis.Launcher) {
        val applied = runCatching { NativeSymbiosis.setActiveLauncher(index) }.getOrDefault(0)
        runCatching { NativeConfig.saveGlobalConfig() }

        updateCustomVisibility(launcher.isCustom)

        val message = when {
            launcher.isCustom -> getString(R.string.launcher_custom_selected)
            launcher.isRetro -> getString(
                R.string.launcher_retro_selected,
                launcher.displayName,
                launcher.virtualWidth,
                launcher.virtualHeight
            )
            else -> getString(R.string.launcher_selected, launcher.displayName)
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun updateCustomVisibility(isCustom: Boolean) {
        binding.containerCustom.isVisible = isCustom
        if (isCustom) {
            loadCustomValues()
        }
    }

    /** Reads the persisted custom values into the sliders. */
    private fun loadCustomValues() {
        fun clampTo(slider: Slider, value: Int) {
            slider.value = value.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        }
        clampTo(binding.sliderWidth, IntSetting.RETRO_WIDTH.getInt())
        clampTo(binding.sliderHeight, IntSetting.RETRO_HEIGHT.getInt())
        clampTo(binding.sliderColors, IntSetting.RETRO_COLOR_LEVELS.getInt())
        clampTo(binding.sliderDither, IntSetting.RETRO_DITHER.getInt())
        clampTo(binding.sliderScanline, IntSetting.RETRO_SCANLINE.getInt())
        clampTo(binding.sliderGrid, IntSetting.RETRO_LCD_GRID.getInt())
        clampTo(binding.sliderSaturation, IntSetting.RETRO_SATURATION.getInt())
        clampTo(binding.sliderContrast, IntSetting.RETRO_CONTRAST.getInt())
        refreshLabels()
    }

    private fun setupSliders() {
        // Persist on release rather than on every pixel of drag: each write
        // invalidates the presentation pipeline, and rebuilding it mid-drag
        // would stutter badly.
        val onRelease = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                persistCustomValues()
            }
        }

        listOf(
            binding.sliderWidth, binding.sliderHeight, binding.sliderColors,
            binding.sliderDither, binding.sliderScanline, binding.sliderGrid,
            binding.sliderSaturation, binding.sliderContrast
        ).forEach { slider ->
            slider.addOnSliderTouchListener(onRelease)
            slider.addOnChangeListener { _, _, _ -> refreshLabels() }
        }

        binding.buttonResetCustom.setOnClickListener {
            binding.sliderWidth.value = 0f
            binding.sliderHeight.value = 0f
            binding.sliderColors.value = 0f
            binding.sliderDither.value = 0f
            binding.sliderScanline.value = 0f
            binding.sliderGrid.value = 0f
            binding.sliderSaturation.value = 100f
            binding.sliderContrast.value = 100f
            persistCustomValues()
            Snackbar.make(binding.root, R.string.reset_to_native, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun persistCustomValues() {
        IntSetting.RETRO_WIDTH.setInt(binding.sliderWidth.value.toInt())
        IntSetting.RETRO_HEIGHT.setInt(binding.sliderHeight.value.toInt())
        IntSetting.RETRO_COLOR_LEVELS.setInt(binding.sliderColors.value.toInt())
        IntSetting.RETRO_DITHER.setInt(binding.sliderDither.value.toInt())
        IntSetting.RETRO_SCANLINE.setInt(binding.sliderScanline.value.toInt())
        IntSetting.RETRO_LCD_GRID.setInt(binding.sliderGrid.value.toInt())
        IntSetting.RETRO_SATURATION.setInt(binding.sliderSaturation.value.toInt())
        IntSetting.RETRO_CONTRAST.setInt(binding.sliderContrast.value.toInt())
        runCatching { NativeConfig.saveGlobalConfig() }
    }

    private fun refreshLabels() {
        val width = binding.sliderWidth.value.toInt()
        val height = binding.sliderHeight.value.toInt()
        binding.labelRes.text = if (width == 0 || height == 0) {
            getString(R.string.virtual_resolution_native)
        } else {
            getString(R.string.virtual_resolution, width, height)
        }

        val levels = binding.sliderColors.value.toInt()
        binding.labelColors.text = if (levels < 2) {
            getString(R.string.color_depth_full)
        } else {
            val total = levels.toLong() * levels * levels
            getString(R.string.color_depth, levels, total)
        }

        binding.labelDither.text =
            getString(R.string.dithering_pct, binding.sliderDither.value.toInt())
        binding.labelScanline.text =
            getString(R.string.scanlines_pct, binding.sliderScanline.value.toInt())
        binding.labelGrid.text =
            getString(R.string.lcd_grid_pct, binding.sliderGrid.value.toInt())
        binding.labelSaturation.text =
            getString(R.string.saturation_pct, binding.sliderSaturation.value.toInt())
        binding.labelContrast.text =
            getString(R.string.contrast_pct, binding.sliderContrast.value.toInt())
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _: View, insets: WindowInsetsCompat ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.appbarLaunchers.updatePadding(
                left = bars.left + cutout.left,
                top = bars.top,
                right = bars.right + cutout.right
            )
            binding.scrollLaunchers.updatePadding(
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
