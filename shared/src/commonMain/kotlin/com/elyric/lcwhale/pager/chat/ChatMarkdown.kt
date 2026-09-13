package com.elyric.lcwhale.pager.chat

import com.elyric.lcwhale.foundation.theme.ThemePalette
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.RichText
import com.tencent.kuikly.core.views.RichTextView
import com.tencent.kuikly.core.views.Span
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button

/**
 * Renders assistant output using the same block-oriented model as the Kuikly
 * Markdown demo: paragraphs, headings, quotes, lists, tables and fenced code
 * are separate layout blocks, while inline emphasis is rendered as spans.
 */
internal fun ViewContainer<*, *>.AiMarkdownContent(
    markdown: String,
    palette: ThemePalette,
    onCodeCopy: (String) -> Unit = {},
) {
    parseMarkdownBlocks(markdown).forEach { block ->
        when (block) {
            is MarkdownBlock.Paragraph -> MarkdownParagraph(block, palette)
            is MarkdownBlock.Heading -> MarkdownHeading(block, palette)
            is MarkdownBlock.Quote -> MarkdownQuote(block, palette)
            is MarkdownBlock.ListItems -> MarkdownList(block, palette)
            is MarkdownBlock.Code -> MarkdownCode(block, palette, onCodeCopy)
            is MarkdownBlock.Table -> MarkdownTable(block, palette)
            MarkdownBlock.Divider -> {
                View {
                    attr {
                        height(1f)
                        marginTop(8f)
                        marginBottom(8f)
                        backgroundColor(palette.border)
                    }
                }
            }
        }
    }
}

private sealed interface MarkdownBlock {
    data class Paragraph(val lines: List<String>) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Quote(val lines: List<String>) : MarkdownBlock
    data class ListItems(val items: List<ListItem>) : MarkdownBlock
    data class Code(val language: String, val text: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

private data class ListItem(val marker: String, val text: String)

private fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            index += 1
            continue
        }

        if (trimmed.startsWith("```")) {
            val language = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            index += 1
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                codeLines.add(lines[index])
                index += 1
            }
            if (index < lines.size) index += 1
            blocks.add(MarkdownBlock.Code(language, codeLines.joinToString("\n")))
            continue
        }

        val heading = HEADING_REGEX.matchEntire(line)
        if (heading != null) {
            blocks.add(MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim()))
            index += 1
            continue
        }

        if (isDivider(trimmed)) {
            blocks.add(MarkdownBlock.Divider)
            index += 1
            continue
        }

        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (index < lines.size && lines[index].trim().startsWith(">")) {
                quoteLines.add(lines[index].trim().removePrefix(">").trimStart())
                index += 1
            }
            blocks.add(MarkdownBlock.Quote(quoteLines))
            continue
        }

        val listItem = parseListItem(line)
        if (listItem != null) {
            val items = mutableListOf<ListItem>()
            while (index < lines.size) {
                val item = parseListItem(lines[index]) ?: break
                items.add(item)
                index += 1
            }
            blocks.add(MarkdownBlock.ListItems(items))
            continue
        }

        if (index + 1 < lines.size && isTableSeparator(lines[index + 1]) && line.contains('|')) {
            val rows = mutableListOf<List<String>>()
            rows.add(splitTableRow(line))
            index += 2
            while (index < lines.size && lines[index].contains('|') && lines[index].trim().isNotEmpty()) {
                rows.add(splitTableRow(lines[index]))
                index += 1
            }
            blocks.add(MarkdownBlock.Table(rows))
            continue
        }

        val paragraphLines = mutableListOf<String>()
        while (index < lines.size) {
            val paragraphLine = lines[index]
            if (paragraphLine.trim().isEmpty() || (paragraphLines.isNotEmpty() && isBlockStart(index, lines))) {
                break
            }
            paragraphLines.add(paragraphLine)
            index += 1
        }
        if (paragraphLines.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paragraphLines))
        } else {
            index += 1
        }
    }
    return blocks
}

private fun isBlockStart(index: Int, lines: List<String>): Boolean {
    val line = lines[index]
    val trimmed = line.trim()
    if (trimmed.startsWith("```") || trimmed.startsWith(">")) return true
    if (HEADING_REGEX.matches(line) || isDivider(trimmed) || parseListItem(line) != null) return true
    return index + 1 < lines.size && line.contains('|') && isTableSeparator(lines[index + 1])
}

