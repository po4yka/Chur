//! Authenticated range reads over a container the target builds itself.
//!
//! A raw-byte fuzzer cannot produce a container that opens, so this target is
//! the structure-aware half of `FUZZING.md` §4: it encodes a valid container
//! from arbitrary plaintext, then asks for arbitrary ranges and arbitrary
//! damage.

#![no_main]

use libfuzzer_sys::fuzz_target;

use chur_core::Id;
use chur_crypto::aead::Nonce;
use chur_crypto::secret::Key;
use chur_format::constants::{MediaClass, StreamKind};
use chur_format::container::{
    CanonicalManifest, ContainerReader, MediaProperties, StreamIdentity, encode_container,
};

const CHUNK_SIZE: u32 = 65_536;

#[derive(arbitrary::Arbitrary, Debug)]
struct Input {
    plaintext: Vec<u8>,
    offset: u64,
    length: u64,
    damage_at: Option<u32>,
}

fuzz_target!(|input: Input| {
    if input.plaintext.len() > 3 * CHUNK_SIZE as usize {
        return;
    }
    let Ok(object_id) = Id::new([0x33; 16]) else {
        return;
    };
    let Ok(stream_id) = Id::new([0x34; 16]) else {
        return;
    };
    let identity = StreamIdentity {
        object_id,
        stream_id,
        stream_kind: StreamKind::Original,
        stream_revision: 1,
    };
    let Ok(properties) = MediaProperties::new(MediaClass::Opaque, 0, 0, 0) else {
        return;
    };
    let Ok(manifest) = CanonicalManifest::new(identity, None, CHUNK_SIZE, [0x35; 16], 1, properties)
    else {
        return;
    };
    let object_key = Key::new([0x77; 32]);
    let Ok(mut container) = encode_container(
        &object_key,
        manifest,
        Nonce::new([0x36; 24]),
        &input.plaintext,
        Nonce::new([0x37; 24]),
        1,
    ) else {
        return;
    };

    if let Some(at) = input.damage_at {
        let index = (at as usize) % container.len();
        container[index] ^= 0x01;
        // A damaged container either fails to open, or opens and then fails to
        // verify. What it must never do is return plaintext that differs from
        // the source without an error.
        if let Ok(reader) = ContainerReader::open(&container, &object_key, &identity) {
            let _ = reader.verify_complete();
            let _ = reader.read_range(input.offset, input.length);
        }
        return;
    }

    let Ok(reader) = ContainerReader::open(&container, &object_key, &identity) else {
        panic!("an undamaged container did not open");
    };
    let verified = reader.verify_complete().unwrap_or_else(|error| {
        panic!("an undamaged container did not verify: {error}");
    });
    assert_eq!(verified, input.plaintext.len() as u64);

    let total = input.plaintext.len() as u64;
    let offset = if total == 0 { 0 } else { input.offset % (total + 1) };
    let length = if total == 0 {
        0
    } else {
        input.length % (total - offset + 1)
    };
    let read = reader
        .read_range(offset, length)
        .unwrap_or_else(|error| panic!("an in-bounds range failed: {error}"));
    let from = offset as usize;
    let to = from + length as usize;
    assert_eq!(read.as_slice(), &input.plaintext[from..to]);
});
