// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.FragmentUtilitiesBinding
import org.yuzu.yuzu_emu.utils.DirectoryInitialization
import org.yuzu.yuzu_emu.utils.NativeSymbiosis
import org.yuzu.yuzu_emu.utils.SharedDataDirectory

/**
 * Four maintenance tools that address problems the emulator itself cannot:
 * firmware taking more space than it needs, cartridge dumps padded to the
 * physical card size, saves living somewhere "clear app data" destroys, and
 * crashes whose cause is buried in a log nobody should have to read.
 */
class UtilitiesFragment : Fragment() {
    private var _binding: FragmentUtilitiesBinding? = null
    private val binding get() = _binding!!

    private val userDir: String? get() = DirectoryInitialization.userDirectory
    private val firmwareDir: String get() = "${userDir}/nand/system/Contents/registered"
    private val saveDir: String get() = "${userDir}/nand/user/save"

    /** Vault lives beside the user directory, not inside app-private storage. */
    private val vaultDir: String
        get() = requireContext().getExternalFilesDir(null)?.absolutePath?.let { "$it/save_vault" }
            ?: "${userDir}/save_vault"

    private var selectedDump: Uri? = null
    private var selectedDumpPath: String? = null

    private val pickDump =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onDumpPicked(it) }
        }

    private val pickSharedDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { onSharedDirPicked(it) }
        }

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
        _binding = FragmentUtilitiesBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbarUtilities.setNavigationOnClickListener {
            binding.root.findNavController().popBackStack()
        }

        runCatching { NativeSymbiosis.configureVault(vaultDir, 5) }

        setupFirmware()
        setupRomTools()
        setupSaveVault()
        setupCrashAnalyst()
        setupSharedDirectory()

        setInsets()
    }

    // --- 1. Firmware ------------------------------------------------------

    private fun setupFirmware() {
        val onToggle = { _: View, _: Boolean -> updateFirmwareEstimate() }
        binding.switchKeepApplets.setOnCheckedChangeListener(onToggle)
        binding.switchKeepFonts.setOnCheckedChangeListener(onToggle)
        binding.switchKeepLangs.setOnCheckedChangeListener(onToggle)

        binding.buttonWhyNoXz.setOnClickListener {
            showText(R.string.why_no_compression, NativeSymbiosis.getCompressionNote())
        }

        binding.buttonFirmwarePrune.setOnClickListener { confirmPrune() }

        refreshFirmware()
    }

    private fun refreshFirmware() {
        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { NativeSymbiosis.firmwareInfo(firmwareDir) }
            binding.textFirmwareInfo.text = if (info.isEmpty) {
                getString(R.string.no_firmware_installed)
            } else {
                buildString {
                    append("total:     ").append(mib(info.totalBytes)).append('\n')
                    append("essential: ").append(mib(info.essentialBytes)).append('\n')
                    append("fonts:     ").append(mib(info.fontBytes)).append('\n')
                    append("applets:   ").append(mib(info.appletBytes)).append('\n')
                    append("languages: ").append(mib(info.languageBytes))
                }
            }
            binding.buttonFirmwarePrune.isEnabled = !info.isEmpty
            updateFirmwareEstimate()
        }
    }

    private fun updateFirmwareEstimate() {
        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { NativeSymbiosis.firmwareInfo(firmwareDir) }
            if (info.isEmpty) {
                binding.textFirmwareEstimate.text = ""
                return@launch
            }
            val after = withContext(Dispatchers.IO) {
                NativeSymbiosis.estimateFirmware(
                    firmwareDir,
                    binding.switchKeepApplets.isChecked,
                    binding.switchKeepFonts.isChecked,
                    binding.switchKeepLangs.isChecked
                )
            }
            val saved = (info.totalBytes - after).coerceAtLeast(0)
            binding.textFirmwareEstimate.text =
                getString(R.string.after_slimming, mib(after), mib(saved))
        }
    }

    private fun confirmPrune() {
        // Deleting firmware content is irreversible, so the dialog states the
        // consequence rather than just asking "are you sure".
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.slim_down)
            .setMessage(R.string.slim_down_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.slim_down) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val freed = withContext(Dispatchers.IO) {
                        NativeSymbiosis.pruneFirmware(
                            firmwareDir,
                            binding.switchKeepApplets.isChecked,
                            binding.switchKeepFonts.isChecked,
                            binding.switchKeepLangs.isChecked
                        )
                    }
                    Snackbar.make(
                        binding.root,
                        getString(R.string.freed_space, mib(freed)),
                        Snackbar.LENGTH_LONG
                    ).show()
                    refreshFirmware()
                }
            }
            .show()
    }

    // --- 2. ROM tools -----------------------------------------------------

    private fun setupRomTools() {
        binding.buttonPickDump.setOnClickListener { pickDump.launch(arrayOf("*/*")) }
        binding.buttonTrim.setOnClickListener { trimSelected() }
        binding.buttonToNsp.setOnClickListener { convertSelected() }
    }

    private fun onDumpPicked(uri: Uri) {
        selectedDump = uri
        // The tools need a real path: SAF content URIs cannot be resized in
        // place. Copying a multi-gigabyte file just to inspect it would be
        // worse than telling the user plainly.
        val path = resolvePath(uri)
        selectedDumpPath = path

        if (path == null) {
            binding.textDumpResult.isVisible = true
            binding.textDumpResult.text = getString(R.string.path_not_accessible)
            binding.containerDumpActions.isVisible = false
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { NativeSymbiosis.inspect(path) }
            binding.textDumpResult.isVisible = true
            if (info == null) {
                binding.textDumpResult.text = getString(R.string.could_not_inspect)
                binding.containerDumpActions.isVisible = false
                return@launch
            }
            binding.textDumpResult.text = buildString {
                append(info.filename).append('\n')
                append("format: ").append(info.format).append('\n')
                append("status: ").append(info.health).append('\n')
                append("size:   ").append(mib(info.sizeBytes)).append('\n')
                if (info.reclaimable > 0) {
                    append("waste:  ").append(mib(info.reclaimable)).append('\n')
                }
                append('\n').append(info.summary)
                if (info.advice.isNotEmpty()) append("\n\n→ ").append(info.advice)
            }
            binding.containerDumpActions.isVisible = info.format == "XCI"
            binding.buttonTrim.isEnabled = info.canTrim
            binding.buttonToNsp.isEnabled = info.isHealthy || info.canTrim
        }
    }

    private fun trimSelected() {
        val path = selectedDumpPath ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) { NativeSymbiosis.trimXci(path) }
            val message = if (freed > 0) {
                getString(R.string.freed_space, mib(freed))
            } else {
                getString(R.string.nothing_to_trim)
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            selectedDump?.let { onDumpPicked(it) }
        }
    }

    private fun convertSelected() {
        val path = selectedDumpPath ?: return
        val destination = path.substringBeforeLast('.') + ".nsp"

        viewLifecycleOwner.lifecycleScope.launch {
            Snackbar.make(binding.root, R.string.converting, Snackbar.LENGTH_SHORT).show()
            val (written, error) = withContext(Dispatchers.IO) {
                NativeSymbiosis.xciToNsp(path, destination)
            }
            val message = if (written > 0) {
                getString(R.string.converted_to, mib(written))
            } else {
                error.ifBlank { getString(R.string.conversion_failed) }
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        }
    }

    // --- 3. Save vault ----------------------------------------------------

    private fun setupSaveVault() {
        binding.buttonBackupNow.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    NativeSymbiosis.backupSaves(saveDir, "manual", "manual backup")
                }
                val message = if (bytes > 0) {
                    getString(R.string.backed_up, mib(bytes))
                } else {
                    getString(R.string.no_saves_found)
                }
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                refreshVault()
            }
        }
        binding.buttonListBackups.setOnClickListener { showBackupPicker() }
        refreshVault()
    }

    private fun refreshVault() {
        viewLifecycleOwner.lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { NativeSymbiosis.getVaultStatus() }
            binding.textVaultStatus.text = status.trim()
        }
    }

    private fun showBackupPicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { NativeSymbiosis.backups() }
            if (list.isEmpty()) {
                Snackbar.make(binding.root, R.string.no_backups, Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            val labels = list.map { backup ->
                val when_ = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    .format(java.util.Date(backup.timestamp * 1000))
                "$when_ · ${mib(backup.sizeBytes)} · ${backup.label.ifBlank { backup.titleId }}"
            }.toTypedArray()

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.restore)
                .setItems(labels) { _, index ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val error = withContext(Dispatchers.IO) {
                            NativeSymbiosis.restore(list[index], saveDir)
                        }
                        val message = error.ifBlank { getString(R.string.restored_ok) }
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                        refreshVault()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // --- 4. Crash analyst -------------------------------------------------

    private fun setupCrashAnalyst() {
        binding.buttonAnalyseCrash.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) { NativeSymbiosis.analyseCrash() }
                binding.textCrashResult.isVisible = true
                binding.textCrashResult.text = text
            }
        }
    }

    // --- 5. Shared data directory -----------------------------------------

    private fun setupSharedDirectory() {
        binding.buttonPickShared.setOnClickListener {
            if (SharedDataDirectory.needsAllFilesAccess() &&
                !SharedDataDirectory.hasAllFilesAccess()
            ) {
                // Without All Files Access the picker returns a tree this app
                // cannot actually write to, which would fail confusingly later.
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.util_shared_dir)
                    .setMessage(R.string.need_all_files_access)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        runCatching {
                            startActivity(
                                android.content.Intent(
                                    android.provider.Settings
                                        .ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:${requireContext().packageName}")
                                )
                            )
                        }
                    }
                    .show()
                return@setOnClickListener
            }
            pickSharedDir.launch(null)
        }

        binding.buttonResetShared.setOnClickListener {
            val current = SharedDataDirectory.configuredPath
            if (current != null) {
                SharedDataDirectory.releaseLock(current)
            }
            SharedDataDirectory.configuredPath = null
            refreshSharedStatus()
            promptRestart()
        }

        refreshSharedStatus()
    }

    private fun onSharedDirPicked(uri: android.net.Uri) {
        // Persist the grant so the choice survives a reboot.
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }

        val path = SharedDataDirectory.resolveTreePath(uri)
        if (path == null) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.util_shared_dir)
                .setMessage(getString(R.string.shared_dir_unreachable,
                    SharedDataDirectory.suggestedNeutralPath()))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val check = SharedDataDirectory.inspect(requireContext(), path)
        if (!check.ok) {
            val reason = when (check.verdict) {
                SharedDataDirectory.Verdict.SameAsPrivate -> getString(R.string.shared_same_as_private)
                SharedDataDirectory.Verdict.NotADirectory -> getString(R.string.shared_not_a_dir)
                SharedDataDirectory.Verdict.NoPermission -> getString(R.string.need_all_files_access)
                else -> getString(R.string.shared_not_writable)
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.util_shared_dir)
                .setMessage(reason)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        // State plainly what was found, so a wrong folder is obvious before it
        // is committed to.
        val contents = buildString {
            append(path).append("\n\n")
            append(getString(R.string.found_firmware,
                if (check.hasFirmware) check.firmwareFiles else 0)).append('\n')
            append(getString(if (check.hasKeys) R.string.found_keys_yes else R.string.found_keys_no))
                .append('\n')
            append(getString(if (check.hasSaves) R.string.found_saves_yes else R.string.found_saves_no))
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_shared_dir)
            .setMessage(contents)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.use_this_folder) { _, _ ->
                SharedDataDirectory.configuredPath = path
                refreshSharedStatus()
                promptRestart()
            }
            .show()
    }

    private fun refreshSharedStatus() {
        val context = requireContext()
        val sharing = SharedDataDirectory.isSharing(context)
        binding.textSharedStatus.text = if (sharing) {
            getString(R.string.shared_dir_active, SharedDataDirectory.configuredPath)
        } else {
            getString(R.string.shared_dir_private, SharedDataDirectory.privatePath(context) ?: "?")
        }
        binding.textSharedWarning.isVisible = sharing
    }

    private fun promptRestart() {
        // The data root is read once during startup, so a restart is required
        // rather than merely recommended.
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.restart_required)
            .setMessage(R.string.restart_required_desc)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }


    // --- helpers ----------------------------------------------------------

    private fun mib(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GiB", mb / 1024)
        else String.format(java.util.Locale.US, "%.0f MiB", mb)
    }

    /**
     * Best-effort mapping of a document URI to a filesystem path. Returns null
     * when the file lives somewhere only the SAF can reach, which is common on
     * modern Android and is reported honestly rather than worked around by
     * copying gigabytes.
     */
    private fun resolvePath(uri: Uri): String? {
        val id = runCatching {
            android.provider.DocumentsContract.getDocumentId(uri)
        }.getOrNull() ?: return null

        val parts = id.split(':')
        if (parts.size != 2) return null
        val (type, relative) = parts

        val candidates = buildList {
            if (type == "primary") {
                add("${android.os.Environment.getExternalStorageDirectory()}/$relative")
            }
            add("/storage/$type/$relative")
        }
        return candidates.firstOrNull { File(it).exists() }
    }

    private fun showText(titleId: Int, body: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(titleId)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _: View, insets: WindowInsetsCompat ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.appbarUtilities.updatePadding(
                left = bars.left + cutout.left,
                top = bars.top,
                right = bars.right + cutout.right
            )
            binding.scrollUtilities.updatePadding(
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
