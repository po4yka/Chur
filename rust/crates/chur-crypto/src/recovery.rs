//! The human encoding of a recovery secret.
//!
//! [ADR-0029] freezes the representation: 24 BIP-39 English words carrying 256
//! bits of entropy plus BIP-39's 8-bit SHA-256 checksum, eleven bits per word
//! against the 2048-word English list. The words are a presentation encoding.
//! The canonical secret underneath is 32 bytes and is what
//! `docs/format/KEY_SLOT_BODIES_V1.md` §4 wraps a root under; no part of the
//! phrase reaches a slot.
//!
//! [ADR-0029]: https://github.com/po4yka/Chur/blob/main/docs/adr/0029-freeze-the-recovery-secret-encoding.md

use sha2::{Digest, Sha256};
use unicode_normalization::UnicodeNormalization;
use zeroize::Zeroizing;

use chur_core::limits::KEY_LEN;
use chur_core::status::ChurStatus;
use chur_core::{Error, Result, ensure};

use crate::secret::Key;

/// The ASCII marker printed above the numbered words.
pub const PRINT_MARKER: &str = "chur-recovery-v1";

/// The QR payload prefix, followed by the 24 space-separated words.
pub const QR_PREFIX: &str = "chur-recovery-v1:";

/// Words in a v1 recovery phrase.
pub const WORD_COUNT: usize = 24;

/// Bits each word carries.
const BITS_PER_WORD: usize = 11;

/// Checksum bits appended to the 256 entropy bits.
const CHECKSUM_BITS: usize = 8;

/// The vendored BIP-39 English wordlist.
const WORDLIST_SOURCE: &str = include_str!("../data/bip39-english.txt");

/// The 2048 words, in BIP-39 order.
fn wordlist() -> &'static [&'static str] {
    use std::sync::OnceLock;
    static LIST: OnceLock<Vec<&'static str>> = OnceLock::new();
    LIST.get_or_init(|| {
        WORDLIST_SOURCE
            .lines()
            .filter(|line| !line.is_empty())
            .collect()
    })
}

/// Encodes a 32-byte recovery secret as 24 BIP-39 English words.
///
/// The words themselves are static list entries; what carries the secret is
/// their order. Use [`to_phrase`] where the rendered text must be cleared after
/// display, which is what a recovery flow does.
#[must_use]
pub fn encode(secret: &Key) -> Vec<&'static str> {
    let entropy = secret.expose();
    let checksum = Sha256::digest(entropy)[0];

    let mut words = Vec::with_capacity(WORD_COUNT);
    for index in 0..WORD_COUNT {
        let mut value = 0usize;
        for offset in 0..BITS_PER_WORD {
            let bit_position = index * BITS_PER_WORD + offset;
            let bit = if bit_position < KEY_LEN * 8 {
                (entropy[bit_position / 8] >> (7 - bit_position % 8)) & 1
            } else {
                (checksum >> (7 - (bit_position - KEY_LEN * 8))) & 1
            };
            value = (value << 1) | usize::from(bit);
        }
        words.push(wordlist()[value]);
    }
    words
}

/// The 24 words joined by single spaces, zeroized when dropped.
#[must_use]
pub fn to_phrase(secret: &Key) -> Zeroizing<String> {
    Zeroizing::new(encode(secret).join(" "))
}

/// The printable phrase: the marker, then the 24 words separated by spaces.
#[must_use]
pub fn to_qr_payload(secret: &Key) -> Zeroizing<String> {
    Zeroizing::new(format!("{QR_PREFIX}{}", to_phrase(secret).as_str()))
}

