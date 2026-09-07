//! The one runtime of `docs/interop/FFI_CONTRACT.md` §14.
//!
//! Duplicate Rust runtimes in one process are forbidden, and the host is what
//! keeps that rule: `chur_runtime_open` issues a fresh handle every call,
//! because a test process opens several runtimes over several roots, which
//! `registry::Entry::owner` records. The registry and the Argon2id semaphore
//! are already one apiece for the whole process, so what two runtimes over one
//! root would really produce is two writers on one catalog and two unlocked
//! sessions the host can no longer reach. Each host therefore builds one
//! runtime and gives it the life of the process.

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
