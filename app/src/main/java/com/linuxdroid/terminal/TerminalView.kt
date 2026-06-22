package com.linuxdroid.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.linuxdroid.R
import com.linuxdroid.util.LinuxDroidLogger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * عرض الطرفية — يرسم محتوى [TerminalBuffer] على الشاشة ويدير الإدخال.
 *
 * المسؤوليات:
 * 1. حساب أبعاد الخلية (cell) بناءً على حجم الخط
 * 2. رسم الأحرف مع الألوان والسمات
 * 3. رسم المؤشر (cursor)
 * 4. استقبال الإدخال من لوحة المفاتيح وإرساله للطبقة الأعلى
 * 5. معالجة اللمس (تحديد النص، التمرير، القوائم)
 * 6. إعادة الحجم (resize) عند تدوير الجهاز
 *
 * الأداء: نستخدم Paint ثابت ونتجنب تخصيص الكائنات في onDraw.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    interface OnInputListener {
        fun onTextInput(text: String)
        fun onSpecialKey(key: SpecialKey, ctrl: Boolean, alt: Boolean, shift: Boolean): Boolean
    }

    enum class SpecialKey {
        ENTER, BACKSPACE, TAB, ESCAPE, ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
        HOME, END, PAGE_UP, PAGE_DOWN, DELETE, INSERT, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
    }

    var buffer: TerminalBuffer? = null
        set(value) {
            field = value
            if (value != null) recalculateSize()
            postInvalidate()
        }

    var inputListener: OnInputListener? = null

    // الخط والأبعاد
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 28f  // افتراضي، يُعدّل من الإعدادات
    }
    private val bgPaint = Paint()
    private val cursorPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val selectionPaint = Paint().apply { alpha = 60 }

    private var cellWidth: Float = 0f
    private var cellHeight: Float = 0f
    private var cellBaseline: Float = 0f
    private var padding = 8f

    private var visibleColumns = 80
    private var visibleRows = 24

    // الإعدادات
    var fontSize: Float = 28f
        set(value) {
            field = value
            textPaint.textSize = value
            recalculateSize()
            invalidate()
        }

    var colorScheme: ColorScheme = ColorScheme.DARK

    // الألوان الفعلية المحلولة
    private val ansiColors = IntArray(16)

    // التحديد
    private var selStartRow = -1
    private var selStartCol = -1
    private var selEndRow = -1
    private var selEndCol = -1
    private var isSelecting = false

    private val gestureDetector = GestureDetector(context, GestureListener())

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setupColors()
        recalculateSize()
    }

    private fun setupColors() {
        val res = context.resources
        ansiColors[0]  = res.getColor(R.color.ansi_black, null)
        ansiColors[1]  = res.getColor(R.color.ansi_red, null)
        ansiColors[2]  = res.getColor(R.color.ansi_green, null)
        ansiColors[3]  = res.getColor(R.color.ansi_yellow, null)
        ansiColors[4]  = res.getColor(R.color.ansi_blue, null)
        ansiColors[5]  = res.getColor(R.color.ansi_magenta, null)
        ansiColors[6]  = res.getColor(R.color.ansi_cyan, null)
        ansiColors[7]  = res.getColor(R.color.ansi_white, null)
        ansiColors[8]  = res.getColor(R.color.ansi_bright_black, null)
        ansiColors[9]  = res.getColor(R.color.ansi_bright_red, null)
        ansiColors[10] = res.getColor(R.color.ansi_bright_green, null)
        ansiColors[11] = res.getColor(R.color.ansi_bright_yellow, null)
        ansiColors[12] = res.getColor(R.color.ansi_bright_blue, null)
        ansiColors[13] = res.getColor(R.color.ansi_bright_magenta, null)
        ansiColors[14] = res.getColor(R.color.ansi_bright_cyan, null)
        ansiColors[15] = res.getColor(R.color.ansi_bright_white, null)
    }

    private fun recalculateSize() {
        val fm = textPaint.fontMetrics
        cellHeight = (fm.descent - fm.ascent).coerceAtLeast(1f)
        cellWidth = textPaint.measureText("M").coerceAtLeast(1f)
        cellBaseline = -fm.ascent

        val w = width.takeIf { it > 0 } ?: 1080
        val h = height.takeIf { it > 0 } ?: 1920
        visibleColumns = ((w - 2 * padding) / cellWidth).toInt().coerceAtLeast(1)
        visibleRows = ((h - 2 * padding) / cellHeight).toInt().coerceAtLeast(1)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateSize()
        buffer?.let { buf ->
            if (buf.columns != visibleColumns || buf.rows != visibleRows) {
                buffer = buf.resize(visibleColumns, visibleRows)
                onResizeListener?.invoke(visibleColumns, visibleRows)
            }
        }
    }

    var onResizeListener: ((Int, Int) -> Unit)? = null

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this, true)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (buffer == null) return super.onKeyDown(keyCode, event)
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val shift = event.isShiftPressed

        val special = when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> SpecialKey.ENTER
            KeyEvent.KEYCODE_DEL -> SpecialKey.BACKSPACE
            KeyEvent.KEYCODE_TAB -> SpecialKey.TAB
            KeyEvent.KEYCODE_ESCAPE -> SpecialKey.ESCAPE
            KeyEvent.KEYCODE_DPAD_UP -> SpecialKey.ARROW_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> SpecialKey.ARROW_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> SpecialKey.ARROW_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> SpecialKey.ARROW_RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> SpecialKey.HOME
            KeyEvent.KEYCODE_MOVE_END -> SpecialKey.END
            KeyEvent.KEYCODE_PAGE_UP -> SpecialKey.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> SpecialKey.PAGE_DOWN
            KeyEvent.KEYCODE_FORWARD_DEL -> SpecialKey.DELETE
            KeyEvent.KEYCODE_INSERT -> SpecialKey.INSERT
            KeyEvent.KEYCODE_F1 -> SpecialKey.F1
            KeyEvent.KEYCODE_F2 -> SpecialKey.F2
            KeyEvent.KEYCODE_F3 -> SpecialKey.F3
            KeyEvent.KEYCODE_F4 -> SpecialKey.F4
            KeyEvent.KEYCODE_F5 -> SpecialKey.F5
            KeyEvent.KEYCODE_F6 -> SpecialKey.F6
            KeyEvent.KEYCODE_F7 -> SpecialKey.F7
            KeyEvent.KEYCODE_F8 -> SpecialKey.F8
            KeyEvent.KEYCODE_F9 -> SpecialKey.F9
            KeyEvent.KEYCODE_F10 -> SpecialKey.F10
            KeyEvent.KEYCODE_F11 -> SpecialKey.F11
            KeyEvent.KEYCODE_F12 -> SpecialKey.F12
            else -> null
        }

        if (special != null) {
            return inputListener?.onSpecialKey(special, ctrl, alt, shift) ?: false
        }

        // أحرف عادية مع Ctrl
        val c = event.unicodeChar
        if (c != 0) {
            val s = String(Character.toChars(c))
            inputListener?.onTextInput(s)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val buf = buffer ?: return

        // الخلفية
        bgPaint.color = colorScheme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // رسم الخلايا
        for (row in 0 until buf.rows) {
            for (col in 0 until buf.columns) {
                val cell = buf.getCell(row, col) ?: continue
                val x = padding + col * cellWidth
                val y = padding + row * cellHeight

                // خلفية الخلية
                val bg = resolveBg(cell)
                if (bg != colorScheme.background) {
                    bgPaint.color = bg
                    canvas.drawRect(x, y, x + cellWidth, y + cellHeight, bgPaint)
                }

                // الحرف
                if (cell.char != ' ') {
                    textPaint.color = resolveFg(cell)
                    textPaint.isFakeBoldText = cell.bold
                    textPaint.isUnderlineText = cell.underline
                    textPaint.isStrikeThruText = false
                    canvas.drawText(cell.char.toString(), x, y + cellBaseline, textPaint)
                }
            }
        }

        // التحديد
        if (selStartRow >= 0) {
            drawSelection(canvas)
        }

        // المؤشر
        drawCursor(canvas, buf)
    }

    private fun resolveFg(cell: TerminalBuffer.Cell): Int {
        val base = if (cell.fgColor == TerminalBuffer.COLOR_DEFAULT)
            colorScheme.foreground else ansiColors[cell.fgColor.coerceIn(0, 15)]
        return if (cell.inverse) {
            if (cell.bgColor == TerminalBuffer.COLOR_DEFAULT)
                colorScheme.background else ansiColors[cell.bgColor.coerceIn(0, 15)]
        } else base
    }

    private fun resolveBg(cell: TerminalBuffer.Cell): Int {
        val base = if (cell.bgColor == TerminalBuffer.COLOR_DEFAULT)
            colorScheme.background else ansiColors[cell.bgColor.coerceIn(0, 15)]
        return if (cell.inverse) {
            if (cell.fgColor == TerminalBuffer.COLOR_DEFAULT)
                colorScheme.foreground else ansiColors[cell.fgColor.coerceIn(0, 15)]
        } else base
    }

    private fun drawSelection(canvas: Canvas) {
        val buf = buffer ?: return
        val (r1, c1, r2, c2) = normalizeSelection()
        selectionPaint.color = colorScheme.selection
        for (r in r1..r2) {
            val startC = if (r == r1) c1 else 0
            val endC = if (r == r2) c2 else buf.columns - 1
            val x1 = padding + startC * cellWidth
            val x2 = padding + (endC + 1) * cellWidth
            val y1 = padding + r * cellHeight
            val y2 = y1 + cellHeight
            canvas.drawRect(x1, y1, x2, y2, selectionPaint)
        }
    }

    private fun normalizeSelection(): IntArray {
        val r1: Int; val c1: Int; val r2: Int; val c2: Int
        if (selStartRow < selEndRow || (selStartRow == selEndRow && selStartCol <= selEndCol)) {
            r1 = selStartRow; c1 = selStartCol; r2 = selEndRow; c2 = selEndCol
        } else {
            r1 = selEndRow; c1 = selEndCol; r2 = selStartRow; c2 = selStartCol
        }
        return intArrayOf(r1, c1, r2, c2)
    }

    private fun drawCursor(canvas: Canvas, buf: TerminalBuffer) {
        if (buf.cursorRow !in 0 until buf.rows || buf.cursorCol !in 0 until buf.columns) return
        val x = padding + buf.cursorCol * cellWidth
        val y = padding + buf.cursorRow * cellHeight
        cursorPaint.color = colorScheme.cursor
        when (cursorStyle) {
            CursorStyle.BLOCK -> {
                cursorPaint.style = Paint.Style.FILL
                cursorPaint.alpha = 120
                canvas.drawRect(x, y, x + cellWidth, y + cellHeight, cursorPaint)
                cursorPaint.alpha = 255
            }
            CursorStyle.UNDERLINE -> {
                cursorPaint.style = Paint.Style.FILL
                canvas.drawRect(x, y + cellHeight - 3f, x + cellWidth, y + cellHeight, cursorPaint)
            }
            CursorStyle.BAR -> {
                cursorPaint.style = Paint.Style.FILL
                canvas.drawRect(x, y, x + 2f, y + cellHeight, cursorPaint)
            }
        }
    }

    var cursorStyle: CursorStyle = CursorStyle.BLOCK

    enum class CursorStyle { BLOCK, UNDERLINE, BAR }

    data class ColorScheme(
        val background: Int,
        val foreground: Int,
        val cursor: Int,
        val selection: Int
    ) {
        companion object {
            val DARK = ColorScheme(
                background = 0xFF0C0C0C.toInt(),
                foreground = 0xFFE0E0E0.toInt(),
                cursor = 0xFF4FC3F7.toInt(),
                selection = 0xFF4FC3F7.toInt()
            )
            val LIGHT = ColorScheme(
                background = 0xFFFAFAFA.toInt(),
                foreground = 0xFF212121.toInt(),
                cursor = 0xFF0288D1.toInt(),
                selection = 0xFF0288D1.toInt()
            )
            val SOLARIZED_DARK = ColorScheme(
                background = 0xFF002B36.toInt(),
                foreground = 0xFF839496.toInt(),
                cursor = 0xFFEEE8D5.toInt(),
                selection = 0xFF073642.toInt()
            )
            val DRACULA = ColorScheme(
                background = 0xFF282A36.toInt(),
                foreground = 0xFFF8F8F2.toInt(),
                cursor = 0xFFFF79C6.toInt(),
                selection = 0xFF6272A4.toInt()
            )
        }
    }

    inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onLongPress(e: MotionEvent) {
            // إظهار قائمة النسخ/اللصق
            performLongClick()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // التمرير السلس — يمكن إضافته لاحقاً
            return false
        }
    }

    /** يحدّث العرض عند تغيّر المحتوى */
    fun notifyChanged() {
        postInvalidate()
    }

    fun getVisibleColumns(): Int = visibleColumns
    fun getVisibleRows(): Int = visibleRows

    fun copySelection(): String {
        val buf = buffer ?: return ""
        if (selStartRow < 0) return ""
        val (r1, c1, r2, c2) = normalizeSelection()
        val sb = StringBuilder()
        for (r in r1..r2) {
            val startC = if (r == r1) c1 else 0
            val endC = if (r == r2) c2 else buf.columns - 1
            for (c in startC..endC) {
                sb.append(buf.getCell(r, c)?.char ?: ' ')
            }
            if (r < r2) sb.append('\n')
        }
        return sb.toString()
    }

    fun clearSelection() {
        selStartRow = -1; selStartCol = -1
        selEndRow = -1; selEndCol = -1
        invalidate()
    }
}

class TerminalInputConnection(
    private val view: TerminalView,
    fullEditor: Boolean
) : BaseInputConnection(view, fullEditor) {

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (text.isNotEmpty()) {
            view.inputListener?.onTextInput(text.toString())
        }
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0) {
            view.inputListener?.onSpecialKey(TerminalView.SpecialKey.BACKSPACE, false, false, false)
        }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            return view.onKeyDown(event.keyCode, event)
        }
        return super.sendKeyEvent(event)
    }
}
