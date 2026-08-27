package dev.po4yka.chur.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kotlin side of the vector suite.
 *
 * `docs/format/TEST_VECTORS.md` section 7 requires the same set to run in Rust,
 * the CLI, Android, and iOS. These tests run on every Kotlin target against the
 * bytes the build embedded from `test-vectors/v1`, so an Android unit test, an
 * iOS test, and `cargo test` are reading one set rather than three copies.
 *
 * They check the index, not the cryptography. A Kotlin implementation that
 * decoded a private record would be the alternate canonical encoder section 13
 * of the encoding profile forbids.
 */
class VectorSuiteTest {
    private val manifest = VectorManifest.parse(VectorFixtures.MANIFEST_JSON)

    @Test
    fun the_manifest_parses_with_no_unknown_field() {
        // The reader rejects an unknown key, so parsing at all is the assertion
        // that the layout has not drifted.
        assertEquals(1, manifest.manifestVersion)
        assertTrue(manifest.vectors.isNotEmpty())
    }

    @Test
    fun the_provenance_fields_are_present_and_well_formed() {
        assertEquals(40, manifest.specCommit.length)
        assertTrue(manifest.specCommit.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals("chur-cli", manifest.generator.name)
        assertTrue(manifest.generator.commit.isNotEmpty())
        assertTrue(manifest.generator.toolchain.isNotEmpty())
    }

    @Test
    fun vector_identifiers_are_unique_and_sorted() {
        val ids = manifest.vectors.map { it.vectorId }
        assertEquals(ids.size, ids.toSet().size, "a vector identifier is used twice")
        assertEquals(ids.sorted(), ids, "vectors are not sorted by vector_id")
    }

    @Test
    fun every_identifier_matches_the_grammar() {
        for (entry in manifest.vectors) {
            val id = entry.vectorId
            assertTrue(
                id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' },
                "$id is not lowercase ASCII words joined by '-'",
            )
            val version = id.substringAfter("-v").substringBefore('-')
            assertTrue(version.isNotEmpty() && version.all { it.isDigit() }, "$id has no numeric version")
            assertTrue(entry.formatWord.isNotEmpty(), "$id has no format word")
            assertTrue(
                id.substringAfter("-v$version-").isNotEmpty(),
                "$id has no case",
            )
        }
    }

    @Test
    fun every_format_word_is_allocated() {
        val allocated = setOf(
            "canonical-encoding",
            "key-derivation",
            "password-slot",
            "recovery-slot",
            "keystore-slot",
            "keychain-slot",
            "vault-descriptor",
            "collection-envelope",
            "object-key-envelope",
            "object",
            "backup",
            "operation",
            "collection-grant",
        )
        for (entry in manifest.vectors) {
            assertTrue(
                entry.formatWord in allocated,
                "${entry.vectorId} names the unallocated format word ${entry.formatWord}",
            )
        }
    }

    @Test
    fun an_accept_vector_expects_and_a_reject_vector_names_a_code() {
        for (entry in manifest.vectors) {
            when (entry.outcome) {
                Outcome.ACCEPT -> {
                    assertTrue(entry.expected.isNotEmpty(), "${entry.vectorId} accepts but expects nothing")
                    assertNull(entry.errorCode, "${entry.vectorId} accepts but names an error code")
                }
                Outcome.REJECT -> {
                    assertNotNull(entry.errorCode, "${entry.vectorId} rejects but names no error code")
                    assertTrue(entry.expected.isEmpty(), "${entry.vectorId} rejects but carries expectations")
                }
            }
        }
    }

    @Test
    fun every_named_error_code_is_a_registered_status() {
        val names = ChurStatus.entries.map { it.name }.toSet()
        for (entry in manifest.vectors) {
            val code = entry.errorCode ?: continue
            assertTrue(code in names, "${entry.vectorId} names an unregistered error code $code")
        }
    }

    @Test
    fun every_byte_value_is_lowercase_hexadecimal_of_even_length() {
        for (entry in manifest.vectors) {
            for (candidate in entry.hexCandidates()) {
                assertTrue(
                    candidate.isCanonicalHex(),
                    "${entry.vectorId} carries a byte value that is not lowercase hexadecimal " +
                        "of even length",
                )
            }
        }
    }

    @Test
    fun every_file_reference_resolves_to_a_fixture_the_build_embedded() {
        var referenced = 0
        for (entry in manifest.vectors) {
            for (path in entry.fileReferences()) {
                assertTrue(
                    path in VectorFixtures.FIXTURE_DIGESTS,
                    "${entry.vectorId} references a missing fixture $path",
                )
                referenced++
            }
        }
        assertEquals(
            VectorFixtures.FIXTURE_DIGESTS.size,
            referenced,
            "a fixture file is referenced by no entry",
        )
    }

    @Test
    fun every_hkdf_label_and_every_key_slot_family_has_a_vector() {
        // TEST_VECTORS.md section 4 requires both. The counts are what the
        // registries hold: 25 labels, 4 slot families.
        val derivations = manifest.vectors.count { it.formatWord == "key-derivation" }
        assertEquals(25, derivations, "the label registry has 25 entries")
        for (family in listOf("password-slot", "recovery-slot", "keystore-slot", "keychain-slot")) {
            assertTrue(
                manifest.vectors.any { it.formatWord == family },
                "no vector for the $family family",
            )
        }
    }

    @Test
    fun the_set_carries_both_outcomes_for_the_frozen_formats() {
        for (format in listOf("canonical-encoding", "object", "vault-descriptor", "object-key-envelope")) {
            val group = manifest.vectors.filter { it.formatWord == format }
            assertTrue(group.any { it.outcome == Outcome.ACCEPT }, "$format has no accepted vector")
            assertTrue(group.any { it.outcome == Outcome.REJECT }, "$format has no rejected vector")
        }
    }

    @Test
    fun every_vector_names_a_specification_and_a_section() {
        for (entry in manifest.vectors) {
            assertTrue(entry.spec.startsWith("docs/"), "${entry.vectorId} names ${entry.spec}")
            assertTrue(entry.spec.endsWith(".md"), "${entry.vectorId} names ${entry.spec}")
            assertTrue(entry.specSection.isNotEmpty(), "${entry.vectorId} names no section")
            assertTrue(entry.purpose.isNotEmpty(), "${entry.vectorId} has no purpose")
        }
    }
}
