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
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use chur_catalog::paths::VaultRoot;
use chur_core::Result;

/// The process runtime.
pub struct Runtime {
    root: VaultRoot,
    /// Whether an unlock is in flight, §8.1: at most one unlock runs per
    /// runtime, and a second one conflicts before deriving anything.
    unlocking: Arc<AtomicBool>,
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
        Ok(Self {
            root,
            unlocking: Arc::new(AtomicBool::new(false)),
        })
    }

    /// The storage root.
    #[must_use]
    pub const fn root(&self) -> &VaultRoot {
        &self.root
    }

    /// Claims the one in-flight unlock of §8.1.
    ///
    /// Returns `None` when another unlock is already running on this runtime,
    /// and the caller turns that into `CONFLICT` before deriving anything, so
    /// a double-tapped unlock button never starts two derivations. The claim
    /// is released when the returned guard drops, on every path including a
    /// panic unwind. The flag is shared because the guard outlives the borrow
    /// of `self`: the runtime mutex is never held across the derivation.
    pub fn begin_unlock(&self) -> Option<UnlockGuard> {
        self.unlocking
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
            .then_some(UnlockGuard(Arc::clone(&self.unlocking)))
    }
}

/// The in-flight unlock claim of §8.1, released when it drops.
pub struct UnlockGuard(Arc<AtomicBool>);

impl Drop for UnlockGuard {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::panic)]

    use super::*;

    #[test]
    fn a_second_unlock_conflicts_until_the_first_releases() {
        // §8.1: at most one unlock is in flight per runtime. The claim is
        // exclusive while it lives and frees on drop, on every path.
        let mut root = std::env::temp_dir();
        root.push(format!("chur-runtime-{}", std::process::id()));
        std::fs::create_dir_all(&root).expect("create");
        let runtime = Runtime::open(root).expect("open");

        let first = runtime.begin_unlock().expect("the first unlock claims");
        assert!(
            runtime.begin_unlock().is_none(),
            "a second unlock while one is in flight must be refused"
        );
        drop(first);
        assert!(
            runtime.begin_unlock().is_some(),
            "the release of the first unlock frees the runtime"
        );
    }
}
