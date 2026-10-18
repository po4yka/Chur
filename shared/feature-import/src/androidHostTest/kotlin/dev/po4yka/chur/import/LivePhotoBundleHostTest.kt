package dev.po4yka.chur.imports

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LivePhotoBundleHostTest {
    private val fixtures: File get() = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "tests/media/live-photo-v1") }.first { File(it, "header.bin").isFile }
    private fun header() = File(fixtures, "header.bin").readBytes()

    @Test
    fun sharedGoldenHeaderRoundTripsAllMetadataAndUnknownResources() {
        val bytes = header()
        val bundle = LivePhotoBundle.parse(bytes, 358)
        assertEquals(1_700_000_123_456L, bundle.captureTimeMs)
        assertEquals(listOf(1, 9, 42), bundle.resources.map { it.type })
        assertEquals("снимок.JPG", bundle.displayPhoto.originalFilename)
        assertEquals("com.apple.quicktime-movie", bundle.displayVideo.uniformTypeIdentifier)
        assertEquals(listOf(305L, 319L, 343L), bundle.resources.map { it.offset })
        assertContentEquals(bytes, LivePhotoBundle.encodeHeader(bundle.captureTimeMs, bundle.resources))
        val payload = File(fixtures, "payload.bin").readBytes()
        for (resource in bundle.resources) {
            val start = (resource.offset - bundle.headerLength).toInt()
            assertContentEquals(resource.sha256, MessageDigest.getInstance("SHA-256")
                .digest(payload.copyOfRange(start, start + resource.byteLength.toInt())))
        }
    }

    @Test
    fun malformedBoundsStringsVersionsAndIncompletePairsAreRefused() {
        val bytes = header()
        val mutations = listOf<(ByteArray) -> Unit>(
            { it[9] = 2 }, // unknown version
            { it[11] = 1 }, // missing required pair
            { it[15] = 0 }, // inconsistent header length
            { it[24] = 0x80.toByte() }, // unsigned role outside public positive Int
            { it[28] = 0x80.toByte() }, // unsigned size exceeds signed range
            { it[74] = 0 }, // embedded NUL in filename
            { it[74] = 0xFF.toByte() }, // invalid UTF-8
            { it[68] = 0; it[69] = 0 }, // empty filename
        )
        for (mutation in mutations) assertFailsWith<LivePhotoBundleException> {
            LivePhotoBundle.parse(bytes.copyOf().also(mutation), 358)
        }
        assertFailsWith<LivePhotoBundleException> { LivePhotoBundle.parse(bytes, 357) }
        assertFailsWith<LivePhotoBundleException> { LivePhotoBundle.parse(bytes, 359) }
        assertFailsWith<LivePhotoBundleException> { LivePhotoBundle.parse(bytes, LivePhotoBundle.MAX_BYTES + 1) }
        assertFailsWith<LivePhotoBundleException> { LivePhotoBundle.parse(bytes.copyOf(bytes.size - 1), 358) }
    }

    @Test
    fun displayPairUsesCompleteFullSizePairButPreservesEveryResource() {
        val base = LivePhotoBundle.parse(header(), 358)
        val extra = listOf(base.displayPhoto.copy(type = 5), base.displayVideo.copy(type = 10), base.displayPhoto.copy(type = 42))
        val resources = base.resources + extra
        val bytes = LivePhotoBundle.encodeHeader(null, resources)
        val bundle = LivePhotoBundle.parse(bytes, bytes.size + resources.sumOf { it.byteLength })
        assertEquals(5, bundle.displayPhoto.type)
        assertEquals(10, bundle.displayVideo.type)
        assertEquals(6, bundle.resources.size)
        val onlyPhoto = base.resources + base.displayPhoto.copy(type = 5)
        val partial = LivePhotoBundle.encodeHeader(null, onlyPhoto)
        assertEquals(1, LivePhotoBundle.parse(partial, partial.size + onlyPhoto.sumOf { it.byteLength }).displayPhoto.type)
        assertTrue(LivePhotoBundle.hasMagic(bytes))
        assertFailsWith<LivePhotoBundleException> {
            LivePhotoBundle.encodeHeader(null, base.resources + base.displayPhoto)
        }
    }
}
