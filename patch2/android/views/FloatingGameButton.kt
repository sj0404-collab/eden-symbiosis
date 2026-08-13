// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.views

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A small draggable button that floats over the game.
 *
 * WHY IT EXISTS
 *   Reaching the in-game menu means swiping the drawer open, which covers the
 *   picture and puts the menu in the way of whatever is happening on screen.
 *   This is a thumb-sized alternative that opens a short list in place.
 *
 * THE ONE RULE: NOTHING HERE PAUSES THE GAME
 *   Checked in upstream before writing a line. Eden's drawer does NOT pause
 *   emulation on its own - onDrawerOpened only changes lock mode, focus and
 *   the per-game config. Pausing happens in exactly one place,
 *   R.id.menu_pause_emulation, which calls pauseEmulationAndCaptureFrame().
 *
 *   So every action offered here is one that leaves emulation running, and
 *   that entry is deliberately absent. Nothing in this file calls
 *   pauseEmulation, and nothing calls into the drawer's pause path.
 *
 * WHAT IT DOES NOT DO
 *   No settings are written, no global state is touched. The only thing it
 *   remembers is where the button was left on screen, in the app's own
 *   preferences - so it does not jump back to the corner on every launch.
 */
class FloatingGameButton(context: Context) : FrameLayout(context) {

    /** Actions the host fragment supplies. All must be pause-free. */
    class Actions(
        val openMenu: () -> Unit,
        val toggleControls: () -> Unit,
        val controlsShown: () -> Boolean,
        val keepInMemory: () -> Boolean,
        val setKeepInMemory: (Boolean) -> Unit,
    )

    private var actions: Actions? = null
    private val handle: TextView
    private var menu: LinearLayout? = null

    // Drag bookkeeping. A press that moves less than the slop is a tap; more
    // and it becomes a drag, so a shaky thumb does not open the menu by
    // accident while moving the button - or move the button while tapping.
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private val slop = dp(10).toFloat()

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    init {
        layoutParams = LayoutParams(dp(52), dp(52)).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        handle = TextView(context).apply {
            text = "☰"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            // Drawn in code rather than as a drawable resource: one fewer file
            // for apply_patch2 to copy, and one fewer thing to forget.
            background = RoundRect(0x99000000.toInt(), dp(26).toFloat())
            alpha = 0.85f
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(handle)
        isClickable = true
    }

    fun attach(parent: ViewGroup, actions: Actions) {
        this.actions = actions
        if (this.parent == null) parent.addView(this)
        post { restorePosition() }
    }

    fun detach() {
        closeMenu()
        (parent as? ViewGroup)?.removeView(this)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                startX = x; startY = y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                if (dragging) {
                    val p = parent as? ViewGroup ?: return true
                    // Clamped so the button cannot be dragged off screen and
                    // become unreachable.
                    x = (startX + dx).coerceIn(0f, (p.width - width).toFloat())
                    y = (startY + dy).coerceIn(0f, (p.height - height).toFloat())
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    savePosition()
                } else {
                    if (menu == null) openMenu() else closeMenu()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun openMenu() {
        val host = parent as? ViewGroup ?: return
        val a = actions ?: return
        closeMenu()

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = RoundRect(0xE6111118.toInt(), dp(12).toFloat())
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

        // Deliberately short, and deliberately without "Pause". Every entry
        // leaves the game running.
        fun row(label: String, onTap: () -> Unit) {
            list.addView(TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener {
                    closeMenu()
                    onTap()
                }
            })
        }

        row(if (a.controlsShown()) "Скрыть кнопки" else "Показать кнопки") { a.toggleControls() }
        row("Меню игры") { a.openMenu() }

        // Whether the emulator holds itself in memory. On, Android evicts
        // other apps to make room and the browser reloads afterwards; off,
        // they survive, but a long spell in the background can cost the
        // session. Shown here because it is a decision about the phone, not
        // about the emulated machine, and it takes effect next launch.
        val keeping = a.keepInMemory()
        row(if (keeping) "Не держать в памяти" else "Держать в памяти") {
            a.setKeepInMemory(!keeping)
        }

        row("Убрать эту кнопку") { detach() }

        host.addView(list)
        list.post {
            // Placed beside the button, flipped to whichever side has room, so
            // the menu is never half off screen.
            val wantX = if (x + width + list.width <= host.width) x + width + dp(6)
                        else x - list.width - dp(6)
            list.x = wantX.coerceIn(0f, (host.width - list.width).toFloat().coerceAtLeast(0f))
            list.y = y.coerceIn(0f, (host.height - list.height).toFloat().coerceAtLeast(0f))
        }
        menu = list
    }

    private fun closeMenu() {
        menu?.let { (parent as? ViewGroup)?.removeView(it) }
        menu = null
    }

    private fun savePosition() {
        prefs.edit()
            .putFloat(KEY_X, x)
            .putFloat(KEY_Y, y)
            .apply()
    }

    private fun restorePosition() {
        val p = parent as? ViewGroup ?: return
        // Default: right edge, a third of the way down - out of the way of
        // both the drawer edge and the on-screen sticks.
        val defaultX = (p.width - width - dp(12)).toFloat().coerceAtLeast(0f)
        val defaultY = (p.height / 3f).coerceAtLeast(0f)
        x = prefs.getFloat(KEY_X, defaultX).coerceIn(0f, (p.width - width).toFloat().coerceAtLeast(0f))
        y = prefs.getFloat(KEY_Y, defaultY).coerceIn(0f, (p.height - height).toFloat().coerceAtLeast(0f))
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).roundToInt()

    /** A rounded rectangle without a drawable resource file. */
    private class RoundRect(private val colour: Int, private val radius: Float) :
        android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = colour
        }

        override fun draw(canvas: android.graphics.Canvas) {
            canvas.drawRoundRect(
                android.graphics.RectF(bounds), radius, radius, paint
            )
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in the platform, still abstract")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val KEY_X = "symbiosis_float_x"
        private const val KEY_Y = "symbiosis_float_y"
    }
}
