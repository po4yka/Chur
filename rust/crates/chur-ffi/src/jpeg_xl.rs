//! Portable JPEG XL data plane. All input pointers are borrowed for the call.

#![expect(
    unsafe_code,
    reason = "the additive C ABI borrows validated pointers and writes caller-owned output"
)]

use crate::panic::{guard_status, guard_status_for};
use crate::registry::{self, Entry, Handle, Kind};
use chur_core::{ChurStatus, Error, Result};
use chur_media::jpeg_xl::Decoder;

/// Width, height, displayed keyframes, and repeat count, in that order.
#[repr(C)]
#[derive(Default)]
pub struct ChurJpegXlInfoV1 {
    /// Oriented width.
    pub width: u32,
    /// Oriented height.
    pub height: u32,
    /// Displayed keyframe count.
    pub frame_count: u32,
    /// Repeat count; zero means infinite.
    pub loop_count: u32,
}

fn invalid() -> Error {
    Error::new(
        ChurStatus::InvalidInput,
        "invalid JPEG XL boundary argument",
    )
}

unsafe fn publish(
    decoder: Decoder,
    out_handle: *mut Handle,
    out_info: *mut ChurJpegXlInfoV1,
) -> Result<()> {
    if out_handle.is_null() || out_info.is_null() {
        return Err(invalid());
    }
    let info = ChurJpegXlInfoV1 {
        width: decoder.width(),
        height: decoder.height(),
        frame_count: decoder.frame_count(),
        loop_count: decoder.loop_count(),
    };
    let handle = registry::insert(Entry::JpegXl(std::sync::Mutex::new(decoder)))?;
    // SAFETY: the caller guarantees writable output records for the call.
    unsafe {
        out_info.write(info);
        out_handle.write(handle);
    }
    Ok(())
}

/// Opens an encoded direct buffer. No input pointer is retained.
/// # Safety
/// Input covers `length` initialized bytes. Both output pointers are writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn chur_jpeg_xl_open(
    source: *const u8,
    length: usize,
    out_handle: *mut Handle,
    out_info: *mut ChurJpegXlInfoV1,
) -> i32 {
    guard_status(|| {
        if source.is_null() || out_handle.is_null() || out_info.is_null() {
            return Err(invalid());
        }
        if length > chur_media::jpeg_xl::MAX_ENCODED_BYTES {
            return Err(Error::new(
                ChurStatus::ResourceLimitExceeded,
                "JPEG XL source exceeds limit",
            ));
        }
        // SAFETY: source is non-null and the caller guarantees length readable bytes.
        let bytes = unsafe { core::slice::from_raw_parts(source, length) };
        let decoder = Decoder::open(bytes)?;
        // SAFETY: output contracts are stated above.
        unsafe { publish(decoder, out_handle, out_info) }
    })
}

/// Opens a borrowed source FD without changing its offset. Rust closes only its duplicate.
/// # Safety
/// Output pointers are writable records for the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn chur_jpeg_xl_open_fd(
    descriptor: i32,
    out_handle: *mut Handle,
    out_info: *mut ChurJpegXlInfoV1,
) -> i32 {
    guard_status(|| {
        if out_handle.is_null() || out_info.is_null() {
            return Err(invalid());
        }
        // SAFETY: duplication validates the borrowed descriptor; ownership stays with the caller.
        let source = unsafe { crate::api::duplicate_descriptor(descriptor)? };
        struct Positional {
            file: std::fs::File,
            offset: u64,
        }
        impl std::io::Read for Positional {
            fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
                use std::os::unix::fs::FileExt;
                let n = self.file.read_at(bytes, self.offset)?;
                self.offset += n as u64;
                Ok(n)
            }
        }
        let decoder = Decoder::read(Positional {
            file: source,
            offset: 0,
        })?;
        // SAFETY: the caller guarantees both output records.
        unsafe { publish(decoder, out_handle, out_info) }
    })
}