/// Normalizes re-entered text before matching.
///
/// NFKD, lowercase, whitespace runs collapsed to one space, then trimmed. The
/// order is the one ADR-0029 fixes: normalizing after lowercasing would leave a
/// compatibility character uppercased.
#[must_use]
pub fn normalize(input: &str) -> String {
    let decomposed: String = input.nfkd().collect();
    decomposed
        .to_lowercase()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

/// Decodes a re-entered phrase back to the 32-byte secret.
///
/// Each word is matched by its first four characters, which are unique across
/// the English list, so a phrase written with the four-letter abbreviations
/// decodes to the same secret as the full words.
///
/// # Errors
///
/// Returns [`ChurStatus::InvalidInput`] for a phrase that is not 24 words, an
/// unmatched word, or a failed checksum. No slot unwrap is attempted for any of
/// them: only a checksum-valid phrase that then fails to unwrap returns
/// `AUTHENTICATION_FAILED`, and that failure belongs to the slot rather than to
/// this module.
pub fn decode(phrase: &str) -> Result<Key> {
    let normalized = normalize(phrase);
    let normalized = normalized
        .strip_prefix(QR_PREFIX)
        .unwrap_or(&normalized)
        .trim();
    let words: Vec<&str> = normalized
        .split(' ')
        .filter(|word| !word.is_empty())
        .collect();
    ensure!(
        words.len() == WORD_COUNT,
        InvalidInput,
        "a v1 recovery phrase is exactly 24 words"
    );

    let mut bits = Zeroizing::new(Vec::<u8>::with_capacity(WORD_COUNT * BITS_PER_WORD));
    for word in &words {
        let index = index_of(word).ok_or_else(|| {
            Error::new(
                ChurStatus::InvalidInput,
                "a word is not in the BIP-39 English list",
            )
        })?;
        for offset in (0..BITS_PER_WORD).rev() {
            bits.push(((index >> offset) & 1) as u8);
        }
    }

    let mut secret = Key::zeroed();
    for (position, bit) in bits.iter().take(KEY_LEN * 8).enumerate() {
        secret.expose_mut()[position / 8] |= bit << (7 - position % 8);
    }
    let mut declared = 0u8;
    for (offset, bit) in bits
        .iter()
        .skip(KEY_LEN * 8)
        .take(CHECKSUM_BITS)
        .enumerate()
    {
        declared |= bit << (7 - offset);
    }
    let expected = Sha256::digest(secret.expose())[0];
    ensure!(
        declared == expected,
        InvalidInput,
        "recovery phrase checksum does not match its words"
    );
    Ok(secret)
}

/// The list index of a word, matched by its first four characters.
///
/// ADR-0029 fixes the rule as "match each word by its first four characters,
/// which are unique in the English list". A word shorter than four characters
/// is its own prefix, and the entries shorter than four characters are matched
/// whole by the same comparison, so one rule covers every length.
fn index_of(word: &str) -> Option<usize> {
    let prefix: &str = &word[..4.min(word.len())];
    wordlist()
        .iter()
        .position(|entry| &entry[..4.min(entry.len())] == prefix)
}

const _: () = assert!(KEY_LEN * 8 + CHECKSUM_BITS == WORD_COUNT * BITS_PER_WORD);

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic, clippy::expect_used)]

    use super::*;

    /// `Key` implements no `Debug` by design (SEC-010), so `unwrap_err` is not
    /// available on a `Result<Key>`. This is the rejection assertion that works
    /// without one.
    fn rejection(outcome: Result<Key>) -> ChurStatus {
        match outcome {
            Err(error) => error.status(),
            Ok(_) => panic!("a phrase that must be rejected decoded"),
        }
    }

    #[test]
    fn the_vendored_wordlist_is_the_published_one() {
        let digest = Sha256::digest(WORDLIST_SOURCE.as_bytes());
        assert_eq!(
            format!("{digest:x}"),
            "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            "the vendored BIP-39 English list is not the published one"
        );
        let list = wordlist();
        assert_eq!(list.len(), 2048);
        assert!(
            list.windows(2).all(|pair| pair[0] < pair[1]),
            "list is not sorted"
        );
        let prefixes: std::collections::BTreeSet<&str> =
            list.iter().map(|word| &word[..4.min(word.len())]).collect();
        assert_eq!(
            prefixes.len(),
            2048,
            "four-character prefixes are not unique"
        );
    }

    #[test]
    fn the_all_zero_secret_encodes_to_the_bip39_reference_phrase() {
        // BIP-39's own 256-bit all-zero test vector.
        let words = encode(&Key::new([0u8; KEY_LEN]));
        assert_eq!(words.len(), 24);
        assert!(words[..23].iter().all(|word| *word == "abandon"));
        assert_eq!(words[23], "art");
    }

    #[test]
    fn the_all_ones_secret_encodes_to_the_bip39_reference_phrase() {
        let words = encode(&Key::new([0xffu8; KEY_LEN]));
        assert!(words[..23].iter().all(|word| *word == "zoo"));
        assert_eq!(words[23], "vote");
    }

    #[test]
    fn a_secret_round_trips_through_the_phrase() {
        for byte in [0x00u8, 0x01, 0x7f, 0x80, 0xfe, 0xff] {
            let secret = Key::new([byte; KEY_LEN]);
            let phrase = encode(&secret).join(" ");
            assert_eq!(decode(&phrase).unwrap().expose(), secret.expose());
        }
        let mut mixed = [0u8; KEY_LEN];
        for (index, slot) in mixed.iter_mut().enumerate() {
            *slot = (index * 7 + 3) as u8;
        }
        let secret = Key::new(mixed);
        assert_eq!(
            decode(&encode(&secret).join(" ")).unwrap().expose(),
            secret.expose()
        );
    }

    #[test]
    fn a_denormalized_re_entry_normalizes_to_the_same_words() {
        let secret = Key::new([0x5a; KEY_LEN]);
        let canonical = encode(&secret).join(" ");
        // Uppercase, a fullwidth letter, a non-breaking space, doubled spaces,
        // and surrounding whitespace all normalize away.
        let messy = format!("  {}  ", canonical.to_uppercase().replace(' ', "\u{00a0} "));
        assert_eq!(normalize(&messy), canonical);
        assert_eq!(decode(&messy).unwrap().expose(), secret.expose());

        let fullwidth = canonical.replacen('a', "\u{ff41}", 1);
        assert_eq!(decode(&fullwidth).unwrap().expose(), secret.expose());
    }

    #[test]
    fn the_qr_payload_carries_the_prefix_and_decodes() {
        let secret = Key::new([0x31; KEY_LEN]);
        let payload = to_qr_payload(&secret);
        assert!(payload.starts_with(QR_PREFIX));
        assert_eq!(payload.split(' ').count(), 24);
        assert_eq!(decode(&payload).unwrap().expose(), secret.expose());
    }

    #[test]
    fn a_four_character_abbreviation_matches_the_full_word() {
        let secret = Key::new([0x27; KEY_LEN]);
        let abbreviated: Vec<String> = encode(&secret)
            .iter()
            .map(|word| word.chars().take(4).collect())
            .collect();
        assert_eq!(
            decode(&abbreviated.join(" ")).unwrap().expose(),
            secret.expose()
        );
    }

    #[test]
    fn a_three_letter_word_matches_itself() {
        // "act" and "action" share three characters; the four-character rule
        // separates them, and a three-letter entry is matched whole.
        assert_eq!(wordlist()[index_of("act").unwrap()], "act");
        assert_eq!(wordlist()[index_of("acti").unwrap()], "action");
        assert_eq!(wordlist()[index_of("action").unwrap()], "action");
        assert!(index_of("zzzz").is_none());
    }

    #[test]
    fn a_wrong_word_count_is_refused() {
        let secret = Key::new([0x11; KEY_LEN]);
        let words = encode(&secret);
        for count in [0usize, 1, 12, 23, 25] {
            let phrase = words
                .iter()
                .cycle()
                .take(count)
                .copied()
                .collect::<Vec<_>>()
                .join(" ");
            assert_eq!(
                rejection(decode(&phrase)),
                ChurStatus::InvalidInput,
                "word count {count}"
            );
        }
    }

    #[test]
    fn an_unmatched_word_is_refused_before_any_checksum_work() {
        let secret = Key::new([0x11; KEY_LEN]);
        let mut words = encode(&secret);
        words[5] = "churchur";
        assert_eq!(
            rejection(decode(&words.join(" "))),
            ChurStatus::InvalidInput
        );
    }

    #[test]
    fn a_failed_checksum_is_refused() {
        let secret = Key::new([0x11; KEY_LEN]);
        let mut words = encode(&secret);
        // Replace the last word, which carries the checksum bits, with another
        // list entry, and assert the phrase is rejected rather than decoded.
        let replacement = wordlist()
            .iter()
            .find(|entry| **entry != words[23])
            .copied()
            .unwrap();
        words[23] = replacement;
        assert_eq!(
            rejection(decode(&words.join(" "))),
            ChurStatus::InvalidInput
        );
    }

    #[test]
    fn every_single_word_substitution_is_caught_or_decodes_to_another_secret() {
        let secret = Key::new([0x42; KEY_LEN]);
        let canonical = encode(&secret);
        for position in 0..WORD_COUNT {
            let mut words = canonical.clone();
            words[position] = wordlist()[(index_of(canonical[position]).unwrap() + 1) % 2048];
            match decode(&words.join(" ")) {
                Err(error) => assert_eq!(error.status(), ChurStatus::InvalidInput),
                Ok(other) => assert_ne!(
                    other.expose(),
                    secret.expose(),
                    "a changed word at {position} decoded to the same secret"
                ),
            }
        }
    }
}
