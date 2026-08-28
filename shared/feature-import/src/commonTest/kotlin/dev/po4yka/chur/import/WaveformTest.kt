package dev.po4yka.chur.imports

import dev.po4yka.chur.ffi.StreamKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The waveform folding of `docs/interop/MEDIA_PIPELINE.md` §6.1.
 *
 * The platform decoders that feed this cannot run in a host test, and the
 * folding is exactly the part that must not differ between them, which is why
 * it lives in common code and is tested here.
 */
class WaveformTest {

    @Test
    fun a_record_carries_its_count_and_its_duration() {
        val record = Waveform.record(183_000, ByteArray(512) { (it % 256).toByte() })
        assertEquals(Waveform.HEAD_LEN + 512, record.size)
        assertEquals(512, assertNotNull(Waveform.peaksOf(record)).size)
        assertEquals(183_000L, Waveform.durationMsOf(record))
    }

    @Test
    fun a_record_that_is_not_one_reads_as_nothing_rather_than_as_a_drawing() {
        assertNull(Waveform.peaksOf(ByteArray(4)))
        assertNull(Waveform.peaksOf(ByteArray(16)))
        val record = Waveform.record(1_000, ByteArray(8) { 1 })
        // A wrong version, a non-zero reserved byte, and a length that
        // contradicts the count are each refused; Rust refuses the same three.
        assertNull(Waveform.peaksOf(record.copyOf().also { it[0] = 2 }))
        assertNull(Waveform.peaksOf(record.copyOf().also { it[1] = 1 }))
        assertNull(Waveform.peaksOf(record + byteArrayOf(0)))
    }

    @Test
    fun the_loudest_sample_reaches_full_height_and_silence_stays_flat() {
        val loud = Waveform.Accumulator(expectedFrames = 8, buckets = 4)
        listOf<Short>(100, 200, 400, 800, 1_600, 3_200, 6_400, 12_800).forEach(loud::add)
        val peaks = assertNotNull(Waveform.peaksOf(loud.encode(1_000)))
        assertEquals(4, peaks.size)
        assertEquals(255.toByte(), peaks.last())
        assertTrue((peaks[0].toInt() and 0xff) < (peaks[3].toInt() and 0xff))

        val quiet = Waveform.Accumulator(expectedFrames = 4, buckets = 4)
        repeat(4) { quiet.add(0) }
        assertTrue(assertNotNull(Waveform.peaksOf(quiet.encode(500))).all { it == 0.toByte() })
    }

    /**
     * `Short.MIN_VALUE` has no positive counterpart in a `Short`. Negating it in
     * place wraps it back to itself, which reads as the quietest sample instead
     * of the loudest, so a recording clipped at the negative rail would draw as
     * silence.
     */
    @Test
    fun the_loudest_negative_sample_is_not_read_as_silence() {
        val accumulator = Waveform.Accumulator(expectedFrames = 2, buckets = 2)
        accumulator.add(Short.MIN_VALUE)
        accumulator.add(1)
        val peaks = assertNotNull(Waveform.peaksOf(accumulator.encode(100)))
        assertEquals(255.toByte(), peaks[0])
        assertEquals(0.toByte(), peaks[1])
    }

    @Test
    fun a_decoder_that_yields_more_frames_than_declared_does_not_overflow_a_bucket() {
        val accumulator = Waveform.Accumulator(expectedFrames = 4, buckets = 4)
        repeat(40) { accumulator.add((it * 100).toShort()) }
        val peaks = assertNotNull(Waveform.peaksOf(accumulator.encode(1_000)))
        assertEquals(4, peaks.size)
        assertEquals(40L, accumulator.sampleCount)
    }

    @Test
    fun little_endian_sample_blocks_fold_the_same_as_single_samples() {
        val block = Waveform.Accumulator(expectedFrames = 4, buckets = 2)
        // 0x0100 = 256 and 0x7fff = 32767, as little-endian pairs.
        block.addAll(byteArrayOf(0x00, 0x01, 0x00, 0x01, 0xff.toByte(), 0x7f, 0x00, 0x00))
        val singles = Waveform.Accumulator(expectedFrames = 4, buckets = 2)
        listOf<Short>(256, 256, 32_767, 0).forEach(singles::add)
        assertTrue(block.encode(10).contentEquals(singles.encode(10)))
    }

    /**
     * A container that reports no duration is ordinary: Android returns zero
     * when `METADATA_KEY_DURATION` is absent and iOS maps a NaN duration to
     * zero. §6.1 calls the record "a peak envelope over equal slices of a
     * recording", so an envelope that wrapped and folded a later passage onto
     * an earlier one would be a superposition of several rather than one.
     */
    @Test
    fun a_recording_of_unknown_length_produces_one_envelope_and_not_several() {
        val buckets = 8
        val accumulator = Waveform.Accumulator(expectedFrames = 0, buckets = buckets)
        // Forty times past one pass over the buckets, rising monotonically. An
        // envelope that wrapped would put a late, loud passage in an early
        // bucket and the result would not be monotonic; one that widens its
        // slice stays monotonic and simply uses fewer buckets.
        val total = buckets * 40
        repeat(total) { index -> accumulator.add((index + 1).toShort()) }
        val peaks = assertNotNull(Waveform.peaksOf(accumulator.encode(0)))
            .map { it.toInt() and 0xff }
        assertEquals(buckets, peaks.size)

        val used = peaks.indexOfLast { it > 0 } + 1
        assertTrue(
            used >= buckets / 2,
            "widening the slice left only $used of $buckets buckets, below the stated floor",
        )
        assertEquals(255, peaks[used - 1], "the loudest sample is not in the last used bucket")
        for (index in 1 until used) {
            assertTrue(
                peaks[index] >= peaks[index - 1],
                "bucket $index is quieter than the one before it, so a later passage folded back",
            )
        }
        assertTrue(peaks.drop(used).all { it == 0 }, "a bucket past the recording is not empty")
    }

    /**
     * §6 lists the waveform beside the data records rather than the pictures,
     * and §12 gives it no long edge. A generator that asked for one would be
     * asking the wrong question.
     */
    @Test
    fun a_waveform_has_no_long_edge_and_audio_asks_for_one() {
        assertNull(MediaBounds.longEdge(StreamKind.AUDIO_WAVEFORM))
        assertEquals(
            listOf(StreamKind.AUDIO_WAVEFORM),
            requiredDerivatives(
                ProbedMedia(MediaBounds.CLASS_AUDIO, 0, 0, 183_000, "audio/mp4"),
            ),
        )
        assertEquals(
            listOf(StreamKind.THUMBNAIL, StreamKind.VIDEO_POSTER),
            requiredDerivatives(
                ProbedMedia(MediaBounds.CLASS_VIDEO, 1_920, 1_080, 8_000, "video/mp4"),
            ),
        )
    }
}
