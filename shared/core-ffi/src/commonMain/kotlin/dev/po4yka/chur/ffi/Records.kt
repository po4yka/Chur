package dev.po4yka.chur.ffi

import dev.po4yka.chur.core.model.ChurStatus

/**
 * The canonical boundary records of `docs/interop/FFI_CONTRACT.md` §6.4 and
 * §6.5.
 *
 * Every one is big-endian, which `docs/format/CANONICAL_ENCODING_V1.md` §3
 * fixes for every Chur integer, and every decoder rejects trailing bytes, which
 * the same section requires. These are boundary bytes rather than persisted
 * ones, so a truncated record is [ChurStatus.INVALID_INPUT] rather than a
 * corruption verdict.
 *
 * Nothing here is an encoder for a persisted record. §13 of that document
 * reserves that for Rust, and this file decodes what Rust produced.
 */

/** Length of the identifier of `CANONICAL_ENCODING_V1.md` §8. */
const val ID_LENGTH: Int = 16

/** Length of the page cursor of `CATALOG_SCHEMA_V1.md` §16.2. */
const val CURSOR_LENGTH: Int = 42

/** Length of `ObjectProjectionV1` of `CATALOG_SCHEMA_V1.md` §16.1. */
const val PROJECTION_LENGTH: Int = 79

/** Length of the `ChurPageV1` header of `FFI_CONTRACT.md` §6.4. */
const val PAGE_HEADER_LENGTH: Int = 63

/** Length of the device secret a slot operation hands back, §6.5. */
const val SECRET_LENGTH: Int = 32

/** The 96-bit GCM nonce of `KEY_SLOTS.md` §4. */
const val GCM_NONCE_LENGTH: Int = 12

/** A wrapped 32-byte key and its 16-byte tag. */
const val WRAPPED_KEY_LENGTH: Int = 48

/** Length of an Ed25519 or X25519 public key. */
const val PUBLIC_KEY_LENGTH: Int = 32

/**
 * The largest recovery phrase, §6.5.
 *
 * Twenty-four words of the BIP-39 English list, whose longest entry is eight
 * characters, plus the separators.
 */
const val RECOVERY_PHRASE_MAX: Int = 24 * 9

/** One row of a library page, §16.1. */
data class ObjectProjection(
    val objectId: ByteArray,
    val primaryStreamId: ByteArray,
    val mediaKind: Int,
    val captureTimeMs: Long,
    val importTimeMs: Long,
    val captureTimeSubstituted: Boolean,
    val plaintextSize: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val favorite: Boolean,
    val state: Int,
    val integritySummary: Int,
    val thumbnailReady: Boolean,
) {
    /** The identifier as lowercase hexadecimal, for a key in a list. */
    val id: String get() = objectId.toHex()

    override fun equals(other: Any?): Boolean = other is ObjectProjection && id == other.id

    override fun hashCode(): Int = id.hashCode()
}

/** One page of results, §16.2. */
data class ObjectPage(
    val objects: List<ObjectProjection>,
    val totalCount: Long,
    val catalogGeneration: Long,
    val nextCursor: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is ObjectPage &&
            objects == other.objects &&
            totalCount == other.totalCount &&
            catalogGeneration == other.catalogGeneration

    override fun hashCode(): Int = objects.hashCode() * 31 + catalogGeneration.hashCode()
}

/** One album, §6.5. */
data class AlbumSummary(val albumId: ByteArray, val memberCount: Long, val name: String) {
    val id: String get() = albumId.toHex()

    override fun equals(other: Any?): Boolean = other is AlbumSummary && id == other.id

    override fun hashCode(): Int = id.hashCode()
}

/** One key slot, §6.5. */
data class SlotSummary(val slotId: ByteArray, val slotType: Int, val generation: Long) {
    val id: String get() = slotId.toHex()

    /** The family name `docs/security/KEY_SLOTS.md` §1 uses. */
    val familyName: String
        get() = when (slotType) {
            1 -> "Password"
            2 -> "Android Keystore"
            3 -> "Apple Keychain"
            4 -> "Recovery"
            5 -> "Peer device"
            else -> "Unknown"
        }

    /** Whether the family is portable, which §10 of that document fixes. */
    val portable: Boolean get() = slotType == 1 || slotType == 4

    override fun equals(other: Any?): Boolean = other is SlotSummary && id == other.id

    override fun hashCode(): Int = id.hashCode()
}

/** One object's detail record, §6.5. */
data class ObjectDetail(
    val captureTimeMs: Long,
    val importTimeMs: Long,
    val captureTimeSubstituted: Boolean,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val plaintextSize: Long,
    val contentType: String,
    val filename: String,
    val caption: String,
    val tags: List<Pair<ByteArray, String>>,
)

