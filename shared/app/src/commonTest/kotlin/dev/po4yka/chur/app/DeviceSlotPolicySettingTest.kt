package dev.po4yka.chur.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The setting a host persists and the binding reads, `KEY_SLOTS.md` section 1. */
class DeviceSlotPolicySettingTest {
    @Test
    fun a_choice_round_trips_through_the_setting() {
        var stored = false
        val setting = DeviceSlotPolicySetting(reader = { stored }, writer = { stored = it })
        assertFalse(setting.read())
        setting.write(true)
        assertTrue(setting.read())
    }

    @Test
    fun the_unset_default_is_convenient_and_keeps_nothing() {
        val setting = DeviceSlotPolicySetting.unset()
        assertFalse(setting.read())
        setting.write(true)
        assertFalse(setting.read())
    }
}
