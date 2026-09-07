package dev.po4yka.chur.core.platformkeys

import androidx.biometric.BiometricManager.Authenticators
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The authenticator combination the device-slot prompt may ask for.
 *
 * androidx.biometric 1.1.0 validates the combination inside
 * `PromptInfo.Builder.build`. Its `AuthenticatorUtils.isSupportedCombination`
 * answers `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (32783) with true only when
 * the level is below 28 or above 29, and `build` throws
 * `IllegalArgumentException` otherwise. `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`
 * (33023) is true at every level, and `BIOMETRIC_STRONG` (15) is as well.
 *
 * minSdk is 29 and ADR-0017 makes API 29 the supported floor, so the first
 * combination made `CONVENIENT` throw before the prompt was shown, and
 * `KEY_SLOTS.md` §4 could not gate anything on that device.
 */
class PromptAuthenticatorsTest {

    @Test
    fun the_convenient_prompt_takes_a_combination_the_library_accepts_on_the_floor_device() {
        assertEquals(
            Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL,
            allowedAuthenticators(DeviceSlotPolicy.CONVENIENT, 29),
        )
    }

    @Test
    fun the_convenient_prompt_takes_the_strong_class_where_the_library_accepts_it() {
        assertEquals(
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL,
            allowedAuthenticators(DeviceSlotPolicy.CONVENIENT, 30),
        )
    }

    @Test
    fun the_strict_prompt_admits_no_device_credential_at_any_level() {
        assertEquals(
            Authenticators.BIOMETRIC_STRONG,
            allowedAuthenticators(DeviceSlotPolicy.STRICT, 29),
        )
        assertEquals(
            Authenticators.BIOMETRIC_STRONG,
            allowedAuthenticators(DeviceSlotPolicy.STRICT, 34),
        )
    }
}
