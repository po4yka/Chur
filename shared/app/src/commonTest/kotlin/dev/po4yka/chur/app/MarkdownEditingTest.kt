package dev.po4yka.chur.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.po4yka.chur.app.notes.MarkdownCommand
import dev.po4yka.chur.app.notes.MarkdownHistory
import dev.po4yka.chur.app.notes.continueMarkdownList
import dev.po4yka.chur.app.notes.markdownEdit
import dev.po4yka.chur.app.notes.toggleMarkdownTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.IElementType
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

class MarkdownEditingTest {
    @Test fun formatting_changes_only_the_selected_source() {
        val original = "before **existing** Привет 🌍 after"
        val start = original.indexOf("Привет")
        val selected = TextFieldValue(original, TextRange(start, start + 6))
        val edited = markdownEdit(selected, MarkdownCommand.BOLD)
        assertEquals("before **existing** **Привет** 🌍 after", edited.text)
        assertEquals("Привет", edited.text.substring(edited.selection.min, edited.selection.max))
        assertEquals(original, selected.text)
        val code = markdownEdit(TextFieldValue("`code`", TextRange(0, 6)), MarkdownCommand.CODE)
        assertEquals("`` `code` ``", code.text)
        assertEquals("`  a  `", markdownEdit(TextFieldValue(" a ", TextRange(0, 3)), MarkdownCommand.CODE).text)
        assertEquals("`   `", markdownEdit(TextFieldValue("   ", TextRange(0, 3)), MarkdownCommand.CODE).text)
    }

    @Test fun multiline_commands_keep_adjacent_lines_and_handle_reverse_selection() {
        val source = "keep\nfirst\nsecond\ntail"
        val result = markdownEdit(TextFieldValue(source, TextRange(17, 5)), MarkdownCommand.TASK)
        assertEquals("keep\n- [ ] first\n- [ ] second\ntail", result.text)
        assertEquals(source, markdownEdit(markdownEdit(TextFieldValue(source), MarkdownCommand.INDENT), MarkdownCommand.OUTDENT).text)
    }

    @Test fun list_continuation_handles_tasks_empty_items_numbers_and_ime() {
        fun enter(source: String): TextFieldValue {
            val old = TextFieldValue(source, TextRange(source.length))
            return continueMarkdownList(old, TextFieldValue(source + "\n", TextRange(source.length + 1)))
        }
        assertEquals("  - [x] done\n  - [ ] ", enter("  - [x] done").text)
        assertEquals("9. item\n10. ", enter("9. item").text)
        assertEquals("\n", enter("- ").text)
        assertEquals("```\n- code\n", enter("```\n- code").text)
        assertEquals("````\n```\n- literal\n", enter("````\n```\n- literal").text)
        assertEquals("~~~\n```\n- literal\n", enter("~~~\n```\n- literal").text)
        val old = TextFieldValue("- text", TextRange(6), composition = TextRange(2, 6))
        val next = TextFieldValue("- text\n", TextRange(7))
        assertEquals(next, continueMarkdownList(old, next))
    }

    @Test fun divider_inserts_a_block_boundary_instead_of_a_setext_heading() {
        val result = markdownEdit(TextFieldValue("paragraph\n", TextRange(10)), MarkdownCommand.RULE)
        assertEquals("paragraph\n\n---\n", result.text)
    }

    @Test fun task_click_is_a_source_range_edit_and_ignores_other_text() {
        val source = "- [ ] one\n- [X] two\n```\n- [ ] code\n```"
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        val tasks = mutableListOf<ASTNode>()
        fun visit(node: ASTNode) {
            if (node.type == GFMTokenTypes.CHECK_BOX) tasks += node
            node.children.forEach(::visit)
        }
        visit(tree)
        assertEquals(2, tasks.size)
        val toggled = toggleMarkdownTask(source, tasks[0].startOffset, tasks[0].endOffset)
        assertEquals("- [x] one\n- [X] two\n```\n- [ ] code\n```", toggled)
        assertEquals("- [ ] one\n- [ ] two\n```\n- [ ] code\n```", toggleMarkdownTask(source, tasks[1].startOffset, tasks[1].endOffset))
        assertNull(toggleMarkdownTask(source, -1, 1))
        assertNull(toggleMarkdownTask(source, 0, 1))
    }

    @Test fun parser_recognizes_gfm_without_reserializing_unknown_source() {
        val source = "# Заголовок\n\n> quote **bold** and *italic* ~~strike~~\n\n1. first\n   - nested\n\n- [ ] task\n\n| A | B |\n| --- | --- |\n| a\\|b | c |\n\n```kotlin\nval x = 1\n```\n\n[ref][id]\n\n[id]: https://example.invalid\n\n[^custom] ==opaque== $" + "math$\n<script>literal</script>\n"
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        val types = mutableSetOf<IElementType>()
        fun visit(node: ASTNode) { types += node.type; node.children.forEach(::visit) }
        visit(tree)
        assertTrue(types.contains(MarkdownElementTypes.ATX_1))
        assertTrue(types.contains(GFMElementTypes.TABLE), types.toString())
        assertTrue(types.contains(GFMTokenTypes.CHECK_BOX), types.toString())
        assertEquals(source, tree.let { source.substring(it.startOffset, it.endOffset) })
    }

    @Test fun history_undo_redo_branches_and_disposal_drop_all_snapshots() {
        val history = MarkdownHistory(TextFieldValue("a"))
        history.record(TextFieldValue("ab", TextRange(2)))
        history.record(TextFieldValue("abc", TextRange(3)))
        assertEquals("ab", history.undo()?.text)
        assertEquals("abc", history.redo()?.text)
        history.undo()
        history.record(TextFieldValue("new"))
        assertFalse(history.canRedo)
        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }
}
