// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import org.yuzu.yuzu_emu.databinding.PageSetupBinding
import org.yuzu.yuzu_emu.model.PageState
import org.yuzu.yuzu_emu.model.SetupCallback
import org.yuzu.yuzu_emu.model.SetupPage
import org.yuzu.yuzu_emu.utils.ViewUtils
import org.yuzu.yuzu_emu.viewholder.AbstractViewHolder
import android.content.res.ColorStateList
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.model.ButtonState

class SetupAdapter(val activity: AppCompatActivity, pages: List<SetupPage>) :
    AbstractListAdapter<SetupPage, SetupAdapter.SetupPageViewHolder>(pages) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SetupPageViewHolder {
        PageSetupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            .also { return SetupPageViewHolder(it) }
    }

    inner class SetupPageViewHolder(val binding: PageSetupBinding) :
        AbstractViewHolder<SetupPage>(binding), SetupCallback {
        /** Page currently shown by this holder; needed because the holder is reused. */
        private var boundPage: SetupPage? = null

        override fun bind(model: SetupPage) {
            boundPage = model
            // Reset before anything else.
            //
            // ViewPager2 is a RecyclerView, so this holder is reused across
            // pages. onStepCompleted() hides the button container and reveals
            // the "Done!" label, and nothing ever put them back: a holder that
            // previously showed the completed permissions page handed the data
            // page a hidden container, so its buttons existed but could not be
            // seen. That is the "the folder button disappeared" report.
            //
            // Removing the child views matters just as much - binding the same
            // page twice (rotation, notifyDataSetChanged) otherwise appends a
            // second copy of every button.
            binding.pageButtonContainer.removeAllViews()
            // Set the state directly rather than via ViewUtils: those helpers
            // animate alpha, and an animation still in flight from the previous
            // page would fight this one and could leave alpha at 0 on a view
            // that is nominally VISIBLE.
            binding.pageButtonContainer.animate().cancel()
            binding.textConfirmation.animate().cancel()
            binding.pageButtonContainer.alpha = 1f
            binding.pageButtonContainer.visibility = View.VISIBLE
            binding.pageButtonContainer.isClickable = true
            binding.textConfirmation.alpha = 1f
            binding.textConfirmation.visibility = View.INVISIBLE

            if (model.pageSteps.invoke() == PageState.COMPLETE) {
                onStepCompleted(0, pageFullyCompleted = true)
            }

            if (model.pageButtons != null && model.pageSteps.invoke() != PageState.COMPLETE) {
                for (pageButton in model.pageButtons) {
                    val pageButtonView = LayoutInflater.from(activity)
                        .inflate(
                            R.layout.page_button,
                            binding.pageButtonContainer,
                            false
                        ) as MaterialButton

                    pageButtonView.apply {
                        id = pageButton.titleId
                        icon = ResourcesCompat.getDrawable(
                            activity.resources,
                            pageButton.iconId,
                            activity.theme
                        )
                        text = activity.resources.getString(pageButton.titleId)
                    }

                    pageButtonView.setOnClickListener {
                        pageButton.buttonAction.invoke(this@SetupPageViewHolder)
                    }

                    binding.pageButtonContainer.addView(pageButtonView)

                    // Disable buton add if its already completed
                    if (pageButton.buttonState.invoke() == ButtonState.BUTTON_ACTION_COMPLETE) {
                        onStepCompleted(pageButton.titleId, pageFullyCompleted = false)
                    }
                }
            }

            binding.icon.setImageDrawable(
                ResourcesCompat.getDrawable(
                    activity.resources,
                    model.iconId,
                    activity.theme
                )
            )
            binding.textTitle.text = activity.resources.getString(model.titleId)
            binding.textDescription.text =
                Html.fromHtml(activity.resources.getString(model.descriptionId), 0)
        }

        /**
         * True while any button on this page can still usefully be pressed.
         *
         * A button reporting UNDEFINED is optional but live - the data-folder
         * button is exactly that - so it must keep the page open.
         */
        private fun hasActionableButton(): Boolean =
            boundPage?.pageButtons?.any {
                it.buttonState.invoke() != ButtonState.BUTTON_ACTION_COMPLETE
            } == true

        override fun onStepCompleted(pageButtonId: Int, pageFullyCompleted: Boolean) {
            val button = binding.pageButtonContainer.findViewById<MaterialButton>(pageButtonId)

            // Only collapse the page when it has nothing left to offer.
            //
            // Hiding the container removes the data-folder button too, and that
            // one is never "done": pointing at a different folder is a thing
            // the user may want at any time, including after keys and firmware
            // are already installed. Losing it is the "the button disappeared"
            // bug, so keep the buttons on screen whenever one of them is still
            // actionable.
            if (pageFullyCompleted && !hasActionableButton()) {
                ViewUtils.hideView(binding.pageButtonContainer, 200)
                ViewUtils.showView(binding.textConfirmation, 200)
            }

            if (button != null) {
                button.isEnabled = false
                button.animate()
                    .alpha(0.38f)
                    .setDuration(200)
                    .start()
                button.setTextColor(button.context.getColor(com.google.android.material.R.color.material_on_surface_disabled))
                button.iconTint =
                    ColorStateList.valueOf(button.context.getColor(com.google.android.material.R.color.material_on_surface_disabled))
            }
        }
    }
}
