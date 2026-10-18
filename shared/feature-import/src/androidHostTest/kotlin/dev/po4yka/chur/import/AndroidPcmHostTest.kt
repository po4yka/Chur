package dev.po4yka.chur.imports

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidPcmHostTest {
    @Test
    fun pcm8BitIsUnsignedAndOtherIntegerWidthsAreSigned() {
        checkPcm(AudioFormat.ENCODING_PCM_8BIT, byteArrayOf(128.toByte(), 192.toByte(), 0), shortArrayOf(0, 16384, -32768))
        checkPcm(AudioFormat.ENCODING_PCM_16BIT,
            ByteBuffer.allocate(6).order(ByteOrder.nativeOrder()).putShort(0).putShort(16384).putShort(-32768).array(),
            shortArrayOf(0, 16384, -32768))
        checkPcm(AudioFormat.ENCODING_PCM_24BIT_PACKED,
            byteArrayOf(0, 0, 0, 0, 0, 64, 0, 0, 128.toByte()), shortArrayOf(0, 16384, -32768))
        checkPcm(AudioFormat.ENCODING_PCM_32BIT,
            ByteBuffer.allocate(12).order(ByteOrder.nativeOrder()).putInt(0).putInt(1 shl 30).putInt(Int.MIN_VALUE).array(),
            shortArrayOf(0, 16384, -32768))
    }

    @Test
    fun floatSamplesAreClippedAndConvertedRatherThanReadAsIntegerBits() {
        checkPcm(AudioFormat.ENCODING_PCM_FLOAT,
            ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).putFloat(0f).putFloat(0.5f).putFloat(-1f).putFloat(2f).array(),
            shortArrayOf(0, 16383, -32767, 32767))
    }

    @Test
    fun unsupportedPartialAndNonFiniteOutputDoesNotProduceAValidWaveform() {
        val into = Waveform.Accumulator(1)
        assertFalse(addAndroidPcm(ByteBuffer.wrap(byteArrayOf(1)), AudioFormat.ENCODING_PCM_16BIT, into))
        assertFalse(addAndroidPcm(ByteBuffer.allocate(0), -100, into))
        assertFalse(addAndroidPcm(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).apply { putFloat(Float.NaN); flip() },
            AudioFormat.ENCODING_PCM_FLOAT, into))
        assertEquals(0L, into.sampleCount)
    }

    private fun checkPcm(encoding: Int, bytes: ByteArray, expected: ShortArray) {
        val actual = Waveform.Accumulator(expected.size.toLong(), expected.size)
        val reference = Waveform.Accumulator(expected.size.toLong(), expected.size)
        expected.forEach(reference::add)
        assertTrue(addAndroidPcm(ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()), encoding, actual))
        assertEquals(expected.size.toLong(), actual.sampleCount)
        assertContentEquals(reference.encode(1), actual.encode(1))
    }
}