/** Public material needed to discover and address one sharing device. */
class SharingIdentity(
    val vaultId: ByteArray,
    val deviceId: ByteArray,
    val signingPublicKey: ByteArray,
    val hpkePublicKey: ByteArray,
    val fingerprint: String,
    val enrollment: ByteArray,
    val initialOperation: ByteArray,
)

/** Decodes the collection-sharing identity record of §6.9. */
fun decodeSharingIdentity(bytes: ByteArray, length: Int): SharingIdentity {
    val reader = RecordReader(bytes, length)
    if (reader.short() != 1) {
        throw ChurFailure(ChurStatus.NON_CANONICAL_ENCODING, "the sharing identity version")
    }
    val identity = SharingIdentity(
        vaultId = reader.take(ID_LENGTH),
        deviceId = reader.take(ID_LENGTH),
        signingPublicKey = reader.take(PUBLIC_KEY_LENGTH),
        hpkePublicKey = reader.take(PUBLIC_KEY_LENGTH),
        fingerprint = reader.bounded().decodeToString(),
        enrollment = reader.bounded(),
        initialOperation = reader.bounded(),
    )
    reader.requireExhausted()
    return identity
}

/** Decodes `ChurPageV1`, §6.4. */
fun decodeObjectPage(bytes: ByteArray, length: Int): ObjectPage {
    val reader = RecordReader(bytes, length)
    val total = reader.long()
    val generation = reader.long()
    val count = reader.int()
    val cursorPresent = reader.byte()
    val cursor = reader.take(CURSOR_LENGTH)
    if (cursorPresent != 0 && cursorPresent != 1) {
        throw ChurFailure(ChurStatus.NON_CANONICAL_ENCODING, "the cursor presence byte")
    }
    val objects = ArrayList<ObjectProjection>(count)
    repeat(count) { objects.add(decodeProjection(reader.take(PROJECTION_LENGTH))) }
    reader.requireExhausted()
    return ObjectPage(objects, total, generation, if (cursorPresent == 1) cursor else null)
}

/** Decodes one `ObjectProjectionV1`, §16.1. */
fun decodeProjection(bytes: ByteArray): ObjectProjection {
    val reader = RecordReader(bytes, bytes.size)
    val projection = ObjectProjection(
        objectId = reader.take(ID_LENGTH),
        primaryStreamId = reader.take(ID_LENGTH),
        mediaKind = reader.short(),
        captureTimeMs = reader.long(),
        importTimeMs = reader.long(),
        captureTimeSubstituted = reader.flag(),
        plaintextSize = reader.long(),
        width = reader.int(),
        height = reader.int(),
        durationMs = reader.long(),
        favorite = reader.flag(),
        state = reader.byte(),
        integritySummary = reader.byte(),
        thumbnailReady = reader.flag(),
    )
    reader.requireExhausted()
    return projection
}

/** Decodes `ChurAlbumListV1`, §6.5. */
fun decodeAlbumList(bytes: ByteArray, length: Int): List<AlbumSummary> {
    val reader = RecordReader(bytes, length)
    val count = reader.int()
    val albums = ArrayList<AlbumSummary>(count)
    repeat(count) {
        albums.add(AlbumSummary(reader.take(ID_LENGTH), reader.long(), reader.text()))
    }
    reader.requireExhausted()
    return albums
}

/** Decodes `ChurSlotListV1`, §6.5. */
fun decodeSlotList(bytes: ByteArray, length: Int): List<SlotSummary> {
    val reader = RecordReader(bytes, length)
    val count = reader.int()
    val slots = ArrayList<SlotSummary>(count)
    repeat(count) {
        slots.add(SlotSummary(reader.take(ID_LENGTH), reader.byte(), reader.long()))
    }
    reader.requireExhausted()
    return slots
}

/**
 * What the Android Keystore needs to wrap the root, §6.6.
 *
 * [rootSecret] is the vault root. `KEY_SLOTS.md` §4 puts the AEAD in the
 * Keystore, so the bytes have to reach it; ADR-0041 records the exception and
 * requires the holder to clear the array as soon as the wrap returns.
 */
class KeystoreEnrollment(
    val alias: ByteArray,
    val aad: ByteArray,
    val rootSecret: ByteArray,
)

/** What the Android Keystore needs to unwrap the root, §6.6. Nothing is secret. */
data class KeystoreMaterial(
    val alias: ByteArray,
    val aad: ByteArray,
    val gcmNonce: ByteArray,
    val wrappedRootSecret: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is KeystoreMaterial &&
            alias.contentEquals(other.alias) &&
            aad.contentEquals(other.aad) &&
            gcmNonce.contentEquals(other.gcmNonce) &&
            wrappedRootSecret.contentEquals(other.wrappedRootSecret)

    override fun hashCode(): Int = alias.contentHashCode()
}

