//! The object store: containers on disk.
//!
//! `docs/format/OBJECT_CONTAINER_V1.md` §14 fixes the transaction. This module
//! owns the filesystem half of it: the temporary namespace, the fsync points,
//! the timestamp normalization, and the atomic rename into the committed
//! namespace. The catalog half is the journal, and the two share one ordering.

use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use chur_catalog::paths::VaultRoot;
use chur_core::{Id, Result, err};

/// A container being written into the temporary namespace.
pub struct TemporaryContainer {
    path: PathBuf,
    file: File,
}

impl TemporaryContainer {
    /// Creates or reopens the temporary container of one import transaction.
    ///
    /// Reopening is the resume path of §14.3: the file already holds every
    /// record below the journaled length and the writer appends from there.
    pub fn open(root_dir: &VaultRoot, store: &Id, temp_path_id: &Id) -> Result<Self> {
        let path = root_dir.temporary_container(store, temp_path_id);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|_| err!(IoFailure, "the incoming directory could not be created"))?;
        }
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(&path)
            .map_err(|_| err!(IoFailure, "the temporary container could not be opened"))?;
        Ok(Self { path, file })
    }

    /// Appends bytes at the current position.
    pub fn write(&mut self, bytes: &[u8]) -> Result<()> {
        self.file
            .write_all(bytes)
            .map_err(|_| err!(IoFailure, "the container could not be written"))
    }

    /// Makes every written byte durable, §14.2 step 4.
    pub fn sync(&mut self) -> Result<()> {
        self.file
            .sync_all()
            .map_err(|_| err!(IoFailure, "the container could not be made durable"))
    }

    /// Truncates to `length` and positions the writer there.
    ///
    /// §14.3 truncates to the end of the last authentic record before writing
    /// the next one, which is what discards a partial record a crash left.
    pub fn truncate_to(&mut self, length: u64) -> Result<()> {
        self.file
            .set_len(length)
            .map_err(|_| err!(IoFailure, "the container could not be truncated"))?;
        self.file
            .seek(SeekFrom::Start(length))
            .map_err(|_| err!(IoFailure, "the container could not be positioned"))?;
        Ok(())
    }

    /// The current byte length.
    pub fn length(&self) -> Result<u64> {
        self.file
            .metadata()
            .map(|metadata| metadata.len())
            .map_err(|_| err!(IoFailure, "the container length could not be read"))
    }

    /// Reads `length` bytes at `offset`, for the resume check of §14.3.
    pub fn read_at(&mut self, offset: u64, length: usize) -> Result<Vec<u8>> {
        self.file
            .seek(SeekFrom::Start(offset))
            .map_err(|_| err!(IoFailure, "the container could not be positioned"))?;
        let mut buffer = vec![0u8; length];
        self.file
            .read_exact(&mut buffer)
            .map_err(|_| err!(ObjectIncomplete, "the reserved record is absent or short"))?;
        self.file
            .seek(SeekFrom::End(0))
            .map_err(|_| err!(IoFailure, "the container could not be positioned"))?;
        Ok(buffer)
    }

    /// Commits the container into the committed namespace, §14.
    ///
    /// The access and modification times are set to the Unix epoch before the
    /// rename, so a listing of the object store discloses neither when each
    /// object was imported nor in what order. It is a store rule rather than a
    /// container byte: no reader depends on the value, and a value a backup or
    /// restore tool resets is not an integrity failure.
    pub fn commit(mut self, root_dir: &VaultRoot, store: &Id, container: &Id) -> Result<()> {
        self.sync()?;
        let destination = root_dir.container(store, container);
        if let Some(parent) = destination.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|_| err!(IoFailure, "the object directory could not be created"))?;
        }
        normalize_times(&self.path)?;
        drop(self.file);
        std::fs::rename(&self.path, &destination)
            .map_err(|_| err!(IoFailure, "the container could not be committed"))?;
        sync_directory(destination.parent())?;
        Ok(())
    }

    /// Deletes the temporary container, §14.4 step 2.
    pub fn discard(self) -> Result<()> {
        drop(self.file);
        match std::fs::remove_file(&self.path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(_) => Err(err!(
                IoFailure,
                "the temporary container could not be deleted"
            )),
        }
    }
}

