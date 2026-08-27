//! The Android JNI adapter, ADR-0040.
//!
//! `docs/interop/FFI_CONTRACT.md` §14 has Android reach the native library
//! through a JNI adapter, and §6.2 forbids a `Java_*` symbol in the Chur
//! artifact, so the adapter is a second shared library. iOS does not load it:
//! Kotlin/Native reaches `chur.h` through cinterop directly.
//!
//! Every function here does three things and no more: read the JVM arguments,
//! call one `chur_*` export, and write the result back. There is no logic to
//! test, because the behaviour is `chur-ffi`'s and is tested there. What this
//! crate owes is the conversions, and the helpers below are where they live.
//!
//! Two rules are structural rather than conventional. A large payload crosses
//! as a direct `ByteBuffer`, because §6 forbids a whole-file `ByteArray`; a
//! small one crosses as a `byte[]`, because a 16-byte identifier in a direct
//! buffer is more ceremony than copy. And every function returns the `int32_t`
//! status the export returned, never a message, so the redaction of
//! `docs/ERROR_MODEL.md` survives the extra boundary.

mod convert;
mod exports;

pub use exports::*;