/** Decodes the enrollment record of §6.6. */
fun decodeKeystoreEnrollment(bytes: ByteArray, length: Int): KeystoreEnrollment {
    val reader = RecordReader(bytes, length)
    val enrollment = KeystoreEnrollment(
        alias = reader.bounded(),
        aad = reader.bounded(),
        rootSecret = reader.take(SECRET_LENGTH),
    )
    reader.requireExhausted()
    return enrollment
}

/** Decodes the material record of §6.6. */
fun decodeKeystoreMaterial(bytes: ByteArray, length: Int): List<KeystoreMaterial> {
    val reader = RecordReader(bytes, length)
    val count = reader.int()
    val material = ArrayList<KeystoreMaterial>(count)
    repeat(count) {
        material.add(
            KeystoreMaterial(
                alias = reader.bounded(),
                aad = reader.bounded(),
                gcmNonce = reader.take(GCM_NONCE_LENGTH),
                wrappedRootSecret = reader.take(WRAPPED_KEY_LENGTH),
            ),
        )
    }
    reader.requireExhausted()
    return material
}

/** Decodes `ChurObjectMetadataV1`, §6.5. */
fun decodeObjectDetail(bytes: ByteArray, length: Int): ObjectDetail {
    val reader = RecordReader(bytes, length)
    val detail = ObjectDetail(
        captureTimeMs = reader.long(),
        importTimeMs = reader.long(),
        captureTimeSubstituted = reader.flag(),
        width = reader.int(),
        height = reader.int(),
        durationMs = reader.long(),
        plaintextSize = reader.long(),
        contentType = reader.text(),
        filename = reader.text(),
        caption = reader.text(),
        tags = buildList {
            repeat(reader.short()) { add(reader.take(ID_LENGTH) to reader.text()) }
        },
    )
    reader.requireExhausted()
    return detail
}

/** Lowercase hexadecimal, which is how every identifier is shown and keyed. */
fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xff
        out.append(digits[value ushr 4])
        out.append(digits[value and 0x0f])
    }
    return out.toString()
}

/** Parses 32 lowercase hexadecimal characters into an identifier. */
fun String.fromHex(): ByteArray {
    require(length % 2 == 0) { "a hexadecimal string has an even length" }
    return ByteArray(length / 2) { index ->
        val high = digitOf(this[index * 2])
        val low = digitOf(this[index * 2 + 1])
        ((high shl 4) or low).toByte()
    }
}

private fun digitOf(character: Char): Int = when (character) {
    in '0'..'9' -> character - '0'
    in 'a'..'f' -> character - 'a' + 10
    in 'A'..'F' -> character - 'A' + 10
    else -> throw IllegalArgumentException("not a hexadecimal digit")
}

/**
 * A big-endian reader over a boundary record.
 *
 * It fails with [ChurFailure] rather than an index exception, so a truncated
 * record reaches the caller as the status the boundary would have used.
 */
private class RecordReader(private val bytes: ByteArray, private val limit: Int) {
    private var at = 0

    fun take(length: Int): ByteArray {
        require(length)
        val slice = bytes.copyOfRange(at, at + length)
        at += length
        return slice
    }

    fun byte(): Int {
        require(1)
        return bytes[at++].toInt() and 0xff
    }

    fun flag(): Boolean = when (val value = byte()) {
        0 -> false
        1 -> true
        else -> throw ChurFailure(
            ChurStatus.NON_CANONICAL_ENCODING,
            "a boolean is neither 0x00 nor 0x01 but $value",
        )
    }

    fun short(): Int {
        require(2)
        var value = 0
        repeat(2) { value = (value shl 8) or (bytes[at++].toInt() and 0xff) }
        return value
    }

    fun int(): Int {
        require(4)
        var value = 0
        repeat(4) { value = (value shl 8) or (bytes[at++].toInt() and 0xff) }
        return value
    }

    fun long(): Long {
        require(8)
        var value = 0L
        repeat(8) { value = (value shl 8) or (bytes[at++].toLong() and 0xff) }
        return value
    }

    fun text(): String {
        val length = short()
        return take(length).decodeToString()
    }

    /** A `u32` length and the bytes it counts. */
    fun bounded(): ByteArray = take(int())

    fun requireExhausted() {
        if (at != limit) {
            throw ChurFailure(
                ChurStatus.NON_CANONICAL_ENCODING,
                "the record carries trailing bytes",
            )
        }
    }

    private fun require(length: Int) {
        if (at + length > limit) {
            throw ChurFailure(ChurStatus.INVALID_INPUT, "the record ends inside a field")
        }
    }
}
