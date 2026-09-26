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
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

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
    /// creation that did not reach `ACTIVE`. Then it removes the vault
    /// directories that no installed descriptor names, because
    /// `docs/security/PROVISIONING.md` §9 requires that start to leave no
    /// orphaned directory. That second sweep is best-effort: a directory it
    /// cannot remove stays inert and never stops the runtime from opening.
    pub fn open(root: PathBuf) -> Result<Self> {
        let root = VaultRoot::new(root);
        root.sweep_temporary()?;
        chur_catalog::vault::sweep_orphaned_directories(&root);
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
    use chur_catalog::vault;

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

    #[test]
    fn a_restart_removes_the_directory_of_a_creation_that_never_reached_active() {
        // `PROVISIONING.md` §9: a creation that was interrupted and then
        // restarted leaves no orphaned directory. A creation dropped without
        // `abandon` is what a process death leaves behind. The installed vault
        // beside it keeps its directory.
        let mut root = std::env::temp_dir();
        root.push(format!("chur-runtime-orphan-{}", std::process::id()));
        std::fs::create_dir_all(&root).expect("create");
        let runtime = Runtime::open(root.clone()).expect("open");
        drop(
            vault::create(runtime.root(), KEPT, 1)
                .expect("create")
                .activate()
                .expect("activate"),
        );
        drop(vault::create(runtime.root(), INTERRUPTED, 1).expect("create"));
        let directories = || {
            std::fs::read_dir(runtime.root().vaults())
                .expect("vaults")
                .count()
        };
        assert_eq!(directories(), 2);

        let restarted = Runtime::open(root.clone()).expect("restart");
        assert_eq!(directories(), 1, "the interrupted creation's directory");
        drop(vault::unlock_with_password(restarted.root(), KEPT, 1).expect("the installed vault"));
        std::fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn a_second_runtime_keeps_the_directory_of_a_creation_that_still_waits() {
        // `FFI_CONTRACT.md` §8.1 expects a second process on one root. A
        // creation that waits for its phrase holds the claim on its directory,
        // so the sweep of a runtime opened meanwhile leaves it, and the
        // creation still activates into a vault that opens.
        let mut root = std::env::temp_dir();
        root.push(format!("chur-runtime-waiting-{}", std::process::id()));
        std::fs::create_dir_all(&root).expect("create");
        let runtime = Runtime::open(root.clone()).expect("open");
        let waiting = vault::create(runtime.root(), KEPT, 1).expect("create");

        let second = Runtime::open(root.clone()).expect("a second runtime");
        let directories = std::fs::read_dir(second.root().vaults())
            .expect("vaults")
            .count();
        assert_eq!(directories, 1, "the waiting creation's directory");
        drop(waiting.activate().expect("activate"));
        drop(vault::unlock_with_password(second.root(), KEPT, 1).expect("the activated vault"));
        std::fs::remove_dir_all(root).expect("cleanup");
    }

    #[cfg(unix)]
    #[test]
    fn an_orphan_the_sweep_cannot_remove_does_not_stop_the_runtime() {
        // The sweep is best-effort: a directory it cannot remove stays inert,
        // because nothing names it, and the runtime still opens.
        use std::os::unix::fs::PermissionsExt as _;
        let mut root = std::env::temp_dir();
        root.push(format!("chur-runtime-stuck-{}", std::process::id()));
        std::fs::create_dir_all(&root).expect("create");
        let runtime = Runtime::open(root.clone()).expect("open");
        drop(vault::create(runtime.root(), INTERRUPTED, 1).expect("create"));
        let vaults = runtime.root().vaults();
        let mode = |bits| std::fs::Permissions::from_mode(bits);
        std::fs::set_permissions(&vaults, mode(0o555)).expect("read-only");

        let reopened = Runtime::open(root.clone());
        std::fs::set_permissions(&vaults, mode(0o755)).expect("writable");
        assert!(reopened.is_ok(), "a sweep failure must not stop the open");
        std::fs::remove_dir_all(root).expect("cleanup");
    }

    const KEPT: &[u8] = b"correct horse battery staple";
    const INTERRUPTED: &[u8] = b"a creation that never reached ACTIVE";
}
