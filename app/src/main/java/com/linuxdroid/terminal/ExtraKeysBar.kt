package com.linuxdroid.terminal

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.linuxdroid.R
import com.linuxdroid.util.PrefUtils

/**
 * شريط المفاتيح الإضافي — أزرار مفاتيح التحكم الأساسية فوق لوحة المفاتيح.
 *
 * يحتوي على:
 * - CTRL, ALT, SHIFT (toggle keys)
 * - ESC, TAB
 * - الأسهم (↑ ↓ ← →)
 * - HOME, END, PAGE UP, PAGE DOWN
 * - -, /, |, >, < (رموز شائعة)
 *
 * مفيد على الشاشات اللمسية حيث لا توجد لوحة مفاتيح فيزيائية.
 */
class ExtraKeysBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    interface OnKeyListener {
        fun onKey(key: Key, isDown: Boolean)
    }

    enum class Key {
        CTRL, ALT, SHIFT,
        ESC, TAB,
        ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
        HOME, END, PAGE_UP, PAGE_DOWN,
        MINUS, SLASH, PIPE, GREATER, LESS,
        DOLLAR, TILDE, ASTERISK, QUESTION
    }

    var keyListener: OnKeyListener? = null

    private var ctrlActive = false
    private var altActive = false
    private var shiftActive = false

    private val toggleKeys = mutableSetOf<Key>()

    init {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        buildButtons()
    }

    private fun buildButtons() {
        val keys = listOf(
            Key.CTRL, Key.ALT, Key.SHIFT,
            Key.ESC, Key.TAB,
            Key.ARROW_UP, Key.ARROW_DOWN, Key.ARROW_LEFT, Key.ARROW_RIGHT,
            Key.HOME, Key.END,
            Key.MINUS, Key.SLASH, Key.PIPE, Key.GREATER, Key.LESS,
            Key.DOLLAR, Key.TILDE, Key.ASTERISK, Key.QUESTION
        )

        for (key in keys) {
            val btn = Button(context).apply {
                text = displayLabel(key)
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                setPadding(8, 4, 8, 4)
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { handleKey(key) }
                setOnLongClickListener {
                    // الضغط المطوّل على CTRL/ALT/SHIFT يبقيهم مفعّلين
                    if (key in listOf(Key.CTRL, Key.ALT, Key.SHIFT)) {
                        toggleSticky(key, this)
                        true
                    } else false
                }
            }
            addView(btn)
        }
    }

    private fun displayLabel(key: Key): String = when (key) {
        Key.CTRL -> "CTRL"
        Key.ALT -> "ALT"
        Key.SHIFT -> "SHIFT"
        Key.ESC -> "ESC"
        Key.TAB -> "TAB"
        Key.ARROW_UP -> "↑"
        Key.ARROW_DOWN -> "↓"
        Key.ARROW_LEFT -> "←"
        Key.ARROW_RIGHT -> "→"
        Key.HOME -> "HOME"
        Key.END -> "END"
        Key.PAGE_UP -> "PGUP"
        Key.PAGE_DOWN -> "PGDN"
        Key.MINUS -> "-"
        Key.SLASH -> "/"
        Key.PIPE -> "|"
        Key.GREATER -> ">"
        Key.LESS -> "<"
        Key.DOLLAR -> "$"
        Key.TILDE -> "~"
        Key.ASTERISK -> "*"
        Key.QUESTION -> "?"
    }

    private fun handleKey(key: Key) {
        // اهتزاز عند الضغط (إن كان مفعّلاً)
        if (PrefUtils.getVibrateOnKey(context)) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        when (key) {
            Key.CTRL, Key.ALT, Key.SHIFT -> {
                // toggle للحظي
                keyListener?.onKey(key, true)
                // يُعاد ضغطه عند استخدام مفتاح آخر
            }
            else -> {
                keyListener?.onKey(key, true)
                // إعادة ضبط toggles بعد استخدام مفتاح عادي (غير sticky)
                resetToggleKeys()
            }
        }
    }

    private fun toggleSticky(key: Key, button: Button) {
        if (toggleKeys.contains(key)) {
            toggleKeys.remove(key)
            button.isActivated = false
        } else {
            toggleKeys.add(key)
            button.isActivated = true
        }
    }

    private fun resetToggleKeys() {
        // فقط الـ toggles غير الـ sticky
        for (i in 0 until childCount) {
            val btn = getChildAt(i) as? Button ?: continue
            btn.isActivated = false
        }
        toggleKeys.clear()
    }

    fun isCtrlActive(): Boolean = toggleKeys.contains(Key.CTRL)
    fun isAltActive(): Boolean = toggleKeys.contains(Key.ALT)
    fun isShiftActive(): Boolean = toggleKeys.contains(Key.SHIFT)
}
