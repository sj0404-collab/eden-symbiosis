// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.adapters.GameAdapter
import org.yuzu.yuzu_emu.databinding.FragmentGamesBinding
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.model.AppletInfo
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.GamesViewModel
import org.yuzu.yuzu_emu.model.HomeViewModel
import org.yuzu.yuzu_emu.ui.main.MainActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.adapters.GameFolderAdapter
import org.yuzu.yuzu_emu.adapters.MyGamesAdapter
import org.yuzu.yuzu_emu.utils.GameFolderScanner
import org.yuzu.yuzu_emu.utils.SetupStatus
import org.yuzu.yuzu_emu.features.settings.ui.SettingsSubscreen
import org.yuzu.yuzu_emu.utils.SharedDataDirectory
import org.yuzu.yuzu_emu.utils.GameFolders
import org.yuzu.yuzu_emu.utils.ViewUtils.setVisible
import org.yuzu.yuzu_emu.utils.collect
import info.debatty.java.stringsimilarity.Jaccard
import info.debatty.java.stringsimilarity.JaroWinkler
import java.util.Locale
import androidx.core.content.edit
import androidx.core.view.doOnNextLayout

class GamesFragment : Fragment() {
    private var _binding: FragmentGamesBinding? = null
    private val binding get() = _binding!!

    private var originalHeaderTopMargin: Int? = null
    private var originalHeaderBottomMargin: Int? = null
    private var originalHeaderRightMargin: Int? = null
    private var originalHeaderLeftMargin: Int? = null

    private var lastViewType: Int = GameAdapter.VIEW_TYPE_GRID
    private var fallbackBottomInset: Int = 0

    companion object {
        private const val SEARCH_TEXT = "SearchText"
        private const val PREF_SORT_TYPE = "GamesSortType"
    }

    private val gamesViewModel: GamesViewModel by activityViewModels()
    private val homeViewModel: HomeViewModel by activityViewModels()
    private lateinit var gameAdapter: GameAdapter

    private val preferences =
        PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    private lateinit var mainActivity: MainActivity
    private val getGamesDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { result ->
            if (result != null) {
                mainActivity.processGamesDir(result, true)
            }
        }

    private fun getCurrentViewType(): Int {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val key = if (isLandscape) CarouselRecyclerView.CAROUSEL_VIEW_TYPE_LANDSCAPE else CarouselRecyclerView.CAROUSEL_VIEW_TYPE_PORTRAIT
        val fallback = if (isLandscape) GameAdapter.VIEW_TYPE_CAROUSEL else GameAdapter.VIEW_TYPE_GRID
        return preferences.getInt(key, fallback)
    }

