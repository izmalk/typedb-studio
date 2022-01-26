/*
 * Copyright (C) 2021 Vaticle
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.vaticle.typedb.studio.view.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.isTypedEvent
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType.Companion.Move
import androidx.compose.ui.input.pointer.PointerEventType.Companion.Press
import androidx.compose.ui.input.pointer.PointerEventType.Companion.Release
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vaticle.typedb.studio.state.project.File
import com.vaticle.typedb.studio.view.common.Label
import com.vaticle.typedb.studio.view.common.component.ContextMenu
import com.vaticle.typedb.studio.view.common.component.Icon
import com.vaticle.typedb.studio.view.common.component.LazyColumn
import com.vaticle.typedb.studio.view.common.component.Separator
import com.vaticle.typedb.studio.view.common.theme.Theme
import com.vaticle.typedb.studio.view.common.theme.Theme.toDP
import com.vaticle.typedb.studio.view.editor.KeyMapping.Command
import com.vaticle.typedb.studio.view.editor.KeyMapping.Companion.CURRENT_KEY_MAPPING
import java.awt.event.MouseEvent.BUTTON1
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object TextEditor2 {

    private const val LINE_HEIGHT = 1.56f
    private val LINE_GAP = 2.dp
    private val AREA_PADDING_HORIZONTAL = 6.dp
    private val DEFAULT_FONT_WIDTH = 12.dp
    private val CURSOR_LINE_PADDING = 2.dp

    internal data class Cursor(val row: Int, val col: Int) : Comparable<Cursor> {
        override fun compareTo(other: Cursor): Int {
            return when (this.row) {
                other.row -> this.col.compareTo(other.col)
                else -> this.row.compareTo(other.row)
            }
        }

        override fun toString(): String {
            return "Cursor (row: $row, col: $col)"
        }
    }

    internal class Selection(val start: Cursor, endInit: Cursor) {
        var end: Cursor by mutableStateOf(endInit)
        val min: Cursor get() = if (start < end) start else end
        val max: Cursor get() = if (end > start) end else start

        override fun toString(): String {
            val startStatus = if (start == min) "min" else "max"
            val endStatus = if (end == max) "max" else "min"
            return "Selection {start: $start [$startStatus], end: $end[$endStatus]}"
        }
    }

    internal sealed interface Operation {

        data class Insertion(val cursor: Cursor, val texts: List<String>) : Operation {
            fun invert(): Deletion {
                assert(texts.isNotEmpty())
                val endRow = cursor.row + texts.size - 1
                val endCol = when {
                    texts.size > 1 -> texts[texts.size - 1].length
                    else -> cursor.col + texts[0].length
                }
                return Deletion(Selection(cursor, Cursor(endRow, endCol)))
            }
        }

        data class Deletion(val selection: Selection) : Operation {
            fun invert(deletedText: List<String>): Insertion {
                assert(deletedText.isNotEmpty())
                return Insertion(selection.min, deletedText)
            }
        }
    }

    class State internal constructor(
        internal val file: File,
        internal val fontBase: TextStyle,
        internal val lineHeight: Dp,
        private val clipboard: ClipboardManager,
        private val coroutineScope: CoroutineScope,
        initDensity: Float,
    ) {
        internal val content: SnapshotStateList<String> get() = file.content
        internal val textLayouts: SnapshotStateList<TextLayoutResult?> = mutableStateListOf<TextLayoutResult?>().apply {
            addAll(List(file.content.size) { null })
        }
        internal val contextMenu = ContextMenu.State()
        internal val verScroller = LazyColumn.createScrollState(lineHeight, file.content.size)
        internal var horScroller = ScrollState(0)
        internal var lineCount: Int by mutableStateOf(file.content.size)
        internal var width by mutableStateOf(0.dp)
        internal var cursor: Cursor by mutableStateOf(Cursor(0, 0))
        internal var selection: Selection? by mutableStateOf(null)
        internal var isSelecting: Boolean by mutableStateOf(false)
        internal var density: Float by mutableStateOf(initDensity)
        private var textAreaRect: Rect by mutableStateOf(Rect.Zero)
        private var undoStack: ArrayDeque<Operation> = ArrayDeque()
        private var redoStack: ArrayDeque<Operation> = ArrayDeque()

        private fun createCursor(x: Int, y: Int): Cursor {
            val relX = x - textAreaRect.left + toDP(horScroller.value, density).value
            val relY = y - textAreaRect.top + verScroller.offset.value
            val row = floor(relY / lineHeight.value).toInt().coerceIn(0, lineCount - 1)
            val offsetInLine = Offset(relX * density, (relY - (row * lineHeight.value)) * density)
            val col = textLayouts[row]?.getOffsetForPosition(offsetInLine) ?: 0
            return Cursor(row, col)
        }

        internal fun updateTextAreaCoord(rawPosition: Rect) {
            textAreaRect = Rect(
                left = toDP(rawPosition.left, density).value + AREA_PADDING_HORIZONTAL.value,
                top = toDP(rawPosition.top, density).value,
                right = toDP(rawPosition.right, density).value - AREA_PADDING_HORIZONTAL.value,
                bottom = toDP(rawPosition.bottom, density).value
            )
        }

        internal fun increaseWidth(newRawWidth: Int) {
            val newWidth = toDP(newRawWidth, density)
            if (newWidth > width) width = newWidth
        }

        internal fun updateCursor(x: Int, y: Int, isSelecting: Boolean) {
            updateCursor(createCursor(x, y), isSelecting, false)
        }

        private fun updateCursor(newCursor: Cursor, isSelecting: Boolean, mayScroll: Boolean = true) {
            if (isSelecting) {
                if (selection == null) selection = Selection(cursor, newCursor)
                else selection!!.end = newCursor
            } else selection = null
            cursor = newCursor
            if (mayScroll) mayScrollToCursor()
        }

        internal fun updateCursorIfOutOfSelection(x: Int, y: Int) {
            val newCursor = createCursor(x, y)
            if (selection == null || newCursor < selection!!.min || newCursor > selection!!.max) {
                updateCursor(newCursor, false)
            }
        }

        internal fun updateSelection(x: Int, y: Int) {
            if (isSelecting) {
                var newCursor = createCursor(x, y)
                val border = textAreaRect.left - toDP(horScroller.value, density).value - AREA_PADDING_HORIZONTAL.value
                if (x < border && selection != null && newCursor >= selection!!.start) {
                    newCursor = createCursor(x, y + lineHeight.value.toInt())
                }
                if (newCursor != cursor) {
                    if (selection == null) selection = Selection(cursor, newCursor)
                    else selection!!.end = newCursor
                    cursor = newCursor
                }
                mayScrollToCoordinate(x, y)
            }
        }

        private fun mayScrollToCursor() {
            val cursorRect = textLayouts[cursor.row]?.let { it.getCursorRect(cursor.col) } ?: Rect(0f, 0f, 0f, 0f)
            val x = textAreaRect.left + toDP(cursorRect.left - horScroller.value, density).value
            val y = textAreaRect.top + (lineHeight.value * (cursor.row + 0.5f)) - verScroller.offset.value
            mayScrollToCoordinate(x.toInt(), y.toInt(), lineHeight.value.toInt() * 2)
        }

        private fun mayScrollToCoordinate(x: Int, y: Int, padding: Int = 0) {
            val left = textAreaRect.left.toInt() + padding
            val right = textAreaRect.right.toInt() - padding
            val top = textAreaRect.top.toInt() + padding
            val bottom = textAreaRect.bottom.toInt() - padding
            if (x < left) coroutineScope.launch {
                horScroller.scrollTo(horScroller.value + ((x - left) * density).toInt())
            } else if (x > right) coroutineScope.launch {
                horScroller.scrollTo(horScroller.value + ((x - right) * density).toInt())
            }
            if (y < top) verScroller.updateOffset((y - top).dp)
            else if (y > bottom) verScroller.updateOffset((y - bottom).dp)
        }

        internal fun processKeyEvent(event: KeyEvent): Boolean {
            return if (event.isTypedEvent) {
                insertText(event.awtEvent.keyChar.toString())
                true
            } else if (event.type != KeyEventType.KeyDown) false
            else CURRENT_KEY_MAPPING.map(event)?.let { processCommand(it); true } ?: false
        }

        private fun processCommand(command: Command) {
            when (command) {
                Command.MOVE_CURSOR_LEFT_CHAR -> moveCursorPrevByChar() // because we only display left to right
                Command.MOVE_CURSOR_RIGHT_CHAR -> moveCursorNextByChar() // because we only display left to right
                Command.MOVE_CURSOR_LEFT_WORD -> moveCursorPrevByWord() // because we only display left to right
                Command.MOVE_CURSOR_RIGHT_WORD -> moveCursorNexBytWord() // because we only display left to right
                Command.MOVE_CURSOR_PREV_PARAGRAPH -> moveCursorPrevByParagraph()
                Command.MOVE_CURSOR_NEXT_PARAGRAPH -> moveCursorNextByParagraph()
                Command.MOVE_CURSOR_LEFT_LINE -> moveCursorToStartOfLine() // because we only display left to right
                Command.MOVE_CURSOR_RIGHT_LINE -> moveCursorToEndOfLine() // because we only display left to right
                Command.MOVE_CURSOR_START_LINE -> moveCursorToStartOfLine()
                Command.MOVE_CURSOR_END_LINE -> moveCursorToEndOfLine()
                Command.MOVE_CURSOR_UP_LINE -> moveCursorUpByLine()
                Command.MOVE_CURSOR_DOWN_LINE -> moveCursorDownByLine()
                Command.MOVE_CURSOR_UP_PAGE -> moveCursorUpByPage()
                Command.MOVE_CURSOR_DOWN_PAGE -> moveCursorDownByPage()
                Command.MOVE_CURSOR_HOME -> moveCursorToHome()
                Command.MOVE_CURSOR_END -> moveCursorToEnd()
                Command.SELECT_LEFT_CHAR -> moveCursorPrevByChar(true) // because we only display left to right
                Command.SELECT_RIGHT_CHAR -> moveCursorNextByChar(true) // because we only display left to right
                Command.SELECT_LEFT_WORD -> moveCursorPrevByWord(true) // because we only display left to right
                Command.SELECT_RIGHT_WORD -> moveCursorNexBytWord(true) // because we only display left to right
                Command.SELECT_PREV_PARAGRAPH -> moveCursorPrevByParagraph(true)
                Command.SELECT_NEXT_PARAGRAPH -> moveCursorNextByParagraph(true)
                Command.SELECT_LEFT_LINE -> moveCursorToStartOfLine(true) // because we only display left to right
                Command.SELECT_RIGHT_LINE -> moveCursorToEndOfLine(true) // because we only display left to right
                Command.SELECT_START_LINE -> moveCursorToStartOfLine(true)
                Command.SELECT_END_LINE -> moveCursorToEndOfLine(true)
                Command.SELECT_UP_LINE -> moveCursorUpByLine(true)
                Command.SELECT_DOWN_LINE -> moveCursorDownByLine(true)
                Command.SELECT_UP_PAGE -> moveCursorUpByPage(true)
                Command.SELECT_DOWN_PAGE -> moveCursorDownByPage(true)
                Command.SELECT_HOME -> moveCursorToHome(true)
                Command.SELECT_END -> moveCursorToEnd(true)
                Command.SELECT_ALL -> selectAll()
                Command.SELECT_NONE -> selectNone()
                Command.DELETE_PREV_CHAR -> deleteSelectionOr { moveCursorPrevByChar(true); deleteSelection() }
                Command.DELETE_NEXT_CHAR -> deleteSelectionOr { moveCursorNextByChar(true); deleteSelection() }
                Command.DELETE_PREV_WORD -> deleteSelectionOr { moveCursorPrevByWord(true); deleteSelection() }
                Command.DELETE_NEXT_WORD -> deleteSelectionOr { moveCursorNexBytWord(true); deleteSelection() }
                Command.DELETE_START_LINE -> deleteSelectionOr { moveCursorToStartOfLine(true); deleteSelection() }
                Command.DELETE_END_LINE -> deleteSelectionOr { moveCursorToEndOfLine(true); deleteSelection() }
                Command.INSERT_NEW_LINE -> insertNewLine()
                Command.INSERT_TAB -> insertTab()
                Command.COPY -> copy()
                Command.PASTE -> paste()
                Command.CUT -> cut()
                Command.UNDO -> undo()
                Command.REDO -> redo()
                Command.CHARACTER_PALETTE -> {
                    // TODO: https://github.com/JetBrains/compose-jb/issues/1754
                    // androidx.compose.foundation.text.showCharacterPalette()
                }
            }
        }

        private fun moveCursorPrevByChar(isSelecting: Boolean = false) {
            if (!isSelecting && selection != null) updateCursor(selection!!.min, false)
            else {
                var newRow = cursor.row
                var newCol = cursor.col - 1
                if (newCol < 0) {
                    newRow -= 1
                    if (newRow < 0) {
                        newRow = 0
                        newCol = 0
                    } else newCol = content[newRow].length
                }
                updateCursor(Cursor(newRow, newCol), isSelecting)
            }
        }

        private fun moveCursorNextByChar(isSelecting: Boolean = false) {
            if (!isSelecting && selection != null) updateCursor(selection!!.max, false)
            else {
                var newRow = cursor.row
                var newCol = cursor.col + 1
                if (newCol > content[newRow].length) {
                    newRow += 1
                    if (newRow >= content.size) {
                        newRow = content.size - 1
                        newCol = content[newRow].length
                    } else newCol = 0
                }
                updateCursor(Cursor(newRow, newCol), isSelecting)
            }
        }

        private fun moveCursorPrevByWord(isSelecting: Boolean = false) {
            val newCursor: Cursor = textLayouts[cursor.row]?.let {
                Cursor(cursor.row, getPrevWordOffset(it, cursor.col))
            } ?: Cursor(0, 0)
            updateCursor(newCursor, isSelecting)
        }

        private fun moveCursorNexBytWord(isSelecting: Boolean = false) {
            val newCursor: Cursor = textLayouts[cursor.row]?.let {
                Cursor(cursor.row, getNextWordOffset(it, cursor.col))
            } ?: Cursor(0, 0)
            updateCursor(newCursor, isSelecting)
        }

        private fun getPrevWordOffset(textLayout: TextLayoutResult, col: Int): Int {
            if (col < 0 || content[cursor.row].isEmpty()) return 0
            val newCol = textLayout.getWordBoundary(withinText(col)).start
            return if (newCol < col) newCol
            else getPrevWordOffset(textLayout, col - 1)
        }

        private fun getNextWordOffset(textLayout: TextLayoutResult, col: Int): Int {
            if (col >= content[cursor.row].length) return content[cursor.row].length
            val newCol = textLayout.getWordBoundary(withinText(col)).end
            return if (newCol > col) newCol
            else getNextWordOffset(textLayout, col + 1)
        }

        private fun withinText(col: Int): Int {
            return col.coerceIn(0, (content[cursor.row].length - 1).coerceAtLeast(0))
        }

        private fun moveCursorPrevByParagraph(isSelecting: Boolean = false) {
            if (cursor.col > 0) moveCursorToStartOfLine(isSelecting) // because we don't wrap text
            else updateCursor(Cursor((cursor.row - 1).coerceAtLeast(0), cursor.col), isSelecting)
        }

        private fun moveCursorNextByParagraph(isSelecting: Boolean = false) {
            if (cursor.col < content[cursor.row].length) moveCursorToEndOfLine(isSelecting) // because we don't wrap text
            else {
                val newRow = (cursor.row + 1).coerceAtMost(content.size - 1)
                val newCol = cursor.col.coerceAtMost(content[newRow].length)
                updateCursor(Cursor(newRow, newCol), isSelecting)
            }
        }

        private fun moveCursorToStartOfLine(isSelecting: Boolean = false) {
            updateCursor(Cursor(cursor.row, 0), isSelecting)
        }

        private fun moveCursorToEndOfLine(isSelecting: Boolean = false) {
            updateCursor(Cursor(cursor.row, content[cursor.row].length), isSelecting)
        }

        private fun moveCursorUpByLine(isSelecting: Boolean = false) {
            var newRow = cursor.row - 1
            var newCol = cursor.col
            if (newRow < 0) {
                newRow = 0
                newCol = 0
            } else newCol = newCol.coerceAtMost(content[newRow].length)
            updateCursor(Cursor(newRow, newCol), isSelecting)
        }

        private fun moveCursorDownByLine(isSelecting: Boolean = false) {
            var newRow = cursor.row + 1
            var newCol = cursor.col
            if (newRow >= content.size) {
                newRow = content.size - 1
                newCol = content[newRow].length
            } else newCol = newCol.coerceAtMost(content[newRow].length)
            updateCursor(Cursor(newRow, newCol), isSelecting)
        }

        private fun moveCursorUpByPage(isSelecting: Boolean = false) {
            val fullyVisibleLines = floor(textAreaRect.height / lineHeight.value).toInt()
            val newRow = (cursor.row - fullyVisibleLines).coerceAtLeast(0)
            val newCol = cursor.col.coerceAtMost(content[newRow].length)
            updateCursor(Cursor(newRow, newCol), isSelecting)
        }

        private fun moveCursorDownByPage(isSelecting: Boolean = false) {
            val fullyVisibleLines = floor(textAreaRect.height / lineHeight.value).toInt()
            val newRow = (cursor.row + fullyVisibleLines).coerceAtMost(content.size - 1)
            val newCol = cursor.col.coerceAtMost(content[newRow].length)
            updateCursor(Cursor(newRow, newCol), isSelecting)
        }

        private fun moveCursorToHome(isSelecting: Boolean = false) {
            updateCursor(Cursor(0, 0), isSelecting)
        }

        private fun moveCursorToEnd(isSelecting: Boolean = false) {
            updateCursor(Cursor(content.size - 1, content.last().length), isSelecting)
        }

        private fun selectAll() {
            cursor = Cursor(content.size - 1, content.last().length)
            selection = Selection(Cursor(0, 0), cursor)
        }

        private fun selectNone() {
            selection = null
        }

        private fun deleteSelectionOr(elseFn: () -> Unit) {
            if (selection != null) deleteSelection()
            else elseFn()
        }

        private fun deleteSelection() {
            // TODO
        }

        private fun insertText(string: String) {
            // TODO
        }

        private fun insertNewLine() {
            // TODO
        }

        private fun insertTab() {
            // TODO
        }

        private fun copy() {
            if (selection == null) return
            val builder = StringBuilder()
            for (i in selection!!.min.row..selection!!.max.row) {
                val line = content[i]
                if (i == selection!!.min.row) {
                    if (selection!!.max.row > selection!!.min.row) builder.append(line.substring(selection!!.min.col))
                    else builder.append(line.substring(selection!!.min.col, selection!!.max.col))
                } else if (i == selection!!.max.row) builder.append("\n").append(line.substring(0, selection!!.max.col))
                else builder.append("\n").append(line)
            }
            clipboard.setText(AnnotatedString(builder.toString()))
        }

        private fun paste() {
            // TODO
        }

        private fun cut() {
            if (selection == null) return
            copy()
            deleteSelection()
        }

        private fun undo() {
            // TODO
        }

        private fun redo() {
            // TODO
        }
    }

    @Composable
    fun createState(file: File, coroutineScope: CoroutineScope): State {
        val font = Theme.typography.code1
        val currentDensity = LocalDensity.current
        val lineHeight = with(currentDensity) { font.fontSize.toDp() * LINE_HEIGHT }
        return State(file, font, lineHeight, LocalClipboardManager.current, coroutineScope, currentDensity.density)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun Area(state: State, modifier: Modifier = Modifier) {
        if (state.content.isEmpty()) return
        val density = LocalDensity.current.density
        val fontHeight = with(LocalDensity.current) { (state.lineHeight - LINE_GAP).toSp() * density }
        val fontColor = Theme.colors.onBackground
        val textFont = state.fontBase.copy(color = fontColor, lineHeight = fontHeight)
        val lineNumberFont = state.fontBase.copy(color = fontColor.copy(0.5f), lineHeight = fontHeight)
        var fontWidth by remember { mutableStateOf(DEFAULT_FONT_WIDTH) }
        val focusReq = FocusRequester()

        Box { // We render a number to find out the default width of a digit for the given font
            Text(text = "0", style = lineNumberFont, onTextLayout = { fontWidth = toDP(it.size.width, density) })
            Row(modifier = modifier.focusRequester(focusReq).focusable()
                .onGloballyPositioned { state.density = density }
                .onKeyEvent { state.processKeyEvent(it) }
                .onPointerEvent(Press) { if (it.awtEvent.button == BUTTON1) state.isSelecting = true }
                .onPointerEvent(Move) { state.updateSelection(it.awtEvent.x, it.awtEvent.y) }
                .onPointerEvent(Release) { if (it.awtEvent.button == BUTTON1) state.isSelecting = false }
                .pointerInput(state) { onPointerInput(state) }
            ) {
                LineNumberArea(state, lineNumberFont, fontWidth)
                Separator.Vertical()
                TextArea(state, textFont, fontWidth)
            }
        }

        LaunchedEffect(state) { focusReq.requestFocus() }
    }

    @Composable
    private fun LineNumberArea(state: State, font: TextStyle, fontWidth: Dp) {
        val minWidth = fontWidth * ceil(log10(state.lineCount.toDouble())).toInt() + AREA_PADDING_HORIZONTAL * 2 + 2.dp
        val lazyColumnState: LazyColumn.State<Int> = LazyColumn.createState(
            items = (0 until state.lineCount).map { it },
            scroller = state.verScroller
        )
        LazyColumn.Area(state = lazyColumnState) { index, _ -> LineNumber(state, index, font, minWidth) }
    }

    @Composable
    private fun LineNumber(state: State, index: Int, font: TextStyle, minWidth: Dp) {
        val isCursor = state.cursor.row == index
        val isSelected = state.selection?.let { it.min.row <= index && it.max.row >= index } ?: false
        val bgColor = if (isCursor || isSelected) Theme.colors.primary else Theme.colors.background
        Box(
            contentAlignment = Alignment.TopEnd,
            modifier = Modifier.background(bgColor)
                .defaultMinSize(minWidth = minWidth)
                .height(state.lineHeight)
                .padding(horizontal = AREA_PADDING_HORIZONTAL)
        ) { Text(text = (index + 1).toString(), style = font) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun TextArea(state: State, font: TextStyle, fontWidth: Dp) {
        val lazyColumnState: LazyColumn.State<String> = LazyColumn.createState(
            items = state.content,
            scroller = state.verScroller
        )

        Box(modifier = Modifier.fillMaxSize()
            .background(Theme.colors.background2)
            .horizontalScroll(state.horScroller)
            .onGloballyPositioned { state.updateTextAreaCoord(it.boundsInWindow()) }
            .onSizeChanged { state.increaseWidth(it.width) }) {
            ContextMenu.Popup(state.contextMenu) { contextMenuFn(state) }
            LazyColumn.Area(state = lazyColumnState) { index, text -> TextLine(state, index, text, font, fontWidth) }
        }
    }

    @Composable
    private fun TextLine(state: State, index: Int, text: String, font: TextStyle, fontWidth: Dp) {
        val bgColor = when {
            state.cursor.row == index && state.selection == null -> Theme.colors.primary
            else -> Theme.colors.background2
        }

        Box(
            contentAlignment = Alignment.TopStart,
            modifier = Modifier.background(bgColor)
                .defaultMinSize(minWidth = state.width)
                .height(state.lineHeight)
                .padding(horizontal = AREA_PADDING_HORIZONTAL)
        ) {
            if (state.selection != null && state.selection!!.min.row <= index && state.selection!!.max.row >= index) {
                SelectionHighlighter(state, index, text.length)
            }
            Text(
                text = AnnotatedString(text), style = font,
                modifier = Modifier.onSizeChanged { state.increaseWidth(it.width) },
                onTextLayout = { state.textLayouts[index] = it }
            )
            if (state.cursor.row == index && state.textLayouts[index] != null) {
                CursorIndicator(state, text, font, fontWidth)
            }
        }
    }

    @Composable
    private fun SelectionHighlighter(state: State, index: Int, length: Int) {
        assert(state.selection != null && state.selection!!.min.row <= index && state.selection!!.max.row >= index)
        val start = when {
            state.selection!!.min.row < index -> 0
            else -> state.selection!!.min.col // state.selection!!.min.row == index
        }
        val end = when {
            state.selection!!.max.row > index -> state.content[index].length
            else -> state.selection!!.max.col
        }
        var startPos = state.textLayouts[index]?.let { toDP(it.getCursorRect(start).left, state.density) } ?: 0.dp
        var endPos = state.textLayouts[index]?.let { toDP(it.getCursorRect(end).right, state.density) } ?: 0.dp
        if (state.selection!!.min.row < index) startPos -= AREA_PADDING_HORIZONTAL
        if (state.selection!!.max.row > index && length > 0) endPos += AREA_PADDING_HORIZONTAL
        val color = Theme.colors.tertiary.copy(Theme.SELECTION_ALPHA)
        Box(Modifier.offset(x = startPos).width(endPos - startPos).height(state.lineHeight).background(color))
    }

    @OptIn(ExperimentalTime::class)
    @Composable
    private fun CursorIndicator(state: State, text: String, font: TextStyle, fontWidth: Dp) {
        var visible by remember { mutableStateOf(true) }
        val textLayout = state.textLayouts[state.cursor.row]
        val offsetX = textLayout?.let { toDP(it.getCursorRect(state.cursor.col).left, state.density) } ?: 0.dp
        val width = when {
            state.cursor.col >= text.length -> fontWidth
            else -> textLayout?.let { toDP(it.getBoundingBox(state.cursor.col).width, state.density) } ?: fontWidth
        }
        if (visible) {
            Box(
                modifier = Modifier.offset(x = offsetX, y = CURSOR_LINE_PADDING)
                    .width(width).height(state.lineHeight - CURSOR_LINE_PADDING * 2)
                    .background(Theme.colors.secondary)
            ) {
                Text(
                    text.getOrNull(state.cursor.col)?.toString() ?: "",
                    Modifier.offset(y = -CURSOR_LINE_PADDING),
                    style = font.copy(Theme.colors.background2)
                )
            }
        }
        LaunchedEffect(state.cursor) {
            visible = true
            while (true) {
                delay(Duration.milliseconds(500))
                visible = !visible
            }
        }
    }

    private suspend fun PointerInputScope.onPointerInput(state: State) {
        state.contextMenu.onPointerInput(
            pointerInputScope = this,
            onSinglePrimaryClick = { state.updateCursor(it.x, it.y, it.isShiftDown) },
            onDoublePrimaryClick = { }, // TODO
            onTriplePrimaryClick = { }, // TODO
            onSecondaryClick = { state.updateCursorIfOutOfSelection(it.x, it.y) }
        )
    }

    private fun contextMenuFn(state: State): List<ContextMenu.Item> { // TODO
        return listOf(
            ContextMenu.Item(Label.PASTE, Icon.Code.PASTE) {}
        )
    }
}