private fun parseListItem(line: String): ListItem? {
    val match = LIST_REGEX.matchEntire(line) ?: return null
    return ListItem(match.groupValues[1], match.groupValues[2].trim())
}

private fun isDivider(line: String): Boolean {
    return line.matches(Regex("^((\\*\\s*){3,}|(-\\s*){3,}|(_\\s*){3,})$"))
}

private fun isTableSeparator(line: String): Boolean {
    val cells = splitTableRow(line)
    return cells.size >= 2 && cells.all { it.matches(Regex("^:?-{3,}:?$")) }
}

private fun splitTableRow(line: String): List<String> {
    var value = line.trim()
    if (value.startsWith("|")) value = value.drop(1)
    if (value.endsWith("|")) value = value.dropLast(1)
    return value.split('|').map { it.trim() }
}

private fun ViewContainer<*, *>.MarkdownParagraph(block: MarkdownBlock.Paragraph, palette: ThemePalette) {
    block.lines.forEachIndexed { index, line ->
        MarkdownInline(line, palette, 14f, if (index == 0) 0f else 2f)
    }
}

private fun ViewContainer<*, *>.MarkdownHeading(block: MarkdownBlock.Heading, palette: ThemePalette) {
    val size = when (block.level) {
        1 -> 20f
        2 -> 19f
        3 -> 18f
        else -> 16f
    }
    MarkdownInline(block.text, palette, size, 8f, bold = true)
}

private fun ViewContainer<*, *>.MarkdownQuote(block: MarkdownBlock.Quote, palette: ThemePalette) {
    View {
        attr {
            flexDirectionRow()
            marginTop(5f)
            marginBottom(5f)
        }
        View {
            attr {
                width(3f)
                backgroundColor(palette.accent)
                marginRight(8f)
            }
        }
        View {
            attr { flex(1f) }
            block.lines.forEachIndexed { index, line ->
                MarkdownInline(line, palette, 14f, if (index == 0) 0f else 2f, italic = true)
            }
        }
    }
}

