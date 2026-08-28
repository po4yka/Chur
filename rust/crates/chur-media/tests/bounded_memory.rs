//! What a reader holds while it reads a large object.
//!
//! `docs/ROADMAP.md` Phase 2 makes "multi-gigabyte objects remain bounded in
//! memory" an exit criterion, and `docs/assurance/PERFORMANCE_BUDGETS.md` §4
//! makes it a requirement rather than a measurement: memory must not scale with
//! object size.
//!
//! A test cannot import a multi-gigabyte object in a build job, and a peak-RSS
//! reading is not portable. It can measure the property directly instead. Every
//! path that touches a committed container — the import's own verification, the
//! range reader behind a player, the export, and the integrity scan — reads
//! through [`ReadAt`], so a source that records the largest single request
//! answers the question exactly: if no request ever exceeds one chunk record,
//! no amount of object length can enlarge the buffer.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use chur_core::{Id, Result};
use chur_crypto::{Key, Nonce};
use chur_format::constants::{MediaClass, StreamKind};
use chur_format::container::{
    CanonicalManifest, ContainerWriter, MediaProperties, ReadAt, StreamIdentity, StreamReader,
};
use chur_media::import;

/// A source that answers from memory and records the largest request made of it.
struct Metered {
    bytes: Vec<u8>,
    largest_request: usize,
    total_requested: u64,
}

impl ReadAt for Metered {
    fn length(&self) -> u64 {
        self.bytes.len() as u64
    }

    fn read_at(&mut self, offset: u64, buffer: &mut [u8]) -> Result<()> {
        self.largest_request = self.largest_request.max(buffer.len());
        self.total_requested += buffer.len() as u64;
        let start = usize::try_from(offset).unwrap();
        let end = start
            .checked_add(buffer.len())
            .filter(|end| *end <= self.bytes.len())
            .ok_or_else(|| chur_core::err!(ObjectIncomplete, "past the end"))?;
        buffer.copy_from_slice(&self.bytes[start..end]);
        Ok(())
    }
}

const KEY: Key = Key::new([0x51; 32]);
const CHUNK: u32 = 65_536;

fn identity() -> StreamIdentity {
    StreamIdentity {
        object_id: Id::new([0x11; 16]).unwrap(),
        stream_id: Id::new([0x22; 16]).unwrap(),
        stream_kind: StreamKind::Original,
        stream_revision: 1,
    }
}

/// Builds a container of `chunks` full chunks in memory.
fn container(chunks: u64) -> Metered {
    let manifest = CanonicalManifest::new(
        identity(),
        None,
        CHUNK,
        [0x33; 16],
        1,
        MediaProperties::opaque(),
    )
    .unwrap();
    let mut writer = ContainerWriter::start(Vec::new(), &KEY, manifest, Nonce::new([0x44; 24]))
        .expect("start the container");
    for index in 0..chunks {
        let piece: Vec<u8> = (0..CHUNK)
            .map(|byte| ((byte as u64 + index) % 251) as u8)
            .collect();
        writer.write_chunk(&piece).expect("write a chunk");
    }
    let bytes = writer
        .finish(Nonce::new([0x45; 24]), 1)
        .expect("finish the container");
    Metered {
        bytes,
        largest_request: 0,
        total_requested: 0,
    }
}

/// One chunk record: the header of §8, the ciphertext, and the AEAD tag.
fn chunk_record_len() -> usize {
    chur_core::limits::container::CHUNK_HEADER_LEN
        + usize::try_from(CHUNK).unwrap()
        + chur_core::limits::TAG_LEN
}

#[test]
fn a_complete_verification_never_asks_for_more_than_one_chunk_record() {
    let mut source = container(64);
    let file_length = source.length();
    let mut reader = StreamReader::open(&mut source, &KEY, &identity()).expect("open");
    let verified = reader.verify_complete().expect("verify");
    drop(reader);

    assert_eq!(verified, u64::from(CHUNK) * 64);
    assert!(
        source.largest_request <= chunk_record_len(),
        "a verification asked for {} bytes at once, more than the {} of one chunk record",
        source.largest_request,
        chunk_record_len()
    );
    // The whole file is read exactly once, which is what a complete
    // verification means; the point is that it is read a record at a time.
    assert!(source.total_requested >= file_length / 2);
}

#[test]
fn peak_read_size_does_not_grow_with_object_length() {
    let mut small = container(8);
    let mut reader = StreamReader::open(&mut small, &KEY, &identity()).expect("open");
    reader.verify_complete().expect("verify");
    drop(reader);

    let mut large = container(512);
    let mut reader = StreamReader::open(&mut large, &KEY, &identity()).expect("open");
    reader.verify_complete().expect("verify");
    drop(reader);

    assert_eq!(
        small.largest_request, large.largest_request,
        "a container 64 times longer changed the largest single read"
    );
    assert!(large.bytes.len() > small.bytes.len() * 32);
}

#[test]
fn a_random_seek_reads_one_chunk_record_whatever_the_offset() {
    let mut source = container(256);
    let mut reader = StreamReader::open(&mut source, &KEY, &identity()).expect("open");
    // An LCG walks the object so the offsets are spread rather than adjacent.
    let mut offset: u64 = 1;
    for _ in 0..64 {
        offset = offset
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        let at = offset % (u64::from(CHUNK) * 256);
        let read = reader.read_range(at, 1).expect("read one byte");
        assert_eq!(read.len(), 1);
    }
    drop(reader);

    assert!(
        source.largest_request <= chunk_record_len(),
        "a one-byte read asked for {} bytes",
        source.largest_request
    );
}

#[test]
fn video_takes_the_chunk_size_that_makes_the_object_bound_reachable() {
    // `chur_core::limits::container` caps a container at CHUNK_COUNT_MAX
    // records, and that count times the video chunk size is exactly
    // TOTAL_PLAINTEXT_MAX. A video written at the photo size would stop at a
    // quarter of the bound the specification states.
    assert_eq!(import::chunk_size_for(MediaClass::Video), 1_048_576);
    assert_eq!(import::chunk_size_for(MediaClass::Audio), 1_048_576);
    assert_eq!(import::chunk_size_for(MediaClass::Image), 262_144);
    assert_eq!(import::chunk_size_for(MediaClass::Opaque), 262_144);

    let reachable = chur_core::limits::container::CHUNK_COUNT_MAX
        * u64::from(import::chunk_size_for(MediaClass::Video));
    assert_eq!(reachable, chur_core::limits::container::TOTAL_PLAINTEXT_MAX);
}
