package dev.po4yka.chur.sync

/**
 * Lowercase hexadecimal, the encoding every sync route uses for the bytes it
 * carries in a URL path.
 */
internal fun ByteArray.toHex(): String =
    buildString(size * 2) {
        for (byte in this@toHex) {
            append(DIGITS[(byte.toInt() ushr 4) and 15])
            append(DIGITS[byte.toInt() and 15])
        }
    }

/** The bytes a hexadecimal string named, `IllegalArgumentException` when it names none. */
internal fun fromHex(value: String): ByteArray {
    require(value.length % 2 == 0) { "hexadecimal text must have an even length" }
    val bytes = ByteArray(value.length / 2)
    for (index in bytes.indices) {
        bytes[index] = ((digitAt(value, index * 2) shl 4) or digitAt(value, index * 2 + 1)).toByte()
    }
    return bytes
}

private fun digitAt(
    value: String,
    index: Int,
): Int {
    val digit = value[index].digitToIntOrNull(16)
    require(digit != null) { "hexadecimal text has a digit that is not hexadecimal" }
    return digit
}

private const val DIGITS = "0123456789abcdef"
