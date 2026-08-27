package dev.po4yka.chur.core.platformkeys

import dev.po4yka.chur.core.model.ChurStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The part of the device-slot prototype that runs without a device.
 *
 * `docs/security/KEY_SLOTS.md` §4 and §5 both require the platform name of a
 * slot to be opaque and to reveal no real or decoy identity. The name is
 * derived here, in common code, so both platforms produce the same one and one
 * test covers both.
 */
class PlatformAliasTest {
    @Test
    fun an_alias_is_the_fixed_prefix_and_the_identifier_in_lowercase_hexadecimal() {
        val identifier = ByteArray(16) { index -> (index * 17).toByte() }
        val alias = platformAlias(identifier)
        assertTrue(alias.startsWith("dev.po4yka.chur.slot."))
        val hex = alias.removePrefix("dev.po4yka.chur.slot.")
        assertEquals(32, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals("00112233445566778899aabbccddeeff", hex)
    }

    @Test
    fun the_identifier_length_is_bounded_at_both_ends() {
        assertFailsWith<IllegalArgumentException> { platformAlias(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { platformAlias(ByteArray(65)) }
        platformAlias(ByteArray(16) { 1 })
        platformAlias(ByteArray(64) { 1 })
    }

    @Test
    fun two_identifiers_never_share_an_alias() {
        val first = platformAlias(ByteArray(16) { 1 })
        val second = platformAlias(ByteArray(16) { index -> if (index == 15) 2 else 1 })
        assertTrue(first != second)
    }

    @Test
    fun an_alias_carries_no_role_and_no_vault() {
        // DECOY_VAULT.md forbids anything that tells a real identity from a
        // decoy one. The alias is a rendering of CSPRNG bytes and nothing else,
        // so this asserts the absence of every word that would give one away.
        val alias = platformAlias(ByteArray(32) { 0x5a })
        for (word in listOf("real", "decoy", "vault", "primary", "secondary", "hidden")) {
            assertFalse(alias.contains(word), "an alias must not contain \"$word\"")
        }
    }

    @Test
    fun a_slot_failure_carries_a_stable_code_and_a_constant_detail() {
        val failure = DeviceSlotException(
            ChurStatus.PLATFORM_KEY_INVALIDATED,
            "the platform factor can no longer unwrap",
        )
        assertEquals(ChurStatus.PLATFORM_KEY_INVALIDATED, failure.status)
        assertEquals(
            "PLATFORM_KEY_INVALIDATED: the platform factor can no longer unwrap",
            failure.message,
        )
    }

    @Test
    fun both_policies_exist_and_the_default_is_named() {
        // The product mode selects one; neither removes the portable-slot
        // requirement of KEY_SLOTS.md section 1.
        assertEquals(2, DeviceSlotPolicy.entries.size)
        assertEquals(DeviceSlotPolicy.CONVENIENT, DeviceSlotPolicy.entries.first())
    }
}
