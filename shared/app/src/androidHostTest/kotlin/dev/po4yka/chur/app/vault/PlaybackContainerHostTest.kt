package dev.po4yka.chur.app.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackContainerHostTest {
    @Test
    fun actual_encoded_dts_is_declared_by_esds_even_though_the_sample_entry_is_mp4a() {
        val bytes = fixture("video-diagnostics/hevc-dts.mp4")
        try {
            val returned = mutableListOf<ByteArray>()
            val facts = inspectPlaybackContainerRanges(bytes.size.toLong()) { at, size ->
                bytes.copyOfRange(at.toInt(), at.toInt() + size).also { returned += it }
            }
            assertTrue(facts.complete)
            assertEquals(listOf(PlaybackAudioFormat.DTS), facts.audio)
            assertEquals(PlaybackVideoFormat.HEVC_MAIN10, facts.video.single().format)
            assertTrue(returned.all { buffer -> buffer.all { it == 0.toByte() } })
        } finally { bytes.fill(0) }
    }

    @Test
    fun actual_aac_and_dolby_vision_are_declared_without_treating_mp4a_as_proof_of_aac() {
        val aac = fixture("hevc-main10/sdr10-hvc1.mp4")
        val camera = fixture("video-acceptance/camera-dolby-vision.mov")
        try {
            assertEquals(listOf(PlaybackAudioFormat.AAC), inspect(aac).audio)
            assertEquals(PlaybackVideoFormat.DOLBY_VISION, inspect(camera).video.single().format)
            val esds = aac.indices.first { at -> at + 4 <= aac.size && aac.copyOfRange(at, at + 4).contentEquals("esds".toByteArray()) }
            "free".toByteArray().copyInto(aac, esds)
            assertEquals(listOf(PlaybackAudioFormat.UNKNOWN), inspect(aac).audio)
        } finally { aac.fill(0); camera.fill(0) }
    }

    @Test
    fun actual_truncated_container_returns_unknown_and_compound_range_offsets_are_honored() {
        val malformed = fixture("video-diagnostics/truncated-container.mp4")
        val movie = fixture("video-diagnostics/hevc-dts.mp4")
        val wrapped = ByteArray(31) + movie + ByteArray(23)
        try {
            assertFalse(inspect(malformed).complete)
            val facts = inspectPlaybackContainerRanges(movie.size.toLong()) { at, size ->
                wrapped.copyOfRange(31 + at.toInt(), 31 + at.toInt() + size)
            }
            assertEquals(listOf(PlaybackAudioFormat.DTS), facts.audio)
        } finally { malformed.fill(0); movie.fill(0); wrapped.fill(0) }
    }

    private fun inspect(bytes: ByteArray) = inspectPlaybackContainerRanges(bytes.size.toLong()) { at, size ->
        bytes.copyOfRange(at.toInt(), at.toInt() + size)
    }

    private fun fixture(name: String): ByteArray = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .take(6).map { File(it, "tests/media/$name") }.first { it.isFile }.readBytes()
}
