package dev.po4yka.chur.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `test-vectors/v1/manifest.json` index.
 *
 * `docs/format/TEST_VECTORS.md` section 2 fixes the metadata and section 7
 * requires the same suite to run on every platform. This is the Kotlin reader
 * of that index. It parses and checks; it decodes no protocol record, because
 * section 13 of the encoding profile reserves that for Rust.
 */
@Serializable
public data class VectorManifest(
    @SerialName("manifest_version") val manifestVersion: Int,
    @SerialName("spec_commit") val specCommit: String,
    val generator: VectorGenerator,
    val vectors: List<VectorEntry>,
) {
    public companion object {
        // A field the manifest carries and this reader does not model is a
        // change to the layout, not something to skip past.
        private val json = Json { ignoreUnknownKeys = false }

        /** Parses a manifest. */
        public fun parse(text: String): VectorManifest = json.decodeFromString(serializer(), text)
    }
}

/** What produced a vector set. */
@Serializable
public data class VectorGenerator(
    val name: String,
    val version: String,
    val commit: String,
    val toolchain: String,
)

/** One vector. */
@Serializable
public data class VectorEntry(
    @SerialName("vector_id") val vectorId: String,
    val spec: String,
    @SerialName("spec_section") val specSection: String,
    val purpose: String,
    val outcome: Outcome,
    val inputs: Map<String, JsonElement> = emptyMap(),
    val expected: Map<String, JsonElement> = emptyMap(),
    val decoded: Map<String, JsonElement> = emptyMap(),
    @SerialName("error_code") val errorCode: String? = null,
    val notes: String? = null,
) {
    /** The section 9 format word this vector was filed under. */
    public val formatWord: String
        get() = vectorId.substringBefore("-v")

    /** Every file this vector references, relative to `test-vectors/v1`. */
    public fun fileReferences(): List<String> =
        (inputs.values + expected.values).mapNotNull { element ->
            ((element as? JsonObject)?.get("file") as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

    /**
     * Every string value that could be a section 2 byte value.
     *
     * A candidate is a non-empty string of hexadecimal digits in either case.
     * The check that follows is that it is lowercase and of even length, which
     * is what section 2 requires and what makes one byte string have one
     * representation.
     */
    public fun hexCandidates(): List<String> =
        (inputs.values + expected.values).mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.filter { candidate ->
            candidate.isNotEmpty() && candidate.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }
}

/** Whether a vector must be accepted or rejected. */
@Serializable
public enum class Outcome {
    @SerialName("accept")
    ACCEPT,

    @SerialName("reject")
    REJECT,
}

/**
 * Whether a string is a section 2 byte value: lowercase hexadecimal, even
 * length, no prefix and no separator. The empty string is a zero-length byte
 * value and is valid.
 */
public fun String.isCanonicalHex(): Boolean =
    length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' }
