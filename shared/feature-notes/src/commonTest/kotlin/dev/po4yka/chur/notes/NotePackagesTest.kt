package dev.po4yka.chur.notes

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertFails

class NotePackagesTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private val bytes = byteArrayOf(0, 1, 2, 127, -128, -1)
    private val attachment = NotePackageAttachment(id, "Заметки 📎.bin", "application/octet-stream", bytes)
    private val note = NotePackage("Exact title", "# Heading  \n\n![image](attachment:$id)\n", NoteFormat.MARKDOWN, listOf(attachment))

    @Test
    fun package_preserves_source_format_names_and_binary_content() {
        val restored = NotePackages.decode(NotePackages.encode(note))
        assertEquals(note.title, restored.title)
        assertEquals(note.body, restored.body)
        assertEquals(note.format, restored.format)
        assertEquals(attachment.id, restored.attachments.single().id)
        assertEquals(attachment.name, restored.attachments.single().name)
        assertContentEquals(bytes, restored.attachments.single().data)
    }

    @Test
    fun empty_attachment_and_plain_text_round_trip() {
        val restored = NotePackages.decode(NotePackages.encode(note.copy(
            format = NoteFormat.PLAIN_TEXT,
            attachments = listOf(attachment.copy(data = byteArrayOf())),
        )))
        assertEquals(NoteFormat.PLAIN_TEXT, restored.format)
        assertContentEquals(byteArrayOf(), restored.attachments.single().data)
    }

    @Test
    fun duplicate_ids_and_unsafe_filenames_are_rejected() {
        assertFailsWith<IllegalArgumentException> { NotePackages.encode(note.copy(attachments = listOf(attachment, attachment))) }
        for (name in listOf("../evil", "a/b", "a\\b", "..", "C:evil", "bad\u0000name", "\nname")) {
            assertFailsWith<IllegalArgumentException> {
                NotePackages.encode(note.copy(attachments = listOf(attachment.copy(name = name))))
            }
        }
        for (id in listOf("../evil", "0".repeat(32))) {
            assertFailsWith<IllegalArgumentException> {
                NotePackages.encode(note.copy(attachments = listOf(attachment.copy(id = id))))
            }
        }
    }

    @Test
    fun filename_limit_counts_utf8_bytes() {
        NoteAttachments.validateName("я".repeat(120))
        assertFailsWith<IllegalArgumentException> { NoteAttachments.validateName("я".repeat(121)) }
    }

    @Test
    fun mime_comes_from_signature_and_executable_types_are_not_renderable() {
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        assertEquals("image/png", NoteAttachments.validate("false.txt", png))
        assertEquals("application/octet-stream", NoteAttachments.validate("image.svg", "<svg onload='alert(1)'/>".encodeToByteArray()))
        assertFailsWith<IllegalArgumentException> {
            NotePackages.encode(note.copy(attachments = listOf(attachment.copy(mimeType = "image/png"))))
        }
    }

    @Test
    fun untrusted_package_checks_version_encoding_mime_and_count() {
        val encoded = NotePackages.encode(note).decodeToString()
        for (malformed in listOf(
            encoded.replace("\"version\":1", "\"version\":999"),
            encoded.replace("\"version\":1,", ""),
            encoded.replace("application/octet-stream", "text/html"),
            encoded.replace("AAECf4D/", "AAECf4D_"),
        )) {
            assertFailsWith<IllegalArgumentException> { NotePackages.decode(malformed.encodeToByteArray()) }
        }
        assertFailsWith<IllegalArgumentException> {
            NotePackages.encode(note.copy(attachments = List(33) { attachment.copy(id = (it + 1).toString(16).padStart(32, '0')) }))
        }
        assertFails { NotePackages.decode(byteArrayOf(-1)) }
        assertFalse(NoteAttachments.validId(id.uppercase()))
    }

    @Test
    fun portable_title_limit_and_nul_rejection_match_private_notes() {
        NotePackages.encode(note.copy(title = "я".repeat(128)))
        assertFailsWith<IllegalArgumentException> { NotePackages.encode(note.copy(title = "я".repeat(129))) }
        assertFailsWith<IllegalArgumentException> { NotePackages.encode(note.copy(title = "bad\u0000title")) }
        assertFailsWith<IllegalArgumentException> { NotePackages.encode(note.copy(body = "bad\u0000body")) }
    }

    @Test
    fun aggregate_and_per_file_limits_are_enforced() {
        val maximum = ByteArray(NoteAttachments.MAX_FILE_BYTES)
        val allowed = note.copy(attachments = List(2) { attachment.copy(id = (it + 1).toString().padStart(32, '0'), data = maximum) })
        NotePackages.encode(allowed)
        assertFailsWith<IllegalArgumentException> {
            NotePackages.encode(note.copy(attachments = listOf(attachment.copy(data = ByteArray(NoteAttachments.MAX_FILE_BYTES + 1)))))
        }
        assertFailsWith<IllegalArgumentException> {
            NotePackages.encode(allowed.copy(attachments = allowed.attachments + attachment))
        }
    }
}
