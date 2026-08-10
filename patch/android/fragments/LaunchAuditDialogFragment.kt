// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.DialogLaunchAuditBinding
import org.yuzu.yuzu_emu.utils.NativeConfig
import org.yuzu.yuzu_emu.utils.NativeSymbiosis

/**
 * Shows what happened to the settings when this game started.
 *
 * The settings screen can only show what was *requested*. Between that and the
 * GPU sit several silent filters - a hardware capability check, a vendor
 * allow-list, a present-mode negotiation - and none of them report back. The
 * result is a toggle that stays on next to a frame rate that never moves.
 *
 * This sheet is the missing half: for every setting that matters, whether it
 * reached the renderer, what it became if it did not, and which line of code
 * decided that. Where the answer is "this does nothing here", the correction
 * is offered directly, because a setting that provably cannot work is not a
 * preference worth keeping.
 */
class LaunchAuditDialogFragment : BottomSheetDialogFragment() {
    private var _binding: DialogLaunchAuditBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogLaunchAuditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.buttonAuditFix.setOnClickListener { applyFixes() }
        binding.buttonAuditCopy.setOnClickListener { copyReport() }
        refresh()
    }

    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            val hasData = withContext(Dispatchers.IO) { NativeSymbiosis.auditHasData() }
            if (!hasData) {
                binding.textAuditSummary.text = getString(R.string.audit_no_data)
                binding.textAuditBody.text = getString(R.string.audit_no_data_detail)
                binding.buttonAuditFix.isVisible = false
                return@launch
            }

            val entries = withContext(Dispatchers.IO) { NativeSymbiosis.auditEntries() }
            val summary = withContext(Dispatchers.IO) { NativeSymbiosis.auditSummary() }

            binding.textAuditSummary.text = getString(
                R.string.audit_summary_line,
                summary.applied,
                summary.substituted,
                summary.ignored
            )

            binding.textAuditBody.text = buildReport(entries)

            // Only offer the button when there is something safe to correct.
            binding.buttonAuditFix.isVisible = summary.fixable > 0
            if (summary.fixable > 0) {
                binding.buttonAuditFix.text =
                    getString(R.string.audit_fix_n, summary.fixable)
            }
        }
    }

    /**
     * Renders the findings, worst first.
     *
     * Ordering matters more than it looks: a user who opens this mid-game wants
     * the thing that is wrong, not a list of things that are fine.
     */
    private fun buildReport(entries: List<NativeSymbiosis.AuditEntry>): CharSequence {
        val order = mapOf(
            NativeSymbiosis.AuditVerdict.Unsupported to 0,
            NativeSymbiosis.AuditVerdict.Ignored to 1,
            NativeSymbiosis.AuditVerdict.Substituted to 2,
            NativeSymbiosis.AuditVerdict.Applied to 3,
            NativeSymbiosis.AuditVerdict.Unknown to 4
        )
        val sorted = entries.sortedBy { order[it.verdict] ?: 9 }

        return buildString {
            for (entry in sorted) {
                if (entry.key.isEmpty()) {
                    append(entry.reason).append("\n\n")
                    continue
                }

                val mark = when (entry.verdict) {
                    NativeSymbiosis.AuditVerdict.Applied -> "✓"
                    NativeSymbiosis.AuditVerdict.Substituted -> "≈"
                    NativeSymbiosis.AuditVerdict.Ignored -> "✕"
                    NativeSymbiosis.AuditVerdict.Unsupported -> "⃠"
                    else -> "?"
                }

                append(mark).append("  ").append(entry.key).append('\n')

                append("    ")
                if (entry.effective == entry.requested) {
                    append(entry.requested)
                } else {
                    append(getString(R.string.audit_became, entry.requested, entry.effective))
                }
                append('\n')

                append("    ").append(entry.reason).append('\n')

                if (entry.evidence.isNotEmpty()) {
                    append("    ").append(entry.evidence).append('\n')
                }
                append('\n')
            }
        }
    }

    private fun applyFixes() {
        viewLifecycleOwner.lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) { NativeSymbiosis.auditAutoFix() }
            // Persist immediately: a correction lost to a crash would have the
            // user fixing the same thing every launch.
            withContext(Dispatchers.IO) { NativeConfig.saveGlobalConfig() }
            binding.textAuditSummary.text = getString(R.string.audit_fixed_n, changed)
            binding.buttonAuditFix.isVisible = false
            refresh()
        }
    }

    private fun copyReport() {
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { NativeSymbiosis.getSettingsAudit() }
            val clipboard = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("Eden Symbiosis audit", text)
            )
            binding.textAuditSummary.text = getString(R.string.audit_copied)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "LaunchAuditDialogFragment"
    }
}
