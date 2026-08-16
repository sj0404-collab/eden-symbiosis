// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import org.yuzu.yuzu_emu.utils.EngineLoader
import org.yuzu.yuzu_emu.utils.KenjiBridge

/**
 * Second player. Lives in the :kenji process so a fault in Kenji cannot
 * close the launcher. Eden stays in EmulationActivity in the main process.
 */
class KenjiPlayerActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_PATH = "kenji_path"
        const val EXTRA_TITLE = "kenji_title"

        fun intent(context: Context, path: String, title: String): Intent =
            Intent(context, KenjiPlayerActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private var pfd: ParcelFileDescriptor? = null
    private var loopThread: Thread? = null
    private var started = false
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "игра" }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surface = SurfaceView(this)
        surface.holder.addCallback(this)
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        status = TextView(this).apply {
            text = "Symbiosis · второе ядро\n$title"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(24, 48, 24, 16)
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val back = Button(this).apply {
            text = "Выйти"
            setOnClickListener { leave() }
        }
        root.addView(
            back,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { setMargins(24, 24, 24, 48) }
        )
        setContentView(root)

        if (path.isBlank()) {
            fail("нет пути к игре")
            return
        }
        if (EngineLoader.state(this, EngineLoader.Engine.KENJI) !is EngineLoader.State.Ready &&
            EngineLoader.state(this, EngineLoader.Engine.KENJI) !is EngineLoader.State.Builtin
        ) {
            fail("второе ядро не скачано")
            return
        }
        pfd = openRom(path)
        if (pfd == null) {
            fail("не открылся файл игры")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (started) return
        val fd = pfd?.fd ?: return fail("нет дескриптора")
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val ext = path.substringAfterLast('.', "nsp").lowercase().substringBefore('?')
        val w = holder.surfaceFrame.width().coerceAtLeast(128)
        val h = holder.surfaceFrame.height().coerceAtLeast(128)
        started = true
        loopThread = Thread({
            val prep = KenjiBridge.preparePlay(this)
            if (!prep.ok) {
                runOnUiThread { fail(prep.message) }
                return@Thread
            }
            val surf = KenjiBridge.attachSurface(holder.surface, w, h)
            if (!surf.ok) {
                runOnUiThread { fail(surf.message) }
                return@Thread
            }
            val load = KenjiBridge.loadGame(fd, ext)
            if (!load.ok) {
                runOnUiThread { fail(load.message) }
                return@Thread
            }
            runOnUiThread { status.text = "второе ядро · играет" }
            KenjiBridge.runLoop()
            runOnUiThread { leave() }
        }, "kenji-loop").also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        KenjiBridge.stopPlay()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        leave()
    }

    override fun onDestroy() {
        KenjiBridge.stopPlay()
        loopThread?.join(1500)
        KenjiBridge.unload()
        runCatching { pfd?.close() }
        pfd = null
        super.onDestroy()
    }

    private fun leave() {
        KenjiBridge.stopPlay()
        finish()
    }

    private fun fail(msg: String) {
        status.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        // Do not finish() instantly on a probe-style failure: the user must
        // see why. Auto-leave after a beat so the process does not linger.
        status.postDelayed({ finish() }, 2200)
    }

    private fun openRom(path: String): ParcelFileDescriptor? = runCatching {
        if (path.startsWith("/")) {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            contentResolver.openFileDescriptor(Uri.parse(path), "r")
        }
    }.getOrNull()
}
