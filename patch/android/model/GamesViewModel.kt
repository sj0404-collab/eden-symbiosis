// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.model

import android.net.Uri
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.utils.GameHelper
import org.yuzu.yuzu_emu.utils.NativeConfig
import java.util.concurrent.atomic.AtomicBoolean

class GamesViewModel : ViewModel() {
    val games: StateFlow<List<Game>> get() = _games
    private val _games = MutableStateFlow(emptyList<Game>())

    val isReloading: StateFlow<Boolean> get() = _isReloading
    private val _isReloading = MutableStateFlow(false)

    private val reloading = AtomicBoolean(false)

    val shouldSwapData: StateFlow<Boolean> get() = _shouldSwapData
    private val _shouldSwapData = MutableStateFlow(false)

    val shouldScrollToTop: StateFlow<Boolean> get() = _shouldScrollToTop
    private val _shouldScrollToTop = MutableStateFlow(false)

    val searchFocused: StateFlow<Boolean> get() = _searchFocused
    private val _searchFocused = MutableStateFlow(false)

    val shouldScrollAfterReload: StateFlow<Boolean> get() = _shouldScrollAfterReload
    private val _shouldScrollAfterReload = MutableStateFlow(false)

    private val _folders = MutableStateFlow(mutableListOf<GameDir>())
    val folders = _folders.asStateFlow()

    private val _filteredGames = MutableStateFlow<List<Game>>(emptyList())

    var lastScrollPosition: Int = 0

    init {
        // Ensure keys are loaded so that ROM metadata can be decrypted.
        NativeLibrary.reloadKeys()

        getGameDirsAndExternalContent()

        // ЛЕНИВЫЙ СТАРТ: показать сохранённый список, НЕ обходя папки.
        //
        // Здесь стояло reloadGames(firstStartup = true). Внутри неё
        // firstStartup лишь ПРЕДВАРИТЕЛЬНО показывает кэш, а сразу за
        // этим безусловно идёт setGames(GameHelper.getGames()) - полный
        // обход дерева с разбором заголовка каждого ROM. То есть кэш
        // существовал, но не экономил ничего: каждый запуск заново читал
        // и расшифровывал все файлы.
        //
        // Это и есть вылет при перезапуске: обход стартует в первые
        // миллисекунды, до готовности интерфейса, и любой сбой в нём
        // валит приложение прежде, чем его можно закрыть по-человечески.
        //
        // Теперь при старте берётся только кэш. Обход - по явной
        // команде: потянуть список вниз, добавить папку или кнопкой
        // «Искать игры».
        loadCachedGames()
    }

