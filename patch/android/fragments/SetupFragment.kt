// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import com.google.android.material.transition.MaterialFadeThrough
import org.yuzu.yuzu_emu.NativeLibrary
import java.io.File
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.adapters.SetupAdapter
import org.yuzu.yuzu_emu.databinding.FragmentSetupBinding
import org.yuzu.yuzu_emu.features.settings.model.Settings
import org.yuzu.yuzu_emu.model.ButtonState
import org.yuzu.yuzu_emu.model.GamesViewModel
import org.yuzu.yuzu_emu.model.HomeViewModel
import org.yuzu.yuzu_emu.model.PageButton
import org.yuzu.yuzu_emu.model.SetupCallback
import org.yuzu.yuzu_emu.model.SetupPage
import org.yuzu.yuzu_emu.model.PageState
import org.yuzu.yuzu_emu.ui.main.MainActivity
import org.yuzu.yuzu_emu.utils.DirectoryInitialization
import org.yuzu.yuzu_emu.utils.SharedDataDirectory
import org.yuzu.yuzu_emu.utils.NativeConfig
import org.yuzu.yuzu_emu.utils.ViewUtils
import org.yuzu.yuzu_emu.utils.ViewUtils.setVisible
import org.yuzu.yuzu_emu.utils.collect

class SetupFragment : Fragment() {
    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val gamesViewModel: GamesViewModel by activityViewModels()

    private lateinit var mainActivity: MainActivity

    private lateinit var hasBeenWarned: BooleanArray

    private lateinit var pages: MutableList<SetupPage>

    /**
     * Checks storage permission before opening the picker.
     *
     * Without All Files Access the system picker still appears, but the tree it
     * returns cannot be written to, and the failure surfaces much later as
     * "the folder does not work". Asking first turns that into one clear
     * prompt.
     */
    private fun requestSharedFolder() {
        if (SharedDataDirectory.needsAllFilesAccess() &&
            !SharedDataDirectory.hasAllFilesAccess()
        ) {
            MessageDialogFragment.newInstance(
                requireActivity(),
                titleId = R.string.setup_data_folder,
                descriptionId = R.string.need_all_files_access,
                positiveButtonTitleId = R.string.open_settings,
                positiveAction = {
                    runCatching {
                        startActivity(
                            Intent(
                                android.provider.Settings
                                    .ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:${requireContext().packageName}")
                            )
                        )
                    }
                },
                showNegativeButton = true,
                negativeButtonTitleId = android.R.string.cancel
            ).show(childFragmentManager, MessageDialogFragment.TAG)
            pageButtonCallback?.onStepCompleted(R.string.setup_data_folder, false)
            return
        }
        getSharedFolder.launch(null)
    }

    private fun rejectFolder(message: String) {
        MessageDialogFragment.newInstance(
            requireActivity(),
            titleId = R.string.setup_data_folder,
            descriptionString = message
        ).show(childFragmentManager, MessageDialogFragment.TAG)
        pageButtonCallback?.onStepCompleted(R.string.setup_data_folder, false)
    }

