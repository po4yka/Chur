//! JVM-to-C conversions.
//!
//! Every function here fails closed: a null argument, a short array, or a
//! non-direct buffer produces `CHUR_INVALID_INPUT` rather than a dereference.
//! `chur-ffi` validates its own arguments too, so these checks are the second
//! of two rather than the only one; the reason they exist is that a JVM
//! argument can be null in ways a C caller's cannot.

use chur_ffi::api::Status;
use jni::JNIEnv;
use jni::objects::{JByteArray, JByteBuffer, JIntArray, JLongArray, JString};
use jni::sys::{jint, jlong};

/// `CHUR_INVALID_INPUT` of `docs/ERROR_MODEL.md`.
pub const INVALID_INPUT: Status = 201;

/// `CHUR_INTERNAL_FAILURE`.
pub const INTERNAL_FAILURE: Status = 900;

/// Reads a Java string as UTF-8 bytes.
pub fn string_bytes(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Option<Vec<u8>> {
    if value.is_null() {
        return None;
    }
    env.get_string(value)
        .ok()
        .map(|text| String::from(text).into_bytes())
}

/// Copies a `byte[]` into a `Vec`.
///
/// A copy rather than a pin: these arrays are identifiers, secrets, names, and
/// search terms, all bounded well below a kilobyte by the catalog, and a pinned
/// array would hold the JVM heap still for the length of the call.
pub fn byte_array(env: &mut JNIEnv<'_>, value: &JByteArray<'_>) -> Option<Vec<u8>> {
    if value.is_null() {
        return None;
    }
    env.convert_byte_array(value).ok()
}

/// Copies a `byte[]` of exactly `length` bytes.
pub fn fixed_array(env: &mut JNIEnv<'_>, value: &JByteArray<'_>, length: usize) -> Option<Vec<u8>> {
    let bytes = byte_array(env, value)?;
    (bytes.len() == length).then_some(bytes)
}

/// Writes bytes back into a `byte[]`, which must be at least as long.
pub fn write_bytes(env: &mut JNIEnv<'_>, target: &JByteArray<'_>, bytes: &[u8]) -> bool {
    if target.is_null() {
        return false;
    }
    let Ok(length) = env.get_array_length(target) else {
        return false;
    };
    if (length as usize) < bytes.len() {
        return false;
    }
    let signed: Vec<i8> = bytes.iter().map(|byte| *byte as i8).collect();
    env.set_byte_array_region(target, 0, &signed).is_ok()
}

/// Writes one `long` into a `long[]`.
pub fn write_long(env: &mut JNIEnv<'_>, target: &JLongArray<'_>, at: usize, value: jlong) -> bool {
    write_longs(env, target, &[value], at)
}

/// Writes several `long` values into a `long[]` starting at `at`.
pub fn write_longs(
    env: &mut JNIEnv<'_>,
    target: &JLongArray<'_>,
    values: &[jlong],
    at: usize,
) -> bool {
    if target.is_null() {
        return false;
    }
    let Ok(length) = env.get_array_length(target) else {
        return false;
    };
    if (length as usize) < at + values.len() {
        return false;
    }
    let Ok(index) = jint::try_from(at) else {
        return false;
    };
    env.set_long_array_region(target, index, values).is_ok()
}

/// Writes several `int` values into an `int[]`.
pub fn write_ints(env: &mut JNIEnv<'_>, target: &JIntArray<'_>, values: &[jint]) -> bool {
    if target.is_null() {
        return false;
    }
    let Ok(length) = env.get_array_length(target) else {
        return false;
    };
    if (length as usize) < values.len() {
        return false;
    }
    env.set_int_array_region(target, 0, values).is_ok()
}

/// The address and capacity of a direct `ByteBuffer`.
///
/// `docs/interop/FFI_CONTRACT.md` §6 requires a caller-provided direct buffer
/// for the data plane, so a heap buffer is refused here rather than silently
/// copied: a copy would double the peak memory of a range read, which §12 of
/// the media pipeline bounds.
pub fn direct_buffer(env: &JNIEnv<'_>, buffer: &JByteBuffer<'_>) -> Option<(*mut u8, usize)> {
    if buffer.is_null() {
        return None;
    }
    let address = env.get_direct_buffer_address(buffer).ok()?;
    let capacity = env.get_direct_buffer_capacity(buffer).ok()?;
    Some((address, capacity))
}
