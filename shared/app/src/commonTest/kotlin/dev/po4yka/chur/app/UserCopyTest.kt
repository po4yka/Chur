package dev.po4yka.chur.app

import dev.po4yka.chur.core.model.ChurStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UserCopyTest {
    private val codeName = Regex("[A-Z]{2,}_[A-Z_]+")

    @Test
    fun every_status_reads_as_a_sentence_and_never_as_its_code() {
        // `ERROR_MODEL.md` "Layer mapping": the user reads the feature layer's
        // copy, never the stable code a message used to carry.
        for (status in ChurStatus.entries) {
            val copy = userCopy(status)
            assertTrue(copy.isNotBlank(), "$status has no copy")
            assertFalse(codeName.containsMatchIn(copy), "$status reads as a code: $copy")
            assertTrue(ChurStatus.entries.none { it.name in copy }, "$status names a code: $copy")
        }
    }

    @Test
    fun an_unknown_code_reads_as_an_internal_failure() {
        assertEquals(userCopy(ChurStatus.INTERNAL_FAILURE), userCopy(ChurStatus.fromValue(12_345)))
        assertEquals(userCopy(ChurStatus.INTERNAL_FAILURE), userCopy(ChurStatus.fromValue(-1)))
    }

    @Test
    fun every_credential_failure_reads_the_same() {
        // "Authentication errors" and `DECOY_VAULT.md` §11: the copy must not
        // say which slot or identity failed.
        val credential = listOf(
            ChurStatus.AUTHENTICATION_FAILED,
            ChurStatus.PLATFORM_KEY_UNAVAILABLE,
            ChurStatus.PLATFORM_KEY_INVALIDATED,
            ChurStatus.RECOVERY_REQUIRED,
        )
        assertEquals(
            setOf("Unable to unlock. Try again or use recovery."),
            credential.map(::userCopy).toSet(),
        )
    }

    @Test
    fun a_sync_refusal_names_the_bootstrap_secret_and_not_an_unlock() {
        // `SERVER_OPERATOR.md` answers every failed token check, a wrong
        // bootstrap secret included, with `AUTHENTICATION_FAILED`.
        val refusals = setOf(ChurStatus.AUTHENTICATION_FAILED, ChurStatus.PERMISSION_DENIED)
        for (status in refusals) {
            val copy = syncCopy(status)
            assertNotEquals(userCopy(status), copy, "$status reads as a failed unlock")
            assertTrue("bootstrap secret" in copy, "$status does not name the secret: $copy")
            assertFalse(codeName.containsMatchIn(copy), "$status reads as a code: $copy")
        }
        for (status in ChurStatus.entries - refusals) {
            assertEquals(userCopy(status), syncCopy(status))
        }
    }
}