    private val getSharedFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                pageButtonCallback?.onStepCompleted(R.string.setup_data_folder, false)
                return@registerForActivityResult
            }
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }

            val path = SharedDataDirectory.resolveTreePath(uri)
            if (path == null) {
                // Android 11+ blocks Android/data of other apps outright. Say so
                // rather than silently accepting a folder that will not work.
                rejectFolder(
                    getString(
                        R.string.shared_dir_unreachable,
                        SharedDataDirectory.suggestedNeutralPath()
                    )
                )
                return@registerForActivityResult
            }

            val check = SharedDataDirectory.inspect(requireContext(), path)
            if (!check.ok) {
                rejectFolder(
                    when (check.verdict) {
                        SharedDataDirectory.Verdict.SameAsPrivate ->
                            getString(R.string.shared_same_as_private)
                        SharedDataDirectory.Verdict.NotADirectory ->
                            getString(R.string.shared_not_a_dir)
                        SharedDataDirectory.Verdict.NoPermission ->
                            getString(R.string.need_all_files_access)
                        else -> getString(R.string.shared_not_writable)
                    }
                )
                return@registerForActivityResult
            }

            SharedDataDirectory.configuredPath = path

            // Redirect the live process, not just the preference.
            //
            // This is what makes one step possible. The buttons below ask the
            // native layer whether keys and firmware exist; that question is
            // answered against whatever directory was initialised at startup.
            // Without redirecting first, pointing at a full Eden folder leaves
            // those buttons reading the old empty one, and the user is told to
            // install keys they already have.
            val redirected = SharedDataDirectory.redirectNow(path)

            if (!redirected) {
                rejectFolder(getString(R.string.shared_redirect_failed))
                return@registerForActivityResult
            }

            // Report what was found. A folder that turns out to be empty is
            // not an error, but the user should learn it now rather than at
            // the first launch attempt.
            MessageDialogFragment.newInstance(
                requireActivity(),
                titleId = R.string.setup_data_folder,
                descriptionString = getString(
                    R.string.shared_dir_adopted,
                    path,
                    if (check.hasFirmware) check.firmwareFiles else 0,
                    getString(if (check.hasKeys) R.string.yes else R.string.no),
                    getString(if (check.hasSaves) R.string.yes else R.string.no)
                )
            ).show(childFragmentManager, MessageDialogFragment.TAG)

            // Re-evaluate every button on this page: adopting a populated
            // folder can complete the keys and firmware steps outright.
            pageButtonCallback?.onStepCompleted(R.string.setup_data_folder, false)
            checkForButtonState.invoke()
        }


    /**
     * Set when a page button is pressed, so an async result can report back.
     *
     * Nullable rather than lateinit: the shared-folder picker can return after
     * a configuration change, when the callback was never assigned in this
     * instance. `lateinit` throws on that path, and the safe calls dotted
     * around this file were only ever hiding a warning, not the exception.
     */
    private var pageButtonCallback: SetupCallback? = null

    companion object {
        const val KEY_NEXT_VISIBILITY = "NextButtonVisibility"
        const val KEY_BACK_VISIBILITY = "BackButtonVisibility"
        const val KEY_HAS_BEEN_WARNED = "HasBeenWarned"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exitTransition = MaterialFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mainActivity = requireActivity() as MainActivity

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.viewPager2.currentItem > 0) {
                        pageBackward()
                    } else {
                        requireActivity().finish()
                    }
                }
            }
        )

        requireActivity().window.navigationBarColor =
            ContextCompat.getColor(requireContext(), android.R.color.transparent)

        pages = mutableListOf<SetupPage>()
        pages.apply {
            add(
                SetupPage(
                    R.drawable.ic_permission,
                    R.string.permissions,
                    R.string.permissions_description,
                    mutableListOf<PageButton>().apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(
                                PageButton(
                                    R.drawable.ic_notification,
                                    R.string.notifications,
                                    R.string.notifications_description,
                                    {
                                        pageButtonCallback = it
                                        permissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    },
                                    {
                                        if (NotificationManagerCompat.from(requireContext())
                                                .areNotificationsEnabled()
                                        ) {
                                            ButtonState.BUTTON_ACTION_COMPLETE
                                        } else {
                                            ButtonState.BUTTON_ACTION_INCOMPLETE
                                        }
                                    },
                                    false,
                                    false,
                                )
                            )
                        }
                    },
                    {
                        if (NotificationManagerCompat.from(requireContext())
                                .areNotificationsEnabled()
                        ) {
                            PageState.COMPLETE
                        } else {
                            PageState.INCOMPLETE
                        }
                    }
                )
            )
            add(
                SetupPage(
                    R.drawable.ic_folder_open,
                    R.string.emulator_data,
                    // One page, two routes: adopt an existing Eden folder, or
                    // install keys and firmware from scratch. Splitting them
                    // across screens made the second look like it had ignored
                    // the first.
                    R.string.emulator_data_description_symbiosis,
                    mutableListOf<PageButton>().apply {
                        add(
                            PageButton(
                                R.drawable.ic_folder_open,
                                R.string.setup_data_folder,
                                R.string.setup_choose_folder_description,
                                {
                                    // First button on this page deliberately.
                                    // Pointing at an existing Eden folder here
                                    // means firmware and keys are already
                                    // present, so the buttons below have
                                    // nothing left to do.
                                    pageButtonCallback = it
                                    requestSharedFolder()
                                },
                                {
                                    if (SharedDataDirectory.isSharing(requireContext())) {
                                        ButtonState.BUTTON_ACTION_COMPLETE
                                    } else {
                                        // Undefined, not incomplete: private
                                        // storage is a perfectly good default
                                        // and this step is optional.
                                        ButtonState.BUTTON_ACTION_UNDEFINED
                                    }
                                },
                                false,
                                false
                            )
                        )
                        add(
                            PageButton(
                                R.drawable.ic_key,
                                R.string.keys,
                                R.string.keys_description,
                                {
                                    pageButtonCallback = it
                                    getProdKey.launch(arrayOf("*/*"))
                                },
                                {
                                    val file = File(
                                        DirectoryInitialization.userDirectory + "/keys/prod.keys"
                                    )
                                    if (file.exists() && NativeLibrary.areKeysPresent()) {
                                        ButtonState.BUTTON_ACTION_COMPLETE
                                    } else {
                                        ButtonState.BUTTON_ACTION_INCOMPLETE
                                    }
                                },
                                false,
                                true,
                                R.string.install_prod_keys_warning,
                                R.string.install_prod_keys_warning_description,
                                R.string.install_prod_keys_warning_help,
                            )
                        )
                        add(
                            PageButton(
                                R.drawable.ic_firmware,
                                R.string.firmware,
                                R.string.firmware_description,
                                {
                                    pageButtonCallback = it
                                    getFirmware.launch(arrayOf("application/zip"))
                                },
                                {
                                    if (NativeLibrary.isFirmwareAvailable()) {
                                        ButtonState.BUTTON_ACTION_COMPLETE
                                    } else {
                                        ButtonState.BUTTON_ACTION_INCOMPLETE
                                    }
                                },
                                false,
                                true,
                                R.string.install_firmware_warning,
                                R.string.install_firmware_warning_description,
                                R.string.install_firmware_warning_help,
                            )
                        )
                        add(
                            PageButton(
                                R.drawable.ic_controller,
                                R.string.games,
                                R.string.games_description,
                                {
                                    pageButtonCallback = it
                                    getGamesDirectory.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
                                },
                                {
                                    if (NativeConfig.getGameDirs().isNotEmpty()) {
                                        ButtonState.BUTTON_ACTION_COMPLETE
                                    } else {
                                        ButtonState.BUTTON_ACTION_INCOMPLETE
                                    }
                                },
                                false,
                                true,
                                R.string.add_games_warning,
                                R.string.add_games_warning_description,
                                R.string.add_games_warning_help,
                            )
                        )
                    },
                    {
                        // Never report COMPLETE for this page.
                        //
                        // SetupAdapter only builds the buttons when the page is
                        // not already complete (SetupAdapter.kt:39). Returning
                        // COMPLETE here produced exactly the bug the user hit:
                        // the page rendered the word "Done!" with no buttons at
                        // all, so the folder could not be chosen.
                        //
                        // Reporting INCOMPLETE keeps every button on screen and
                        // lets each one show its own state. Nothing here is
                        // mandatory - the warnings on Next already handle
                        // skipping - so a page that never says "complete" costs
                        // the user nothing.
                        PageState.INCOMPLETE
                    }
                )
            )
            add(
                SetupPage(
                    R.drawable.ic_check,
                    R.string.done,
                    R.string.done_description,
                    mutableListOf<PageButton>().apply {
                        add(
                            PageButton(
                                R.drawable.ic_arrow_forward,
                                R.string.get_started,
                                0,
                                buttonAction = {
                                    finishSetup()
                                },
                                buttonState = {
                                    ButtonState.BUTTON_ACTION_UNDEFINED
                                },
                            )
                        )
                    }
                ) { PageState.UNDEFINED }
            )
        }

        homeViewModel.shouldPageForward.collect(
            viewLifecycleOwner,
            resetState = { homeViewModel.setShouldPageForward(false) }
        ) { if (it) pageForward() }
        homeViewModel.gamesDirSelected.collect(
            viewLifecycleOwner,
            resetState = { homeViewModel.setGamesDirSelected(false) }
        ) { if (it) checkForButtonState.invoke() }

        binding.viewPager2.apply {
            adapter = SetupAdapter(requireActivity() as AppCompatActivity, pages)
            offscreenPageLimit = 2
            isUserInputEnabled = false
        }

        binding.viewPager2.registerOnPageChangeCallback(object : OnPageChangeCallback() {
            var previousPosition: Int = 0

            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)

                val isFirstPage = position == 0
                val isLastPage = position == pages.size - 1

                if (isFirstPage) {
                    ViewUtils.hideView(binding.buttonBack)
                } else {
                    ViewUtils.showView(binding.buttonBack)
                }

                if (isLastPage) {
                    ViewUtils.hideView(binding.buttonNext)
                } else {
                    ViewUtils.showView(binding.buttonNext)
                }

                previousPosition = position
            }
        })

        binding.buttonNext.setOnClickListener {
            val index = binding.viewPager2.currentItem
            val currentPage = pages[index]

            val warningMessages =
                mutableListOf<Triple<Int, Int, Int>>() // title, description, helpLink

            currentPage.pageButtons?.forEach { button ->
                if (button.hasWarning || button.isUnskippable) {
                    val buttonState = button.buttonState()
                    if (buttonState == ButtonState.BUTTON_ACTION_COMPLETE) {
                        return@forEach
                    }

                    if (button.isUnskippable) {
                        MessageDialogFragment.newInstance(
                            activity = requireActivity(),
                            titleId = button.warningTitleId,
                            descriptionId = button.warningDescriptionId,
                            helpLinkId = button.warningHelpLinkId
                        ).show(childFragmentManager, MessageDialogFragment.TAG)
                        return@setOnClickListener
                    }

                    if (!hasBeenWarned[index]) {
                        warningMessages.add(
                            Triple(
                                button.warningTitleId,
                                button.warningDescriptionId,
                                button.warningHelpLinkId
                            )
                        )
                    }
                }
            }

            if (warningMessages.isNotEmpty()) {
                SetupWarningDialogFragment.newInstance(
                    warningMessages.map { it.first }.toIntArray(),
                    warningMessages.map { it.second }.toIntArray(),
                    warningMessages.map { it.third }.toIntArray(),
                    index
                ).show(childFragmentManager, SetupWarningDialogFragment.TAG)
                return@setOnClickListener
            }
            pageForward()
        }
        binding.buttonBack.setOnClickListener { pageBackward() }


        if (savedInstanceState != null) {
            val nextIsVisible = savedInstanceState.getBoolean(KEY_NEXT_VISIBILITY)
            val backIsVisible = savedInstanceState.getBoolean(KEY_BACK_VISIBILITY)
            hasBeenWarned = savedInstanceState.getBooleanArray(KEY_HAS_BEEN_WARNED)!!

            if (nextIsVisible) {
                binding.buttonNext.visibility = View.VISIBLE
            }
            if (backIsVisible) {
                binding.buttonBack.visibility = View.VISIBLE
            }
        } else {
            hasBeenWarned = BooleanArray(pages.size)
        }

        setInsets()
    }


    override fun onStop() {
        super.onStop()
        NativeConfig.saveGlobalConfig()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_NEXT_VISIBILITY, binding.buttonNext.isVisible)
        outState.putBoolean(KEY_BACK_VISIBILITY, binding.buttonBack.isVisible)
        outState.putBooleanArray(KEY_HAS_BEEN_WARNED, hasBeenWarned)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private val checkForButtonState: () -> Unit = {
        val page = pages[binding.viewPager2.currentItem]
        page.pageButtons?.forEach {
            if (it.buttonState() == ButtonState.BUTTON_ACTION_COMPLETE) {
                pageButtonCallback?.onStepCompleted(
                    it.titleId,
                    pageFullyCompleted = false
                )
            }

            if (page.pageSteps() == PageState.COMPLETE) {
                pageButtonCallback?.onStepCompleted(0, pageFullyCompleted = true)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) {
                checkForButtonState.invoke()
            }

            if (!it &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                PermissionDeniedDialogFragment().show(
                    childFragmentManager,
                    PermissionDeniedDialogFragment.TAG
                )
            }
        }


    val getProdKey =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
            if (result != null) {
                mainActivity.processKey(result, "keys")
                if (NativeLibrary.areKeysPresent()) {
                    checkForButtonState.invoke()
                }
            }
        }

    val getFirmware =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
            if (result != null) {
                mainActivity.processFirmware(result) {
                    if (NativeLibrary.isFirmwareAvailable()) {
                        checkForButtonState.invoke()
                    }
                }
            }
        }

    val getGamesDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { result ->
            if (result != null) {
                mainActivity.processGamesDir(result)
            }
        }

    private fun finishSetup() {
        PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)
            .edit()
            .putBoolean(Settings.PREF_FIRST_APP_LAUNCH, false)
            .apply()

        gamesViewModel.reloadGames(directoriesChanged = true, firstStartup = false)

        mainActivity.finishSetup(binding.root.findNavController())
    }

    fun pageForward() {
        if (_binding != null) {
            binding.viewPager2.currentItem += 1
        }
    }

    fun pageBackward() {
        if (_binding != null) {
            binding.viewPager2.currentItem -= 1
        }
    }

    fun setPageWarned(page: Int) {
        hasBeenWarned[page] = true
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets =
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets =
                windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val leftPadding = barInsets.left + cutoutInsets.left
            val topPadding = barInsets.top + cutoutInsets.top
            val rightPadding = barInsets.right + cutoutInsets.right
            val bottomPadding = barInsets.bottom + cutoutInsets.bottom

            if (resources.getBoolean(R.bool.small_layout)) {
                binding.viewPager2
                    .updatePadding(
                        left = leftPadding,
                        top = topPadding,
                        right = rightPadding
                    )
                binding.constraintButtons
                    .updatePadding(
                        left = leftPadding,
                        right = rightPadding,
                        bottom = bottomPadding
                    )
            } else {
                binding.viewPager2.updatePadding(
                    top = topPadding,
                    bottom = bottomPadding
                )
                binding.constraintButtons
                    .updatePadding(
                        left = leftPadding,
                        right = rightPadding,
                        bottom = bottomPadding
                    )
            }
            windowInsets
        }
}
