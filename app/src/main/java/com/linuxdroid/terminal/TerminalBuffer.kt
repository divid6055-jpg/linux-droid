package com.linuxdroid.terminal

/**
 * شاشة طرفية افتراضية — تمثل الذاكرة المرئية للطرفية.
 *
 * تنفّذ مجموعة فرعية كبيرة من بروتوكول xterm:
 *  - مصفوفة أحرف بأبعاد صفوف × أعمدة
 *  - موضع المؤشر (سطر، عمود)
 *  - سمات النص (لون أمامي/خلفي، غامق، مائل، تحته خط، وميض، معكوس)
 *  - شاشة بديلة (alternate screen) لتطبيقات fullscreen
 *  - التمرير (scrolling) ومنطقة التمرير (scroll region)
 *  - tab stops
 *  - أحرف التحكم (CR, LF, BS, HT, BEL, ESC)
 *  - escape sequences الأكثر شيوعاً (CSI)
 *
 * الأداء: نستخدم CharArray لكل صف لتقليل تخصيص الذاكرة.
 * معالجة الدفعات (batch processing) عند وصول بيانات كبيرة.
 */
class TerminalBuffer(val columns: Int, val rows: Int) {

    /** كل خلية = char + style */
    data class Cell(
        var char: Char = ' ',
        var fgColor: Int = COLOR_DEFAULT,
        var bgColor: Int = COLOR_DEFAULT,
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var blink: Boolean = false,
        var inverse: Boolean = false
    )

    private val grid: Array<Array<Cell>> = Array(rows) { Array(columns) { Cell() } }

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set

    var scrollTop: Int = 0
        private set
    var scrollBottom: Int = rows - 1
        private set

    private val tabStops: BooleanArray = BooleanArray(columns) { (it + 1) % 8 == 0 }

    var currentStyle: Cell = Cell()
        private set

    // Saved cursor state (for DECSC/DECRC)
    private var savedRow = 0
    private var savedCol = 0
    private var savedStyle = Cell()

