package dev.po4yka.chur.imports

import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The photo picker's copy has a random name, `IOS.md` §12, so the catalog
 * must record the name the picker gave the item and not the copy's.
 */
class IosMediaCodecTest {
    @Test
    fun a_picked_copy_keeps_the_items_own_name_and_type() {
        val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".heic"
        assertTrue(NSFileManager.defaultManager.createFileAtPath(path, null, null))
        try {
            val named = assertNotNull(IosMediaCodec().open(NSURL.fileURLWithPath(path), "IMG_0001.heic"))
            named.close()
            assertEquals("IMG_0001.heic", named.originalFilename)
            assertEquals("image/heic", named.contentTypeHint)

            val unnamed = assertNotNull(IosMediaCodec().open(NSURL.fileURLWithPath(path), null))
            unnamed.close()
            assertNull(unnamed.originalFilename)
        } finally {
            unlink(path)
        }
    }
}
