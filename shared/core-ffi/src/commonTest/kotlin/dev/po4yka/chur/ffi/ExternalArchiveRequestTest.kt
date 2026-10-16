package dev.po4yka.chur.ffi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ExternalArchiveRequestTest {
    @Test
    fun request_has_exact_versioned_big_endian_fields_and_preserves_caller_password() {
        val password = "external-fixture-password".encodeToByteArray()
        val file = ExternalArchiveFile(7, "документ.bin", "application/octet-stream", 17)
        val unknown = ExternalArchiveFile(8, "unknown", "", null)
        val request = encodeExternalArchiveRequest(listOf(file, unknown), password, 1234)
        try {
            val reader = PrivateNoteReader(request)
            assertEquals(1, reader.byte())
            assertContentEquals(password, reader.sized(1024))
            assertEquals(1234L, reader.number())
            assertContentEquals(byteArrayOf(0, 0, 0, 2), reader.raw(4))
            assertEquals(file.displayName, reader.text(1024))
            assertEquals(file.contentType, reader.text(255))
            assertEquals(1, reader.byte()); assertEquals(17L, reader.number())
            assertEquals(unknown.displayName, reader.text(1024)); assertEquals("", reader.text(255))
            assertEquals(0, reader.byte()); reader.end()
            assertContentEquals("external-fixture-password".encodeToByteArray(), password)
            assertFalse(file.toString().contains(file.displayName))
            assertFalse(file.toString().contains(file.contentType))
        } finally { request.fill(0); password.fill(0) }
    }

    @Test
    fun invalid_names_metadata_lengths_descriptors_and_selection_are_refused() {
        val valid = ExternalArchiveFile(7, "fixture", "", 0)
        for (name in listOf("", ".", "..", "../private", "folder/file", "folder\\file", "bad\u0000name", "bad\nname", "é".repeat(513))) {
            assertFailsWith<IllegalArgumentException> { request(listOf(valid.copy(displayName = name))) }
        }
        for (file in listOf(valid.copy(descriptor = -1), valid.copy(contentType = "x".repeat(256)),
            valid.copy(knownLength = -1), valid.copy(knownLength = 4_294_967_297))) {
            assertFailsWith<IllegalArgumentException> { request(listOf(file)) }
        }
        assertFailsWith<IllegalArgumentException> { request(emptyList()) }
        assertFailsWith<IllegalArgumentException> { request(List(33) { valid }) }
        assertFailsWith<IllegalArgumentException> { request(List(2) { valid.copy(knownLength = 3_000_000_000) }) }
        assertFailsWith<IllegalArgumentException> { encodeExternalArchiveRequest(listOf(valid), byteArrayOf(), 1) }
        assertFailsWith<IllegalArgumentException> { encodeExternalArchiveRequest(listOf(valid), ByteArray(1025), 1) }
        assertFailsWith<IllegalArgumentException> { encodeExternalArchiveRequest(listOf(valid), byteArrayOf(1), 0) }
    }

    private fun request(files: List<ExternalArchiveFile>) {
        encodeExternalArchiveRequest(files, byteArrayOf(1), Long.MAX_VALUE).fill(0)
    }
}
