package dev.po4yka.chur.app.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Source edits use UTF-16 offsets, like Compose selection and the Markdown AST. */
internal enum class MarkdownCommand(val label: String) {
    BOLD("Bold"), ITALIC("Italic"), STRIKE("Strike"), CODE("Code"), FENCE("Code block"),
    HEADING("Heading"), BULLET("Bullet list"), NUMBER("Numbered list"), TASK("Checklist"),
    QUOTE("Quote"), LINK("Link"), IMAGE("Image"), TABLE("Table"), RULE("Divider"),
    INDENT("Indent"), OUTDENT("Outdent"),
}

internal fun markdownEdit(value: TextFieldValue, command: MarkdownCommand): TextFieldValue {
    val start = value.selection.min
    val end = value.selection.max
    val selected = value.text.substring(start, end)
    fun replace(from: Int, to: Int, text: String, selection: TextRange): TextFieldValue =
        TextFieldValue(value.text.replaceRange(from, to, text), selection)
    fun wrap(marker: String): TextFieldValue {
        val result = marker + selected + marker
        return replace(start, end, result, TextRange(start + marker.length, end + marker.length))
    }
    fun block(text: String): TextFieldValue {
        val prefix = value.text.substring(0, start)
        val suffix = value.text.substring(end)
        val before = if (prefix.isEmpty() || prefix.endsWith("\n\n")) "" else if (prefix.endsWith('\n')) "\n" else "\n\n"
        val after = if (suffix.startsWith("\n\n")) "" else if (suffix.startsWith('\n') || suffix.isEmpty()) "\n" else "\n\n"
        return replace(start, end, before + text + after, TextRange(start + before.length + text.length))
    }
    return when (command) {
        MarkdownCommand.BOLD -> wrap("**")
        MarkdownCommand.ITALIC -> wrap("*")
        MarkdownCommand.STRIKE -> wrap("~~")
        MarkdownCommand.CODE -> {
            val fence = "`".repeat((Regex("`+").findAll(selected).maxOfOrNull { it.value.length } ?: 0) + 1)
            val pad = if (selected.startsWith('`') || selected.endsWith('`') ||
                (selected.startsWith(' ') && selected.endsWith(' ') && selected.any { it != ' ' })) " " else ""
            val text = fence + pad + selected + pad + fence
            replace(start, end, text, TextRange(start + fence.length + pad.length, end + fence.length + pad.length))
        }
        MarkdownCommand.FENCE -> {
            val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(selected).maxOfOrNull { it.value.length } ?: 0) + 1))
            block("$fence\n$selected\n$fence")
        }
        MarkdownCommand.LINK, MarkdownCommand.IMAGE -> {
            val prefix = if (command == MarkdownCommand.IMAGE) "!" else ""
            val label = selected.ifEmpty { if (prefix.isEmpty()) "link" else "image" }
                .replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
            val text = "$prefix[$label](https://)"
            val urlStart = start + prefix.length + label.length + 3
            replace(start, end, text, TextRange(urlStart, urlStart + 8))
        }
        MarkdownCommand.TABLE -> block("| Column 1 | Column 2 |\n| --- | --- |\n| ${selected.replace("|", "\\|").replace('\n', ' ')} |  |")
        MarkdownCommand.RULE -> block("---")
        else -> {
            val lineStart = value.text.lastIndexOf('\n', (start - 1).coerceAtLeast(-1)) + 1
            val last = if (end > start && value.text.getOrNull(end - 1) == '\n') end - 1 else end
            val lineEnd = value.text.indexOf('\n', last).let { if (it < 0) value.text.length else it }
            val lines = value.text.substring(lineStart, lineEnd).split('\n')
            val edited = lines.mapIndexed { index, line ->
                when (command) {
                    MarkdownCommand.HEADING -> "## $line"
                    MarkdownCommand.BULLET -> "- $line"
                    MarkdownCommand.NUMBER -> "${index + 1}. $line"
                    MarkdownCommand.TASK -> "- [ ] $line"
                    MarkdownCommand.QUOTE -> "> $line"
                    MarkdownCommand.INDENT -> "    $line"
                    MarkdownCommand.OUTDENT -> if (line.startsWith('\t')) line.drop(1) else line.drop(minOf(4, line.takeWhile { it == ' ' }.length))
                    else -> line
                }
            }.joinToString("\n")
            replace(lineStart, lineEnd, edited, TextRange(lineStart, lineStart + edited.length))
        }
    }
}

/** Continue a list only for an ordinary Enter, outside an IME composition. */
internal fun continueMarkdownList(old: TextFieldValue, next: TextFieldValue): TextFieldValue {
    val cursor = old.selection.start
    if (!old.selection.collapsed || old.composition != null || next.composition != null ||
        next.text.length != old.text.length + 1 || next.selection.start != cursor + 1 ||
        next.text != old.text.replaceRange(cursor, cursor, "\n")) return next
    val start = old.text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(-1)) + 1
    // Use the same GFM parser as preview, including nested/long/tilde fences.
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(old.text)
    val pending = ArrayDeque<ASTNode>().apply { add(tree) }
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        if (cursor < node.startOffset || cursor > node.endOffset) continue
        if (node.type == MarkdownElementTypes.CODE_FENCE || node.type == MarkdownElementTypes.CODE_BLOCK) return next
        pending.addAll(node.children)
    }
    val line = old.text.substring(start, cursor)
    val match = Regex("^(\\s*)(?:([-+*]) (\\[[ xX]\\] )?|(\\d+)([.)]) )(.+)?$").matchEntire(line) ?: return next
    val indent = match.groupValues[1]
    if (match.groupValues[6].isBlank()) {
        val text = next.text.removeRange(start, cursor)
        return TextFieldValue(text, TextRange(start + 1))
    }
    val marker = if (match.groupValues[4].isNotEmpty()) {
        val number = match.groupValues[4].toLongOrNull() ?: return next
        if (number >= 999_999_999) return next
        "${number + 1}${match.groupValues[5]} "
    } else match.groupValues[2] + " " + if (match.groupValues[3].isNotEmpty()) "[ ] " else ""
    val prefix = indent + marker
    return TextFieldValue(next.text.replaceRange(cursor + 1, cursor + 1, prefix), TextRange(cursor + 1 + prefix.length))
}

/** Bounded, route-owned undo state. No serializer, singleton, or saved-state owner. */
internal class MarkdownHistory(initial: TextFieldValue) {
    private val undo = ArrayDeque<TextFieldValue>()
    private val redo = ArrayDeque<TextFieldValue>()
    private var current = initial
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    fun record(next: TextFieldValue) {
        if (next.text != current.text) {
            undo.addLast(current.copy(composition = null))
            redo.clear()
            while (undo.size > 30 || undo.sumOf { it.text.length } > 524_288) undo.removeFirst()
        }
        current = next
    }
    fun undo(): TextFieldValue? {
        if (undo.isEmpty()) return null
        redo.addLast(current.copy(composition = null))
        return undo.removeLast().also { current = it }
    }
    fun redo(): TextFieldValue? {
        if (redo.isEmpty()) return null
        undo.addLast(current.copy(composition = null))
        return redo.removeLast().also { current = it }
    }
    fun clear() { undo.clear(); redo.clear(); current = TextFieldValue() }
}