/// Reads a committed container whole.
///
/// `docs/format/OBJECT_CONTAINER_V1.md` §16 caps a container at 1 TiB, so this
/// is for the vector suite, the CLI, and the tests. The reader of
/// [`crate::reader`] is what an application uses, and it reads one chunk at a
/// time.
pub fn read_container(root_dir: &VaultRoot, store: &Id, container: &Id) -> Result<Vec<u8>> {
    std::fs::read(root_dir.container(store, container))
        .map_err(|_| err!(NotFound, "the container is absent"))
}

/// Whether a committed container exists.
#[must_use]
pub fn container_exists(root_dir: &VaultRoot, store: &Id, container: &Id) -> bool {
    root_dir.container(store, container).exists()
}

/// Whether a temporary container exists, which §14.4 uses to find dead
/// transactions.
#[must_use]
pub fn temporary_exists(root_dir: &VaultRoot, store: &Id, temp_path_id: &Id) -> bool {
    root_dir.temporary_container(store, temp_path_id).exists()
}

/// Unlinks a committed container, §14.1 steps 3 and 4.
pub fn unlink_container(root_dir: &VaultRoot, store: &Id, container: &Id) -> Result<()> {
    match std::fs::remove_file(root_dir.container(store, container)) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(_) => Err(err!(IoFailure, "the container could not be unlinked")),
    }
}

/// A random-access handle on a committed container.
pub struct ContainerFile {
    file: File,
    length: u64,
}

impl ContainerFile {
    /// Opens a committed container for reading.
    pub fn open(root_dir: &VaultRoot, store: &Id, container: &Id) -> Result<Self> {
        let path = root_dir.container(store, container);
        let file = File::open(&path).map_err(|_| err!(NotFound, "the container is absent"))?;
        let length = file
            .metadata()
            .map(|metadata| metadata.len())
            .map_err(|_| err!(IoFailure, "the container length could not be read"))?;
        Ok(Self { file, length })
    }

    /// The file length in bytes.
    #[must_use]
    pub const fn length(&self) -> u64 {
        self.length
    }

    /// Reads exactly `length` bytes at `offset`.
    pub fn read_at(&mut self, offset: u64, length: usize) -> Result<Vec<u8>> {
        self.file
            .seek(SeekFrom::Start(offset))
            .map_err(|_| err!(IoFailure, "the container could not be positioned"))?;
        let mut buffer = vec![0u8; length];
        self.file
            .read_exact(&mut buffer)
            .map_err(|_| err!(ObjectIncomplete, "the container record is absent or short"))?;
        Ok(buffer)
    }
}

/// Sets a path's access and modification times to the Unix epoch.
///
/// `std::fs` cannot set a time on stable Rust without a platform crate, and
/// `docs/DEPENDENCY_POLICY.md` prefers no dependency for what a few lines can
/// do. `File::set_times` has been stable since 1.75 and takes both times, which
/// is exactly what §14 asks for.
fn normalize_times(path: &Path) -> Result<()> {
    let file = OpenOptions::new()
        .write(true)
        .open(path)
        .map_err(|_| err!(IoFailure, "the container could not be reopened"))?;
    let epoch = std::fs::FileTimes::new()
        .set_accessed(std::time::UNIX_EPOCH)
        .set_modified(std::time::UNIX_EPOCH);
    file.set_times(epoch).map_err(|_| {
        err!(
            IoFailure,
            "the container timestamps could not be normalized"
        )
    })?;
    Ok(())
}

/// Makes a rename durable, where the platform supports it.
fn sync_directory(path: Option<&Path>) -> Result<()> {
    let Some(path) = path else {
        return Ok(());
    };
    let directory = File::open(path)
        .map_err(|_| err!(IoFailure, "the object directory could not be opened"))?;
    let _ = directory.sync_all();
    Ok(())
}

impl Write for TemporaryContainer {
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        self.file.write(buffer)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.file.flush()
    }
}