    /**
     * Список из кэша, без единого обращения к папкам.
     *
     * Кэш пишет GameHelper.getGames() в SharedPreferences, поэтому он
     * переживает и перезапуск, и смену корня данных. Существование
     * файлов НЕ проверяется: DocumentFile.exists() на каждую игру - это
     * снова запрос к хранилищу на каждый файл, то самое, чего мы
     * избегаем. Удалённая игра отвалится при попытке запуска или при
     * следующем ручном обходе.
     */
    private fun loadCachedGames() {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching {
                    PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)
                        .getStringSet(GameHelper.KEY_GAMES, emptySet())
                        ?.mapNotNull { entry ->
                            runCatching { Json.decodeFromString<Game>(entry) }.getOrNull()
                        }
                        ?: emptyList()
                }.getOrDefault(emptyList())
            }
            if (cached.isNotEmpty()) {
                setGames(cached)
                GameHelper.cachedGameList = cached.toMutableList()
            }
        }
    }

    fun setGames(games: List<Game>) {
        val sortedList = games.sortedWith(
            compareBy(
                { it.title.lowercase(Locale.getDefault()) },
                { it.path }
            )
        )

        _games.value = sortedList
    }

    fun setShouldSwapData(shouldSwap: Boolean) {
        _shouldSwapData.value = shouldSwap
    }

    fun setShouldScrollToTop(shouldScroll: Boolean) {
        _shouldScrollToTop.value = shouldScroll
    }

    fun setShouldScrollAfterReload(shouldScroll: Boolean) {
        _shouldScrollAfterReload.value = shouldScroll
    }

    fun setSearchFocused(searchFocused: Boolean) {
        _searchFocused.value = searchFocused
    }

    fun setFilteredGames(games: List<Game>) {
        _filteredGames.value = games
    }

    fun reloadGames(directoriesChanged: Boolean, firstStartup: Boolean = false) {
        // Запрос, пришедший во время идущего обхода, раньше просто
        // выбрасывался - `return`, и всё. А обход папок при старте длится
        // секунды: пользователь успевает выбрать папку ровно в это окно,
        // его запрос теряется, и папка снова "не появляется до
        // перезапуска". Второй случай той же жалобы.
        //
        // Теперь такой запрос запоминается и выполняется сразу после
        // текущего.
        if (!reloading.compareAndSet(false, true)) {
            rescanRequested.set(true)
            return
        }
        _isReloading.value = true

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (firstStartup) {
                        // Retrieve list of cached games
                        val storedGames =
                            PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)
                                .getStringSet(GameHelper.KEY_GAMES, emptySet())
                        if (storedGames!!.isNotEmpty()) {
                            val deserializedGames = mutableSetOf<Game>()
                            storedGames.forEach {
                                val game: Game
                                try {
                                    game = Json.decodeFromString(it)
                                } catch (e: Exception) {
                                    // We don't care about any errors related to parsing the game cache
                                    return@forEach
                                }

                                val gameExists =
                                    DocumentFile.fromSingleUri(
                                        YuzuApplication.appContext,
                                        Uri.parse(game.path)
                                    )?.exists()
                                if (gameExists == true) {
                                    deserializedGames.add(game)
                                }
                            }
                            setGames(deserializedGames.toList())
                        }
                    }

                    setGames(GameHelper.getGames())
                    _shouldScrollAfterReload.value = true

                    if (directoriesChanged) {
                        setShouldSwapData(true)
                    }
                } catch (e: Exception) {
                    // Обход не должен ронять приложение. Раньше исключение
                    // из GameHelper.getGames() - битый файл, отозванный
                    // доступ - уходило наверх из корутины.
                    //
                    // Unit в конце обязателен: Log.e возвращает Int, и без
                    // него try/catch становится выражением типа Any, а
                    // тогда Kotlin требует else у if внутри try.
                    android.util.Log.e("Symbiosis", "не удалось прочитать список игр", e)
                    Unit
                } finally {
                    reloading.set(false)
                    _isReloading.value = false
                }
            }

            // Кто-то просил обновиться, пока мы работали.
            if (rescanRequested.compareAndSet(true, false)) {
                reloadGames(directoriesChanged = true, firstStartup = false)
            }
        }
    }

    /** Запрос на обход, пришедший во время другого обхода. */
    private val rescanRequested = AtomicBoolean(false)

    fun addFolder(gameDir: GameDir, savedFromGameFragment: Boolean) =
        viewModelScope.launch {
            // The user is editing the list, so any remembered copy is now
            // stale. Keeping it resurrects removed folders on the next start
            // and makes re-adding one fail with "already added".
            org.yuzu.yuzu_emu.utils.SharedDataDirectory.forgetGameDirs()
            withContext(Dispatchers.IO) {
                when (gameDir.type) {
                    DirectoryType.GAME -> {
                        NativeConfig.addGameDir(gameDir)
                        // ПОЧЕМУ ПАПКА ПОЯВЛЯЛАСЬ ТОЛЬКО ПОСЛЕ ПЕРЕЗАПУСКА
                        //
                        // Здесь стояло getGameDirsAndExternalContent(
                        //     !isFirstTimeSetup)
                        // то есть: перечитать список игр, только если
                        // "первый запуск" уже пройден. Флаг снимался
                        // РОВНО В ОДНОМ месте - в finishSetup() мастера
                        // первичной настройки. Мастер из этой сборки
                        // удалён, значит finishSetup() не вызывается
                        // никогда, значит PREF_FIRST_APP_LAUNCH остаётся
                        // true навсегда, значит reloadGames не звался
                        // никогда.
                        //
                        // Папка при этом добавлялась в конфиг честно -
                        // поэтому после перезапуска игры и появлялись:
                        // при старте список читается сам. Выглядело как
                        // "приложение не видит папку, пока не
                        // перезапустишь", а на деле оно её видело и
                        // просто не обновляло экран.
                        //
                        // Условие убрано целиком. Пользователь только что
                        // выбрал папку - перечитать список нужно всегда,
                        // безусловно.
                        getGameDirsAndExternalContent(reloadList = true)
                    }
                    DirectoryType.EXTERNAL_CONTENT -> {
                        addExternalContentDir(gameDir.uriString)
                        NativeConfig.saveGlobalConfig()
                        getGameDirsAndExternalContent()
                    }
                }
            }

            if (savedFromGameFragment) {
                NativeConfig.saveGlobalConfig()
                Toast.makeText(
                    YuzuApplication.appContext,
                    R.string.add_directory_success,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    fun removeFolder(gameDir: GameDir) =
        viewModelScope.launch {
            // The user is editing the list, so any remembered copy is now
            // stale. Keeping it resurrects removed folders on the next start
            // and makes re-adding one fail with "already added".
            org.yuzu.yuzu_emu.utils.SharedDataDirectory.forgetGameDirs()
            withContext(Dispatchers.IO) {
                val gameDirs = _folders.value.toMutableList()
                val removedDirIndex = gameDirs.indexOf(gameDir)
                if (removedDirIndex != -1) {
                    gameDirs.removeAt(removedDirIndex)
                    when (gameDir.type) {
                        DirectoryType.GAME -> {
                            NativeConfig.setGameDirs(gameDirs.filter { it.type == DirectoryType.GAME }.toTypedArray())
                        }
                        DirectoryType.EXTERNAL_CONTENT -> {
                            removeExternalContentDir(gameDir.uriString)
                        }
                    }
                    getGameDirsAndExternalContent()
                }
            }
        }

    fun updateGameDirs() =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val gameDirs = _folders.value.filter { it.type == DirectoryType.GAME }
                NativeConfig.setGameDirs(gameDirs.toTypedArray())
                getGameDirsAndExternalContent()
            }
        }

    fun onOpenGameFoldersFragment() =
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                getGameDirsAndExternalContent()
            }
        }

    fun onCloseGameFoldersFragment() {
        NativeConfig.saveGlobalConfig()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                getGameDirsAndExternalContent(true)
            }
        }
    }

    private fun getGameDirsAndExternalContent(reloadList: Boolean = false) {
        val gameDirs = NativeConfig.getGameDirs().toMutableList()
        val externalContentDirs = NativeConfig.getExternalContentDirs().map {
            GameDir(it, false, DirectoryType.EXTERNAL_CONTENT)
        }
        gameDirs.addAll(externalContentDirs)
        _folders.value = gameDirs
        if (reloadList) {
            reloadGames(true)
        }
    }

    private fun addExternalContentDir(path: String) {
        val currentDirs = NativeConfig.getExternalContentDirs().toMutableList()
        if (!currentDirs.contains(path)) {
            currentDirs.add(path)
            NativeConfig.setExternalContentDirs(currentDirs.toTypedArray())
            NativeConfig.saveGlobalConfig()
        }
    }

    private fun removeExternalContentDir(path: String) {
        val currentDirs = NativeConfig.getExternalContentDirs().toMutableList()
        currentDirs.remove(path)
        NativeConfig.setExternalContentDirs(currentDirs.toTypedArray())
        NativeConfig.saveGlobalConfig()
    }
}
