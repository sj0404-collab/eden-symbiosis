// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.utils.EngineDownloader
import org.yuzu.yuzu_emu.utils.EngineLoader
import org.yuzu.yuzu_emu.utils.KenjiProbeService
import org.yuzu.yuzu_emu.utils.EnginePreference

/**
 * Pick which emulator core runs a game.
 *
 * WHY TWO ENGINES AT ALL
 *   Eden descends from yuzu, Kenji-NX from Ryujinx. They share no code and
 *   fail on different games: where one stumbles the other sometimes runs. This
 *   screen is the whole point of the fork - one app, either core, the same
 *   games, keys, firmware and saves.
 *
 * WHY ONE OF THEM IS DOWNLOADED
 *   Measured in our own CI, not estimated:
 *
 *     LibKenjinx.so       54.7 MB raw -> 19.6 MB inside an APK
 *     libyuzu-android.so  34.4 MB raw -> 12.3 MB
 *
 *   Both in one APK is about 47 MB, and the budget is 25. Eden ships inside;
 *   Kenji is fetched once, on demand, and the APK stays under the limit.
 *
 * The screen is built in code rather than XML on purpose: it is a short list
 * of rows, and a layout file would have to be kept in step with the patch
 * script by hand - which has already been forgotten twice in this project.
 */
class EnginesFragment : Fragment() {

    private lateinit var root: LinearLayout
    private var busy = false

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
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(16), bottom = bars.bottom + dp(32))
            insets
        }
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        val ctx = requireContext()
        root.removeAllViews()

        root.addView(heading("Ядра"))
        root.addView(
            note(
                "Два плеера. Основное ядро — в этом процессе. " +
                    "Второе (~55 МБ, Kenji-NX) скачивается и играет в процессе :kenji: " +
                    "если упадёт, лаунчер останется. В APK его нет — иначе сборка раздуется вдвое."
            )
        )

        val current = EnginePreference.selected(ctx)

        for (engine in EngineLoader.Engine.values()) {
            root.addView(engineRow(engine, engine == current))
        }

        if (!EngineLoader.deviceSupported()) {
            root.addView(
                note(
                    "Это устройство не 64-битное (arm64), поэтому скачиваемое " +
                        "ядро на нём не запустится. Доступен только Eden."
                )
            )
        }
    }

    private fun engineRow(engine: EngineLoader.Engine, selected: Boolean): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(12)
            layoutParams = lp
        }

        val state = EngineLoader.state(ctx, engine)
        val title = TextView(ctx).apply {
            text = if (selected) "${engine.label}  ✓ выбран" else engine.label
            textSize = 17f
        }
        box.addView(title)

        val describe = when (state) {
            is EngineLoader.State.Builtin ->
                "встроен в приложение · всегда доступен"
            is EngineLoader.State.Ready ->
                "скачан · ${state.bytes / 1048576} МБ в памяти устройства"
            is EngineLoader.State.Missing ->
                "не скачан · потребуется ${state.bytes / 1048576} МБ загрузки"
            is EngineLoader.State.Broken ->
                "не готов: ${state.reason}"
        }
        box.addView(note(describe))

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        // Selecting is only offered when the engine can actually start. A
        // button that sets a preference and then fails at launch is worse than
        // no button.
        val usable = state is EngineLoader.State.Builtin || state is EngineLoader.State.Ready
        if (usable && !selected) {
            buttons.addView(button("Выбрать") {
                EnginePreference.select(ctx, engine)
                render()
            })
        }
        if (usable) {
            buttons.addView(button("Запустить") {
                EnginePreference.select(ctx, engine)
                val last = org.yuzu.yuzu_emu.utils.LivePanel.rememberedGames()
                    .maxByOrNull { org.yuzu.yuzu_emu.utils.LivePanel.lastPlayedOf(it.path) }
                if (engine == EngineLoader.Engine.KENJI && last != null) {
                    startActivity(
                        org.yuzu.yuzu_emu.activities.KenjiPlayerActivity.intent(
                            ctx, last.path, last.title
                        )
                    )
                } else {
                    android.widget.Toast.makeText(
                        ctx,
                        if (last == null) "вернитесь на лаунчер и нажмите Запустить на игре"
                        else "ядро выбрано — на лаунчере нажмите Запустить",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            })
        }

        if (state is EngineLoader.State.Missing || state is EngineLoader.State.Broken) {
            if (EngineLoader.deviceSupported()) {
                val mb = (EngineLoader.KNOWN_SIZE[engine] ?: 0L) / 1048576
                buttons.addView(button("Скачать ($mb МБ)") { fetch(engine) })
            }
        }

        // Only offered once the core is present, and it is the honest test:
        // everything before this point proves the FILE is right, not that the
        // core will run on this device.
        if (state is EngineLoader.State.Ready) {
            buttons.addView(button("Проверить") { probe(engine) })
        }

        if (state is EngineLoader.State.Ready) {
            buttons.addView(button("Удалить") {
                if (selected) EnginePreference.select(ctx, EngineLoader.Engine.EDEN)
                EngineLoader.remove(ctx, engine)
                render()
            })
        }

        if (buttons.childCount > 0) box.addView(buttons)
        return box
    }

    private fun fetch(engine: EngineLoader.Engine) {
        if (busy) return
        busy = true

        val ctx = requireContext()
        val status = note("Скачиваю…")
        root.addView(status)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                EngineDownloader.download(ctx, engine) { done, total ->
                    // Progress arrives on the IO thread; the view must be
                    // touched on the main one. Posting to the view's own
                    // handler keeps this correct without another coroutine
                    // per chunk.
                    val text = if (total > 0) {
                        "Скачиваю… ${done / 1048576} из ${total / 1048576} МБ"
                    } else {
                        "Скачиваю… ${done / 1048576} МБ"
                    }
                    status.post { status.text = text }
                }
            }
            busy = false
            if (!result.ok) {
                status.text = result.message
            } else {
                // Load it straight away rather than at the next launch: if the
                // core is going to be refused, the person who just waited for
                // 55 MB should find out now.
                val err = withContext(Dispatchers.IO) { EngineLoader.load(ctx, engine) }
                if (err != null) {
                    status.text = "Скачано, но не загрузилось: $err"
                    return@launch
                }
                render()
            }
        }
    }

    /**
     * Start the core in the isolated :kenji process and report back.
     *
     * The only test that means anything: a core can download intact, verify
     * against its hash and still abort on this particular device. Because it
     * runs in another process, an abort is reported here as a sentence instead
     * of closing the app.
     */
    private fun probe(engine: EngineLoader.Engine) {
        if (busy) return
        busy = true
        val status = note("Проверяю ядро в отдельном процессе…")
        root.addView(status)

        KenjiProbeService.probe(requireContext()) { ok, message ->
            busy = false
            status.text = if (ok) "Ядро запускается: $message" else "Не запустилось: $message"
        }
    }

    private fun heading(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 20f
        setPadding(0, 0, 0, dp(8))
    }

    private fun note(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f
        alpha = 0.75f
        setPadding(0, dp(2), 0, dp(8))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(requireContext()).apply {
        this.text = text
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.rightMargin = dp(8)
        layoutParams = lp
        setOnClickListener { onClick() }
    }
}
