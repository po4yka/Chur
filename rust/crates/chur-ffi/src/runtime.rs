//! The one runtime of `docs/interop/FFI_CONTRACT.md` §14.
//!
//! Duplicate Rust runtimes in one process are forbidden, so a second
//! `chur_runtime_open` returns the same handle rather than a second runtime:
//! two runtimes would mean two registries, two Argon2id semaphores, and two
//! writers on one catalog.

use std::path::PathBuf;

use chur_catalog::paths::VaultRoot;
use chur_core::Result;

/// The process runtime.
pub struct Runtime {
    root: VaultRoot,
}

impl Runtime {
    /// Opens the runtime over a storage root.
    ///
    /// It sweeps the registry's temporary descriptors first, which
    /// `docs/format/VAULT_DESCRIPTOR_V1.md` §9 requires of a start after a
    /// creation that did not reach `ACTIVE`.
    pub fn open(root: PathBuf) -> Result<Self> {
        let root = VaultRoot::new(root);
        root.sweep_temporary()?;
        Ok(Self { root })
    }

    /// The storage root.
    #[must_use]
    pub const fn root(&self) -> &VaultRoot {
        &self.root
    }
}
