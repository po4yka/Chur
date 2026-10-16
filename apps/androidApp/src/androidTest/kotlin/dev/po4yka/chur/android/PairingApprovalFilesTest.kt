package dev.po4yka.chur.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production file reader with actual bounded stream reads. */
@RunWith(AndroidJUnit4::class)
class PairingApprovalFilesTest {
    @Test
    fun exactlySixteenMiBIsReadWithoutTruncation() {
        val input = PatternStream(PAIRING_APPROVAL_MAX.toLong())
        val result = readPairingApproval(input)
        assertEquals(PAIRING_APPROVAL_MAX, result.size)
        for (index in result.indices) {
            if (result[index] != pattern(index.toLong())) fail("incorrect byte at $index")
        }
        assertEquals(PAIRING_APPROVAL_MAX.toLong(), input.consumed)
        assertTrue("the reader must test EOF at the exact limit", input.sawEof)
    }

    @Test
    fun anOversizedProviderIsStoppedAfterOneExtraByte() {
        val input = PatternStream(PAIRING_APPROVAL_MAX.toLong() + 1_000_000)
        val refusal = refuses { readPairingApproval(input) }
        assertTrue(refusal.message!!.contains("size limit"))
        assertEquals(PAIRING_APPROVAL_MAX.toLong() + 1, input.consumed)
        assertEquals(false, input.sawEof)
    }

    @Test
    fun aZeroLengthReadFailsImmediatelyInsteadOfSpinning() {
        var calls = 0
        val input = object : InputStream() {
            override fun read(): Int = error("the production reader uses bounded bulk reads")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { calls++; return 0 }
        }
        val refusal = refuses { readPairingApproval(input) }
        assertTrue(refusal.message!!.contains("no read progress"))
        assertEquals(1, calls)
    }

    @Test
    fun anEmptyFileEndsAtEofWithoutTreatingItAsAStalledProvider() {
        assertEquals(0, readPairingApproval(ByteArrayInputStream(byteArrayOf())).size)
    }

    private fun refuses(body: () -> Unit): IllegalArgumentException {
        try { body() } catch (failure: IllegalArgumentException) { return failure }
        fail("the reader must refuse this stream")
        error("unreachable")
    }

    private class PatternStream(private val size: Long) : InputStream() {
        var consumed = 0L
            private set
        var sawEof = false
            private set
        private var calls = 0
        override fun read(): Int = error("the production reader uses bounded bulk reads")
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            require(length > 0) { "the production reader must always request progress" }
            if (consumed == size) { sawEof = true; return -1 }
            val amount = minOf(length.toLong(), size - consumed, (1 + calls++ % 3) * 4096L).toInt()
            repeat(amount) { bytes[offset + it] = pattern(consumed + it) }
            consumed += amount
            return amount
        }
    }

    companion object {
        private fun pattern(index: Long): Byte = ((index * 31 + index / 97) and 255).toByte()
    }
}