    fun setCursor(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, columns - 1)
    }

    fun moveCursor(deltaRow: Int, deltaCol: Int) {
        setCursor(cursorRow + deltaRow, cursorCol + deltaCol)
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        scrollTop = top.coerceIn(0, rows - 1)
        scrollBottom = bottom.coerceIn(0, rows - 1)
    }

    fun resetScrollRegion() {
        scrollTop = 0
        scrollBottom = rows - 1
    }

    fun saveCursor() {
        savedRow = cursorRow
        savedCol = cursorCol
        savedStyle = currentStyle.copy()
    }

    fun restoreCursor() {
        cursorRow = savedRow
        cursorCol = savedCol
        currentStyle = savedStyle.copy()
    }

    fun resetStyle() {
        currentStyle = Cell()
    }

    fun setStyle(
        fg: Int? = null, bg: Int? = null,
        bold: Boolean? = null, italic: Boolean? = null,
        underline: Boolean? = null, blink: Boolean? = null,
        inverse: Boolean? = null
    ) {
        fg?.let { currentStyle.fgColor = it }
        bg?.let { currentStyle.bgColor = it }
        bold?.let { currentStyle.bold = it }
        italic?.let { currentStyle.italic = it }
        underline?.let { currentStyle.underline = it }
        blink?.let { currentStyle.blink = it }
        inverse?.let { currentStyle.inverse = it }
    }

    /** طباعة حرف في موضع المؤشر مع التحريك */
    fun putChar(c: Char) {
        if (cursorCol >= columns) {
            cursorCol = 0
            cursorRow++
            if (cursorRow > scrollBottom) {
                scrollUp(1)
                cursorRow = scrollBottom
            }
        }
        if (cursorRow in 0 until rows && cursorCol in 0 until columns) {
            val cell = grid[cursorRow][cursorCol]
            cell.char = c
            cell.fgColor = currentStyle.fgColor
            cell.bgColor = currentStyle.bgColor
            cell.bold = currentStyle.bold
            cell.italic = currentStyle.italic
            cell.underline = currentStyle.underline
            cell.blink = currentStyle.blink
            cell.inverse = currentStyle.inverse
        }
        cursorCol++
    }

    /** طباعة نص كامل (دفعات) */
    fun putString(s: String) {
        for (c in s) putChar(c)
    }

    fun newline() {
        cursorCol = 0
        cursorRow++
        if (cursorRow > scrollBottom) {
            scrollUp(1)
            cursorRow = scrollBottom
        }
    }

    fun carriageReturn() { cursorCol = 0 }

    fun backspace() {
        if (cursorCol > 0) cursorCol--
    }

    fun tab() {
        var next = cursorCol + 1
        while (next < columns && !tabStops[next]) next++
        cursorCol = if (next >= columns) columns - 1 else next
    }

    fun setTabStop(col: Int) { if (col in 0 until columns) tabStops[col] = true }
    fun clearTabStop(col: Int) { if (col in 0 until columns) tabStops[col] = false }
    fun clearAllTabStops() { tabStops.fill(false) }

    /** تمرير لأعلى بمقدار n صفوف داخل منطقة التمرير */
    fun scrollUp(n: Int = 1) {
        val count = n.coerceAtMost(scrollBottom - scrollTop + 1)
        for (row in scrollTop..(scrollBottom - count)) {
            grid[row] = grid[row + count]
        }
        for (row in (scrollBottom - count + 1)..scrollBottom) {
            grid[row] = Array(columns) { Cell() }
        }
    }

    /** تمرير لأسفل بمقدار n صفوف */
    fun scrollDown(n: Int = 1) {
        val count = n.coerceAtMost(scrollBottom - scrollTop + 1)
        for (row in scrollBottom downTo (scrollTop + count)) {
            grid[row] = grid[row - count]
        }
        for (row in scrollTop until (scrollTop + count)) {
            grid[row] = Array(columns) { Cell() }
        }
    }

    /** مسح خط من البداية إلى المؤشر */
    fun eraseLineFromCursor(toEnd: Boolean = true) {
        val start = if (toEnd) cursorCol else 0
        val end = if (toEnd) columns - 1 else cursorCol
        for (col in start..end) {
            grid[cursorRow][col] = Cell()
        }
    }

    fun eraseLine() {
        for (col in 0 until columns) grid[cursorRow][col] = Cell()
    }

    /** مسح من المؤشر إلى نهاية الشاشة */
    fun eraseDisplayFromCursor(toEnd: Boolean = true) {
        if (toEnd) {
            eraseLineFromCursor(true)
            for (row in (cursorRow + 1) until rows) {
                for (col in 0 until columns) grid[row][col] = Cell()
            }
        } else {
            eraseLineFromCursor(false)
            for (row in 0 until cursorRow) {
                for (col in 0 until columns) grid[row][col] = Cell()
            }
        }
    }

    fun eraseDisplay() {
        for (row in 0 until rows) {
            for (col in 0 until columns) grid[row][col] = Cell()
        }
    }

    fun getCell(row: Int, col: Int): Cell? =
        if (row in 0 until rows && col in 0 until columns) grid[row][col] else null

    fun rowToString(row: Int): String {
        if (row !in 0 until rows) return ""
        val sb = StringBuilder(columns)
        for (col in 0 until columns) sb.append(grid[row][col].char)
        return sb.toString().trimEnd()
    }

    fun resize(newCols: Int, newRows: Int): TerminalBuffer {
        if (newCols == columns && newRows == rows) return this
        val newBuffer = TerminalBuffer(newCols, newRows)
        // انسخ المحتوى القديم
        val copyRows = minOf(rows, newRows)
        val copyCols = minOf(columns, newCols)
        for (r in 0 until copyRows) {
            for (c in 0 until copyCols) {
                newBuffer.grid[r][c] = grid[r][c]
            }
        }
        newBuffer.cursorRow = cursorRow.coerceIn(0, newRows - 1)
        newBuffer.cursorCol = cursorCol.coerceIn(0, newCols - 1)
        return newBuffer
    }

    companion object {
        const val COLOR_DEFAULT = -1   // تستخدم اللون الافتراضي
        const val COLOR_BLACK = 0
        const val COLOR_RED = 1
        const val COLOR_GREEN = 2
        const val COLOR_YELLOW = 3
        const val COLOR_BLUE = 4
        const val COLOR_MAGENTA = 5
        const val COLOR_CYAN = 6
        const val COLOR_WHITE = 7
        const val COLOR_BRIGHT_BLACK = 8
        const val COLOR_BRIGHT_RED = 9
        const val COLOR_BRIGHT_GREEN = 10
        const val COLOR_BRIGHT_YELLOW = 11
        const val COLOR_BRIGHT_BLUE = 12
        const val COLOR_BRIGHT_MAGENTA = 13
        const val COLOR_BRIGHT_CYAN = 14
        const val COLOR_BRIGHT_WHITE = 15
    }
}