/// Renders RGBA8 sRGB straight alpha at dimensions derived from source info and max_edge.
/// # Safety
/// Destination covers capacity writable bytes; duration points to a writable u32.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn chur_jpeg_xl_render(
    handle: Handle,
    index: u32,
    max_edge: u32,
    destination: *mut u8,
    capacity: usize,
    out_duration_ms: *mut u32,
) -> i32 {
    guard_status_for(handle, || {
        if destination.is_null() || out_duration_ms.is_null() || capacity > 64 * 1024 * 1024 {
            return Err(invalid());
        }
        let entry = registry::get(handle, Kind::JpegXl)?;
        let Entry::JpegXl(decoder) = &*entry else {
            return Err(invalid());
        };
        let decoder = decoder
            .lock()
            .map_err(|_| Error::new(ChurStatus::InternalFailure, "JPEG XL decoder failed"))?;
        // SAFETY: the caller guarantees capacity writable bytes and no aliasing for this call.
        let output = unsafe { core::slice::from_raw_parts_mut(destination, capacity) };
        let duration = decoder.render(index, max_edge, output)?;
        // SAFETY: the caller guarantees a writable duration record.
        unsafe {
            out_duration_ms.write(duration);
        }
        Ok(())
    })
}

/// Closes a decoder. Repeated close is idempotent.
#[unsafe(no_mangle)]
pub extern "C" fn chur_jpeg_xl_close(handle: Handle) -> i32 {
    guard_status_for(handle, || match registry::get(handle, Kind::JpegXl) {
        Ok(_) => {
            registry::close(handle)?;
            Ok(())
        }
        Err(e) if e.status() == ChurStatus::SessionExpired => Ok(()),
        Err(e) => Err(e),
    })
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic)]
    use super::*;
    use std::io::{Seek, SeekFrom};
    use std::os::fd::AsRawFd;

    fn fixture() -> Vec<u8> {
        std::fs::read(
            std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../../../tests/media/jpeg-xl/still-codestream.jxl"),
        )
        .unwrap()
    }

    #[test]
    fn all_exports_validate_output_and_handle_lifecycle() {
        let bytes = fixture();
        let mut info = ChurJpegXlInfoV1::default();
        let mut handle = 0;
        // SAFETY: all non-null pointers below cover live caller-owned records.
        unsafe {
            assert_eq!(
                chur_jpeg_xl_open(core::ptr::null(), bytes.len(), &mut handle, &mut info),
                ChurStatus::InvalidInput.as_i32()
            );
            assert_eq!(
                chur_jpeg_xl_open(
                    bytes.as_ptr(),
                    bytes.len(),
                    core::ptr::null_mut(),
                    &mut info
                ),
                ChurStatus::InvalidInput.as_i32()
            );
            assert_eq!(
                chur_jpeg_xl_open(bytes.as_ptr(), bytes.len(), &mut handle, &mut info),
                0
            );
            assert_eq!((info.width, info.height, info.frame_count), (96, 48, 1));
            let mut pixels = vec![0; 96 * 48 * 4];
            let mut duration = 0;
            assert_eq!(
                chur_jpeg_xl_render(
                    handle,
                    0,
                    96,
                    pixels.as_mut_ptr(),
                    pixels.len(),
                    &mut duration
                ),
                0
            );
            assert_eq!(&pixels[..4], &[255, 0, 0, 255]);
            assert_eq!(
                chur_jpeg_xl_render(handle, 0, 96, pixels.as_mut_ptr(), 1, &mut duration),
                ChurStatus::InvalidInput.as_i32()
            );
            assert_eq!(chur_jpeg_xl_close(handle), 0);
            assert_eq!(chur_jpeg_xl_close(handle), 0);
            assert_eq!(
                chur_jpeg_xl_render(
                    handle,
                    0,
                    96,
                    pixels.as_mut_ptr(),
                    pixels.len(),
                    &mut duration
                ),
                ChurStatus::SessionExpired.as_i32()
            );
            assert_eq!(
                chur_jpeg_xl_close(u64::MAX),
                ChurStatus::InvalidInput.as_i32()
            );
        }
    }
    #[test]
    fn borrowed_fd_is_read_from_zero_without_changing_its_offset() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../tests/media/jpeg-xl/still-container.jxl");
        let mut source = std::fs::File::open(path).unwrap();
        source.seek(SeekFrom::Start(7)).unwrap();
        let mut handle = 0;
        let mut info = ChurJpegXlInfoV1::default();
        // SAFETY: source descriptor and both output locals live for the call.
        assert_eq!(
            unsafe { chur_jpeg_xl_open_fd(source.as_raw_fd(), &mut handle, &mut info) },
            0
        );
        assert_eq!(source.stream_position().unwrap(), 7);
        assert_eq!(chur_jpeg_xl_close(handle), 0);
    }
}