    private fun setCurrentViewType(type: Int) {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val key = if (isLandscape) CarouselRecyclerView.CAROUSEL_VIEW_TYPE_LANDSCAPE else CarouselRecyclerView.CAROUSEL_VIEW_TYPE_PORTRAIT
        preferences.edit { putInt(key, type) }
    }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGamesBinding.inflate(inflater)
        return binding.root
    }

    @SuppressLint("NotifyDataSetChanged")
    /**
     * Выбор папки данных.
     *
     * Регистрируется как обычный ActivityResult - Eden делает так же для
     * папки с играми. Результат проверяется до применения: SharedDataDirectory
     * умеет сказать, что папка не годится, до того как что-то будет записано.
     */
    private val getDataRootFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { result ->
            if (result == null) return@registerForActivityResult
            runCatching {
                val path = SharedDataDirectory.resolveTreePath(result)
                    ?: SharedDataDirectory.suggestedNeutralPath()
                val check = SharedDataDirectory.inspect(requireContext(), path)
                if (check.verdict != SharedDataDirectory.Verdict.Usable) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.shared_folder_failed, SharedDataDirectory.summarise(check)),
                        Toast.LENGTH_LONG
                    ).show()
                    return@runCatching
                }
                SharedDataDirectory.ensureLayout(path)
                if (SharedDataDirectory.redirectNow(path)) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.shared_folder_set, path),
                        Toast.LENGTH_LONG
                    ).show()
                    refreshStatusStrip()
                }
            }.onFailure {
                android.util.Log.e("Symbiosis", "data root change failed", it)
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setStatusBarShadeVisibility(true)
        mainActivity = requireActivity() as MainActivity

        if (savedInstanceState != null) {
            binding.searchText.setText(savedInstanceState.getString(SEARCH_TEXT))
        }

        gameAdapter = GameAdapter(
            requireActivity() as AppCompatActivity
        )

        applyGridGamesBinding()

        binding.swipeRefresh.apply {
            (binding.swipeRefresh as? SwipeRefreshLayout)?.setOnRefreshListener {
                gamesViewModel.reloadGames(false)
            }
            (binding.swipeRefresh as? SwipeRefreshLayout)?.setProgressBackgroundColorSchemeColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.swipeRefresh,
                    com.google.android.material.R.attr.colorPrimary
                )
            )
            (binding.swipeRefresh as? SwipeRefreshLayout)?.setColorSchemeColors(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.swipeRefresh,
                    com.google.android.material.R.attr.colorOnPrimary
                )
            )
            post {
                if (_binding == null) {
                    return@post
                }
                (binding.swipeRefresh as? SwipeRefreshLayout)?.isRefreshing = gamesViewModel.isReloading.value
            }
        }

        gamesViewModel.isReloading.collect(viewLifecycleOwner) {
            (binding.swipeRefresh as? SwipeRefreshLayout)?.isRefreshing = it
            binding.noticeText.setVisible(
                visible = gamesViewModel.games.value.isEmpty() && !it,
                gone = false
            )
        }
        gamesViewModel.games.collect(viewLifecycleOwner) {
            if (it.isNotEmpty()) {
                setAdapter(it)
            }
        }
        gamesViewModel.shouldSwapData.collect(
            viewLifecycleOwner,
            resetState = { gamesViewModel.setShouldSwapData(false) }
        ) {
            if (it) {
                setAdapter(gamesViewModel.games.value)
            }
        }
        gamesViewModel.shouldScrollToTop.collect(
            viewLifecycleOwner,
            resetState = { gamesViewModel.setShouldScrollToTop(false) }
        ) { if (it) scrollToTop() }

        gamesViewModel.shouldScrollAfterReload.collect(viewLifecycleOwner) { shouldScroll ->
            if (shouldScroll) {
                binding.gridGames.post {
                    (binding.gridGames as? CarouselRecyclerView)?.pendingScrollAfterReload = true
                    gameAdapter.notifyDataSetChanged()
                }
                gamesViewModel.setShouldScrollAfterReload(false)
            }
        }

        setupTopView()

        updateButtonsVisibility()

        binding.addDirectory.setOnClickListener {
            getGamesDirectory.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
        }

        binding.launchQlaunch?.setOnClickListener {
            launchQLaunch()
        }

        setInsets()

        // Панель состояния и карточки папок. Всё внутри runCatching: если
        // что-то пойдёт не так, экран игр останется точно таким же, как в
        // апстриме, а не упадёт.
        runCatching { setUpStatusStrip() }
            .onFailure { android.util.Log.e("Symbiosis", "status strip failed", it) }
    }

    /**
     * Показывает, что установлено и где - прямо на экране игр.
     *
     * Читается заново при каждом появлении экрана: запомненный ответ - это
     * ровно то, из-за чего мастер первого запуска говорил "Готово" там, где
     * ничего не было установлено.
     */
    private fun setUpStatusStrip() {
        val strip = binding.statusStrip ?: return

        // Открывается ровно так же, как это делает сам Eden из настроек:
        // HomeNavigationDirections + SettingsSubscreen.GAME_FOLDERS. Свой
        // переход в navigation-графе не изобретается - его там нет, и
        // сборка бы упала на несуществующем id.
        binding.foldersButton?.setOnClickListener {
            runCatching {
                val action = HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                    SettingsSubscreen.GAME_FOLDERS,
                    null
                )
                binding.root.findNavController().navigate(action)
            }.onFailure {
                android.util.Log.e("Symbiosis", "folders button failed", it)
            }
        }

        // Папка данных: куда эмулятор кладёт ключи, прошивку, сейвы и
        // шейдеры. Открывает системный выбор папки; сам перенос делает
        // SharedDataDirectory, который умеет проверить папку до записи.
        binding.dataRootButton?.setOnClickListener {
            runCatching { getDataRootFolder.launch(null) }
                .onFailure { android.util.Log.e("Symbiosis", "data root picker failed", it) }
        }

        strip.isVisible = true
        refreshStatusStrip()
    }

    /** Заново опрашивает состояние: файлы либо есть, либо нет. */
    private fun refreshStatusStrip() {
        val line = binding.statusLine ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val items = withContext(Dispatchers.IO) {
                runCatching { SetupStatus.all(ctx) }.getOrNull()
            } ?: return@launch
            if (_binding == null) return@launch

            line.text = items.joinToString(" · ") { item ->
                val size = item.bytes?.takeIf { it > 0 }
                    ?.let { " " + GameFolderScanner.humanSize(it) } ?: ""
                getString(item.labelRes) + (if (item.present) " ✓" else " ✕") + size
            }

            binding.statusPaths?.text = buildString {
                for (item in items) {
                    append(getString(item.labelRes)).append(": ").append(item.detail)
                    item.bytes?.takeIf { it > 0 }?.let {
                        append(" · ").append(GameFolderScanner.humanSize(it))
                    }
                    append('\n')
                }
                append(getString(R.string.status_data_root)).append(": ")
                    .append(SetupStatus.dataRoot())
            }

            val folders = withContext(Dispatchers.IO) {
                runCatching { GameFolderScanner.scan(ctx) }.getOrDefault(emptyList())
            }
            if (_binding == null) return@launch
            binding.folderCards?.isVisible = folders.isNotEmpty()

            // Панель "Мои игры": сами файлы в папках, без обхода вглубь.
            binding.myGamesTitle?.isVisible = folders.isNotEmpty()
            binding.myGamesList?.isVisible = folders.isNotEmpty()
            binding.myGamesList?.adapter = MyGamesAdapter(requireContext(), folders) { }
            // Адаптер строится заново на каждый обход: его список задаётся
            // конструктором, метода submitList у него нет.
            binding.folderCards?.adapter = GameFolderAdapter(
                requireActivity() as AppCompatActivity,
                folders
            ) { folder ->
                runCatching {
                    val action = HomeNavigationDirections.actionGlobalSettingsSubscreenActivity(
                        SettingsSubscreen.GAME_FOLDERS,
                        null
                    )
                    binding.root.findNavController().navigate(action)
                }.onFailure {
                    android.util.Log.e("Symbiosis", "folder card tap failed", it)
                }
            }
        }
    }

    val applyGridGamesBinding = {
        (binding.gridGames as? RecyclerView)?.apply {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val currentViewType = getCurrentViewType()
            val savedViewType = if (isLandscape || currentViewType != GameAdapter.VIEW_TYPE_CAROUSEL) currentViewType else GameAdapter.VIEW_TYPE_GRID

            //This prevents Grid/List views from reusing scaled or otherwise modified ViewHolders left over from the carousel.
            adapter = null
            recycledViewPool.clear()

            gameAdapter.setViewType(savedViewType)
            currentFilter = preferences.getInt(PREF_SORT_TYPE, View.NO_ID)

            // Set the correct layout manager
            layoutManager = when (savedViewType) {
                GameAdapter.VIEW_TYPE_GRID -> {
                    val columns = resources.getInteger(R.integer.game_columns_grid)
                    GridLayoutManager(context, columns)
                }
                GameAdapter.VIEW_TYPE_GRID_COMPACT -> {
                    val columns = resources.getInteger(R.integer.game_columns_grid)
                    GridLayoutManager(context, columns)
                }
                GameAdapter.VIEW_TYPE_LIST -> {
                    val columns = resources.getInteger(R.integer.game_columns_list)
                    GridLayoutManager(context, columns)
                }
                GameAdapter.VIEW_TYPE_CAROUSEL -> {
                    LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                }
                else -> throw IllegalArgumentException("Invalid view type: $savedViewType")
            }
            if (savedViewType == GameAdapter.VIEW_TYPE_CAROUSEL) {
                (binding.gridGames as? View)?.let { it -> ViewCompat.requestApplyInsets(it)}
                doOnNextLayout { //Carousel: important to avoid overlap issues
                    (this as? CarouselRecyclerView)?.notifyLaidOut(fallbackBottomInset)
                }
            } else {
                (this as? CarouselRecyclerView)?.setupCarousel(false)
            }
            adapter = gameAdapter
            lastViewType = savedViewType
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (_binding != null) {
            outState.putString(SEARCH_TEXT, binding.searchText.text.toString())
        }
    }

    override fun onPause() {
        super.onPause()
        if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) {
            gamesViewModel.lastScrollPosition = (binding.gridGames as? CarouselRecyclerView)?.getClosestChildPosition() ?: 0
        }
    }

    override fun onResume() {
        super.onResume()
        if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) {
            (binding.gridGames as? CarouselRecyclerView)?.setupCarousel(true)
            (binding.gridGames as? CarouselRecyclerView)?.restoreScrollState(gamesViewModel.lastScrollPosition)
        }
    }

    private var lastSearchText: String = ""
    private var lastFilter: Int = preferences.getInt(PREF_SORT_TYPE, View.NO_ID)

    private fun setAdapter(games: List<Game>) {
        val currentSearchText = binding.searchText.text.toString()
        val currentFilter = binding.filterButton.id

        val searchChanged = currentSearchText != lastSearchText
        val filterChanged = currentFilter != lastFilter

        if (searchChanged || filterChanged) {
            filterAndSearch(games)
            lastSearchText = currentSearchText
            lastFilter = currentFilter
        } else {
            ((binding.gridGames as? RecyclerView)?.adapter as? GameAdapter)?.submitList(games)
            gamesViewModel.setFilteredGames(games)
        }
    }

    private fun setupTopView() {
        binding.searchText.doOnTextChanged() { text: CharSequence?, _: Int, _: Int, _: Int ->
            if (text.toString().isNotEmpty()) {
                binding.clearButton.visibility = View.VISIBLE
            } else {
                binding.clearButton.visibility = View.INVISIBLE
            }
            filterAndSearch()
        }

        binding.clearButton.setOnClickListener { binding.searchText.setText("") }
        binding.searchBackground.setOnClickListener { focusSearch() }

        // Setup view button
        binding.viewButton.setOnClickListener { showViewMenu(it) }

        // Setup filter button
        binding.filterButton.setOnClickListener { view ->
            showFilterMenu(view)
        }

        // Setup settings button
        binding.settingsButton.setOnClickListener { navigateToSettings() }
    }

    private fun navigateToSettings() {
        val navController = findNavController()
        navController.navigate(R.id.action_gamesFragment_to_homeSettingsFragment)
    }

    private fun showViewMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_game_views, popup.menu)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape) {
            popup.menu.findItem(R.id.view_carousel)?.isVisible = false
        }

        val currentViewType = getCurrentViewType()
        when (currentViewType) {
            GameAdapter.VIEW_TYPE_LIST -> popup.menu.findItem(R.id.view_list).isChecked = true
            GameAdapter.VIEW_TYPE_GRID_COMPACT -> popup.menu.findItem(R.id.view_grid_compact).isChecked = true
            GameAdapter.VIEW_TYPE_GRID -> popup.menu.findItem(R.id.view_grid).isChecked = true
            GameAdapter.VIEW_TYPE_CAROUSEL -> popup.menu.findItem(R.id.view_carousel).isChecked = true
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.view_grid -> {
                    if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) onPause()
                    setCurrentViewType(GameAdapter.VIEW_TYPE_GRID)
                    applyGridGamesBinding()
                    item.isChecked = true
                    true
                }

                R.id.view_grid_compact -> {
                    if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) onPause()
                    setCurrentViewType(GameAdapter.VIEW_TYPE_GRID_COMPACT)
                    applyGridGamesBinding()
                    item.isChecked = true
                    true
                }

                R.id.view_list -> {
                    if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) onPause()
                    setCurrentViewType(GameAdapter.VIEW_TYPE_LIST)
                    applyGridGamesBinding()
                    item.isChecked = true
                    true
                }

                R.id.view_carousel -> {
                    if (!item.isChecked || getCurrentViewType() != GameAdapter.VIEW_TYPE_CAROUSEL) {
                        setCurrentViewType(GameAdapter.VIEW_TYPE_CAROUSEL)
                        applyGridGamesBinding()
                        item.isChecked = true
                        onResume()
                    }
                    true
                }

                else -> false
            }
        }

        popup.show()
    }

    private fun showFilterMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_game_filters, popup.menu)

        // Set checked state based on current filter
        when (currentFilter) {
            R.id.alphabetical -> popup.menu.findItem(R.id.alphabetical).isChecked = true
            R.id.filter_recently_played -> popup.menu.findItem(R.id.filter_recently_played).isChecked =
                true

            R.id.filter_recently_added -> popup.menu.findItem(R.id.filter_recently_added).isChecked =
                true
        }

        popup.setOnMenuItemClickListener { item ->
            currentFilter = item.itemId
            preferences.edit { putInt(PREF_SORT_TYPE, currentFilter) }
            filterAndSearch()
            true
        }

        popup.show()
    }

    // Track current filter
    private var currentFilter = View.NO_ID

    private fun filterAndSearch(baseList: List<Game> = gamesViewModel.games.value) {
        val filteredList: List<Game> = when (currentFilter) {
            R.id.alphabetical -> baseList.sortedBy { it.title }
            R.id.filter_recently_played -> {
                baseList.filter {
                    val lastPlayedTime = preferences.getLong(it.keyLastPlayedTime, 0L)
                    lastPlayedTime > (System.currentTimeMillis() - 24 * 60 * 60 * 1000)
                }.sortedByDescending { preferences.getLong(it.keyLastPlayedTime, 0L) }
            }
            R.id.filter_recently_added -> {
                baseList.filter {
                    val addedTime = preferences.getLong(it.keyAddedToLibraryTime, 0L)
                    addedTime > (System.currentTimeMillis() - 24 * 60 * 60 * 1000)
                }.sortedByDescending { preferences.getLong(it.keyAddedToLibraryTime, 0L) }
            }
            // Grouped by the folder each game lives in. The folder is read
            // from GameFolders; the Game objects themselves are upstream's.
            else -> runCatching {
                baseList.sortedWith(
                    compareBy(
                        { GameFolders.folderOf(it.path).lowercase(Locale.getDefault()) },
                        { it.title.lowercase(Locale.getDefault()) }
                    )
                )
            }.getOrElse {
                android.util.Log.e("Symbiosis", "groupByFolder failed", it)
                baseList
            }
        }

        val searchTerm = binding.searchText.text.toString().lowercase(Locale.getDefault())
        if (searchTerm.isEmpty()) {
            ((binding.gridGames as? RecyclerView)?.adapter as? GameAdapter)?.submitList(
                filteredList
            )
            gamesViewModel.setFilteredGames(filteredList)
            return
        }

        val searchAlgorithm = if (searchTerm.length > 1) Jaccard(2) else JaroWinkler()
        val sortedList = filteredList.mapNotNull { game ->
            val title = game.title.lowercase(Locale.getDefault())
            val score = searchAlgorithm.similarity(searchTerm, title)
            if (score > 0.03) {
                ScoredGame(score, game)
            } else {
                null
            }
        }.sortedByDescending { it.score }.map { it.item }

        ((binding.gridGames as? RecyclerView)?.adapter as? GameAdapter)?.submitList(sortedList)
        gamesViewModel.setFilteredGames(sortedList)
    }

    private inner class ScoredGame(val score: Double, val item: Game)

    private fun focusSearch() {
        binding.searchText.requestFocus()
        val imm = requireActivity()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        imm?.showSoftInput(binding.searchText, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun scrollToTop() {
        if (_binding != null) {
            (binding.gridGames as? CarouselRecyclerView)?.smoothScrollToPosition(0)
        }
    }

    private fun launchQLaunch() {
        try {
            val appletPath = NativeLibrary.getAppletLaunchPath(AppletInfo.QLaunch.entryId)
            if (appletPath.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    R.string.applets_error_applet,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            NativeLibrary.setCurrentAppletId(AppletInfo.QLaunch.appletId)

            val qlaunchGame = Game(
                title = getString(R.string.qlaunch_applet),
                path = appletPath
            )

            val action = HomeNavigationDirections.actionGlobalEmulationActivity(qlaunchGame)
            findNavController().navigate(action)
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to launch QLaunch: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateButtonsVisibility() {
        val showQLaunch = BooleanSetting.ENABLE_QLAUNCH_BUTTON.getBoolean()
        val showFolder = BooleanSetting.ENABLE_FOLDER_BUTTON.getBoolean()
        val isFirmwareAvailable = NativeLibrary.isFirmwareAvailable()

        val shouldShowQLaunch = showQLaunch && isFirmwareAvailable
        binding.launchQlaunch.visibility = if (shouldShowQLaunch) View.VISIBLE else View.GONE

        binding.addDirectory.visibility = if (showFolder) View.VISIBLE else View.GONE
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val spacingNavigation = resources.getDimensionPixelSize(R.dimen.spacing_navigation)
            resources.getDimensionPixelSize(R.dimen.spacing_navigation_rail)

            (binding.swipeRefresh as? SwipeRefreshLayout)?.setProgressViewEndTarget(
                false,
                barInsets.top + resources.getDimensionPixelSize(R.dimen.spacing_refresh_end)
            )

            val leftInset = barInsets.left + cutoutInsets.left
            val rightInset = barInsets.right + cutoutInsets.right
            val topInset = maxOf(barInsets.top, cutoutInsets.top)

            val mlpSwipe = binding.swipeRefresh.layoutParams as ViewGroup.MarginLayoutParams
            mlpSwipe.leftMargin = leftInset
            mlpSwipe.rightMargin = rightInset
            binding.swipeRefresh.layoutParams = mlpSwipe

            val mlpHeader = binding.header.layoutParams as ViewGroup.MarginLayoutParams

            // Store original margins only once
            if (originalHeaderTopMargin == null) {
                originalHeaderTopMargin = mlpHeader.topMargin
                originalHeaderRightMargin = mlpHeader.rightMargin
                originalHeaderLeftMargin = mlpHeader.leftMargin
            }

            // Always set margin as original + insets
            mlpHeader.leftMargin = (originalHeaderLeftMargin ?: 0) + leftInset
            mlpHeader.rightMargin = (originalHeaderRightMargin ?: 0) + rightInset
            mlpHeader.topMargin = (originalHeaderTopMargin ?: 0) + topInset + resources.getDimensionPixelSize(
                R.dimen.spacing_med
            )
            binding.header.layoutParams = mlpHeader

            binding.noticeText.updatePadding(bottom = spacingNavigation)

            binding.gridGames.updatePadding(
                top = resources.getDimensionPixelSize(R.dimen.spacing_med)
            )

            val mlpFab = binding.addDirectory.layoutParams as ViewGroup.MarginLayoutParams
            val fabPadding = resources.getDimensionPixelSize(R.dimen.spacing_large)
            mlpFab.leftMargin = leftInset + fabPadding
            mlpFab.bottomMargin = barInsets.bottom + fabPadding
            mlpFab.rightMargin = rightInset + fabPadding
            binding.addDirectory.layoutParams = mlpFab

            binding.launchQlaunch?.let { qlaunchButton ->
                val mlpQLaunch = qlaunchButton.layoutParams as ViewGroup.MarginLayoutParams
                mlpQLaunch.leftMargin = leftInset + fabPadding
                mlpQLaunch.bottomMargin = barInsets.bottom + fabPadding
                qlaunchButton.layoutParams = mlpQLaunch
            }

            val navInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val gestureInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemGestures())
            val bottomInset = maxOf(navInsets.bottom, gestureInsets.bottom, cutoutInsets.bottom)
            fallbackBottomInset = bottomInset
            (binding.gridGames as? CarouselRecyclerView)?.notifyInsetsReady(bottomInset)
            windowInsets
        }
}