private fun ViewContainer<*, *>.MarkdownList(block: MarkdownBlock.ListItems, palette: ThemePalette) {
    View {
        attr {
            flexDirectionColumn()
            marginTop(3f)
            marginBottom(3f)
        }
        block.items.forEach { item ->
            val task = TASK_REGEX.matchEntire(item.text)
            val bullet = if (task == null) item.marker else if (task.groupValues[1].equals("x", true)) "[x]" else "[ ]"
            val content = task?.groupValues?.get(2) ?: item.text
            View {
                attr {
                    flexDirectionRow()
                    marginTop(2f)
                }
                Text {
                    attr {
                        text(if (item.marker.firstOrNull()?.isDigit() == true) "${item.marker} " else "$bullet ")
                        width(24f)
                        fontSize(14f)
                        color(palette.accent)
                    }
                }
                View {
                    attr { flex(1f) }
                    MarkdownInline(content, palette, 14f, 0f)
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.MarkdownCode(
    block: MarkdownBlock.Code,
    palette: ThemePalette,
    onCodeCopy: (String) -> Unit,
) {
    View {
        attr {
            marginTop(6f)
            marginBottom(6f)
            padding(all = 10f)
            borderRadius(6f)
            backgroundColor(palette.surfaceMuted)
        }
        View {
            attr {
                flexDirectionRow()
                marginBottom(5f)
                selectable(com.tencent.kuikly.core.views.SelectableOption.DISABLE)
            }
            if (block.language.isNotEmpty()) {
                Text {
                    attr {
                        text(block.language)
                        fontSize(11f)
                        color(palette.accent)
                    }
                }
            }
            View { attr { flex(1f) } }
            Button {
                attr {
                    height(24f)
                    padding(left = 7f, right = 7f)
                    borderRadius(5f)
                    backgroundColor(palette.surface)
                    titleAttr {
                        text("复制代码")
                        fontSize(11f)
                        color(palette.textMuted)
                    }
                }
                event { click { onCodeCopy(block.text) } }
            }
        }
        Text {
            attr {
                text(block.text)
                fontSize(13f)
                lineHeight(20f)
                fontFamily("monospace")
                color(palette.text)
            }
        }
    }
}

private fun ViewContainer<*, *>.MarkdownTable(block: MarkdownBlock.Table, palette: ThemePalette) {
    if (block.rows.isEmpty()) return
    View {
        attr {
            flexDirectionColumn()
            marginTop(6f)
            marginBottom(6f)
            borderRadius(5f)
            backgroundColor(palette.surfaceMuted)
        }
        block.rows.forEachIndexed { rowIndex, row ->
            View {
                attr {
                    flexDirectionRow()
                    backgroundColor(if (rowIndex == 0) palette.surface else palette.surfaceMuted)
                }
                row.forEach { cell ->
                    View {
                        attr {
                            flex(1f)
                            padding(all = 6f)
                        }
                        MarkdownInline(cell, palette, 12f, 0f, bold = rowIndex == 0)
                    }
                }
            }
        }
    }
}

private enum class InlineStyle { Plain, Bold, Italic, Strike, Code, Link }

private data class InlineSegment(val text: String, val style: InlineStyle)

private fun ViewContainer<*, *>.MarkdownInline(
    text: String,
    palette: ThemePalette,
    fontSize: Float,
    marginTop: Float,
    bold: Boolean = false,
    italic: Boolean = false,
) {
    RichText {
        attr {
            this.fontSize(fontSize)
            color(palette.text)
            lineHeight(fontSize + 8f)
            if (marginTop > 0f) marginTop(marginTop)
        }
        parseInlineMarkdown(text).forEach { segment ->
            Span {
                text(segment.text)
                fontSize(fontSize)
                when {
                    bold || segment.style == InlineStyle.Bold -> fontWeightBold()
                    else -> fontWeightNormal()
                }
                if (italic || segment.style == InlineStyle.Italic) fontStyleItalic()
                if (segment.style == InlineStyle.Strike) textDecorationLineThrough()
                if (segment.style == InlineStyle.Code) {
                    fontFamily("monospace")
                    color(palette.accent)
                } else if (segment.style == InlineStyle.Link) {
                    color(palette.accent)
                    textDecorationUnderLine()
                } else {
                    color(palette.text)
                }
            }
        }
    }
}

private fun parseInlineMarkdown(value: String): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    var cursor = 0
    var plainStart = 0

    fun addPlain(until: Int) {
        if (until > plainStart) {
            segments.add(InlineSegment(value.substring(plainStart, until), InlineStyle.Plain))
        }
    }

    while (cursor < value.length) {
        val linkStart = value[cursor] == '['
        if (linkStart) {
            val labelEnd = value.indexOf("](", cursor + 1)
            val urlEnd = if (labelEnd >= 0) value.indexOf(')', labelEnd + 2) else -1
            if (labelEnd > cursor && urlEnd > labelEnd + 2) {
                addPlain(cursor)
                segments.add(InlineSegment(value.substring(cursor + 1, labelEnd), InlineStyle.Link))
                cursor = urlEnd + 1
                plainStart = cursor
                continue
            }
        }

        val token = when {
            value.startsWith("**", cursor) -> "**" to InlineStyle.Bold
            value.startsWith("__", cursor) -> "__" to InlineStyle.Bold
            value.startsWith("~~", cursor) -> "~~" to InlineStyle.Strike
            value[cursor] == '`' -> "`" to InlineStyle.Code
            value[cursor] == '*' -> "*" to InlineStyle.Italic
            value[cursor] == '_' && (cursor == 0 || !value[cursor - 1].isLetterOrDigit()) -> "_" to InlineStyle.Italic
            else -> null
        }
        if (token != null) {
            val marker = token.first
            val close = value.indexOf(marker, cursor + marker.length)
            if (close > cursor + marker.length) {
                addPlain(cursor)
                segments.add(InlineSegment(value.substring(cursor + marker.length, close), token.second))
                cursor = close + marker.length
                plainStart = cursor
                continue
            }
        }
        cursor += 1
    }
    addPlain(value.length)
    return segments.ifEmpty { listOf(InlineSegment(value, InlineStyle.Plain)) }
}

private val HEADING_REGEX = Regex("^\\s*(#{1,6})\\s+(.+?)\\s*$")
private val LIST_REGEX = Regex("^\\s*((?:[-+*])|(?:\\d+[.)]))\\s+(.+?)\\s*$")
private val TASK_REGEX = Regex("^\\[([ xX])\\]\\s+(.+)$")
