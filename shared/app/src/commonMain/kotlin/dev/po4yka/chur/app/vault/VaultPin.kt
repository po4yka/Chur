package dev.po4yka.chur.app.vault

internal fun isValidVaultPin(pin: String): Boolean =
    pin.length in 12..20 && pin.all { it in '0'..'9' }
