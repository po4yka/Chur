package dev.po4yka.chur.app.vault

import dev.po4yka.chur.ffi.withChurBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The viewer's decode of an original on iOS, `MEDIA_PIPELINE.md` §11: an
 * original has not had its orientation normalized, so the decoder must apply
 * the EXIF orientation itself. It reads the original from a native buffer.
 */
class DecodeForDisplayIosTest {
    @Test
    fun an_original_is_decoded_upright_and_scaled_to_its_bound() {
        val image = assertNotNull(decode(ROTATED, 2_048))
        assertEquals(2 to 6, image.width to image.height)
        // The pixels were drawn, not left as allocated: a JPEG is opaque.
        val pixel = IntArray(1).also { image.readPixels(it, 0, 0, 1, 1) }[0]
        assertEquals(0xFF, pixel ushr 24)
        val scaled = assertNotNull(decode(ROTATED, 3))
        assertEquals(1 to 3, scaled.width to scaled.height)
        assertNull(decode(ByteArray(64), 2_048))
    }

    private fun decode(bytes: ByteArray, maxEdgePx: Int) = withChurBuffer(bytes.size) { buffer ->
        buffer.copyIn(bytes)
        decodeForDisplay(buffer, maxEdgePx)
    }

    private companion object {
        /** A 6 by 2 px baseline JPEG whose EXIF orientation 6 turns it upright to 2 by 6. */
        val ROTATED: ByteArray = (
            "ffd8ffe000104a46494600010100000100010000ffe100224578696600004d4d002a0000000800010112000300000001" +
                "0006000000000000ffdb004300100b0c0e0c0a100e0d0e1211101318281a181616183123251d283a333d3c3933383740" +
                "485c4e404457453738506d51575f626768673e4d71797064785c656763ffdb0043011112121815182f1a1a2f63423842" +
                "636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363" +
                "6363ffc00011080002000603012200021101031101ffc4001500010100000000000000000000000000000005ffc40014" +
                "100100000000000000000000000000000000ffc4001501010100000000000000000000000000000406ffc40014110100" +
                "000000000000000000000000000000ffda000c03010002110311003f0096004a67ffd9"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
