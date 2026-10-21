//! The local delay after failed password unlocks, `PASSWORD_PROFILE.md` §11.
//!
//! Argon2id is the defence against an offline attack. This delay only slows
//! casual guesses on the device, so it is small and it never wipes anything.
//! It applies to the password factor alone: a recovery phrase and a device
//! unlock are never delayed, so recovery is never refused.
//!
//! One record counts the failures of the whole application. It lives in the
//! storage root, beside `registry/` and `vaults/` and inside neither, so it
//! is not part of any vault, it is not in a backup, and it is the same file
//! whichever identity the next credential opens. `unlock_with_password` tries
//! every identity in one call, so a failure is a failure of every identity,
//! and a success of any identity resets the count. `DECOY_VAULT.md` §10
//! forbids a count that differs with the existence of a real vault.
//!
//! An attempt is counted before the key derivation starts, durably, and the
//! success clears it. A process killed during the derivation therefore keeps
//! the failure, and a record that cannot be written refuses the attempt.
//!
//! The record keeps the end of the delay on the boot clock, which a wall-clock
//! change does not move, and the identifier of the boot. After a reboot the
//! full delay of the current count runs again, measured from the boot, so a
//! reboot gains nothing and a user waits at most one delay. A lock file
//! serializes every runtime and process that reads or writes the record.

use std::fs::File;
use std::io::Write;
use std::path::{Path, PathBuf};

use chur_catalog::paths::VaultRoot;
use chur_core::{ChurStatus, Error, Result};

/// The attempts that carry no delay.
pub(crate) const FREE_ATTEMPTS: u32 = 5;

const FILE_NAME: &str = "unlock-attempts";
const TEMPORARY_NAME: &str = "unlock-attempts.tmp";
const LOCK_NAME: &str = "unlock-attempts.lock";
const RECORD_VERSION: u8 = 2;
const RECORD_LENGTH: usize = 30;

/// The boot clock and the boot it belongs to.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Clocks {
    /// Milliseconds since boot, including time asleep.
    pub(crate) boot_ms: u64,
    /// The platform's identifier of this boot, when it can be read.
    pub(crate) boot_id: Option<[u8; 16]>,
}

impl Clocks {
    /// Reads the boot clock and the boot identifier now.
    pub(crate) fn now() -> Self {
        Self {
            boot_ms: boot_ms(),
            boot_id: boot_id(),
        }
    }
}

/// The failures since the last success, and where on the boot clock of which
/// boot the delay they cause ends.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub(crate) struct Record {
    failures: u32,
    boot_until_ms: u64,
    boot_id: Option<[u8; 16]>,
}

/// The delay after `failures` consecutive failures.
///
/// Five attempts are free, then 30 seconds, 1 minute, 5 minutes, and 15
/// minutes for every later failure. The cap keeps the delay a nuisance and
/// never a denial.
#[must_use]
pub(crate) const fn delay_ms(failures: u32) -> u64 {
    match failures {
        0..FREE_ATTEMPTS => 0,
        FREE_ATTEMPTS => 30_000,
        6 => 60_000,
        7 => 300_000,
        _ => 900_000,
    }
}

impl Record {
    /// Whether the record belongs to an earlier boot.
    ///
    /// Two known boot identifiers decide. Without them, a boot clock below
    /// the time of the last failure shows a reboot; a reboot that this cannot
    /// see leaves a wait of at most one delay on the boot clock.
    fn rebooted(&self, now: Clocks) -> bool {
        match (self.boot_id, now.boot_id) {
            (Some(stored), Some(current)) => stored != current,
            _ => now.boot_ms < self.boot_until_ms.saturating_sub(delay_ms(self.failures)),
        }
    }

    /// The same record, expressed against the current boot.
    ///
    /// After a reboot, the full delay of the current count runs again from
    /// the boot.
    #[must_use]
    fn rebased(&self, now: Clocks) -> Self {
        if !self.rebooted(now) {
            return Self {
                boot_id: self.boot_id.or(now.boot_id),
                ..*self
            };
        }
        Self {
            failures: self.failures,
            boot_until_ms: delay_ms(self.failures),
            boot_id: now.boot_id,
        }
    }

    /// The milliseconds before the next password attempt is accepted.
    #[must_use]
    pub(crate) fn remaining_ms(&self, now: Clocks) -> u64 {
        self.rebased(now)
            .boot_until_ms
            .saturating_sub(now.boot_ms)
            .min(delay_ms(self.failures))
    }

    /// The record after one more failure at `now`.
    #[must_use]
    fn failed(&self, now: Clocks) -> Self {
        let failures = self.failures.saturating_add(1);
        Self {
            failures,
            boot_until_ms: now.boot_ms.saturating_add(delay_ms(failures)),
            boot_id: now.boot_id,
        }
    }

    /// The record that stands for a damaged one: the first delayed failure.
    #[must_use]
    fn damaged(now: Clocks) -> Self {
        Self {
            failures: FREE_ATTEMPTS - 1,
            ..Self::default()
        }
        .failed(now)
    }

    fn encode(&self) -> [u8; RECORD_LENGTH] {
        let mut bytes = [0u8; RECORD_LENGTH];
        bytes[0] = RECORD_VERSION;
        bytes[1..5].copy_from_slice(&self.failures.to_be_bytes());
        bytes[5..13].copy_from_slice(&self.boot_until_ms.to_be_bytes());
        if let Some(id) = self.boot_id {
            bytes[13] = 1;
            bytes[14..30].copy_from_slice(&id);
        }
        bytes
    }

    fn decode(bytes: &[u8]) -> Option<Self> {
        let bytes: &[u8; RECORD_LENGTH] = bytes.try_into().ok()?;
        if bytes[0] != RECORD_VERSION {
            return None;
        }
        let mut failures = [0u8; 4];
        let mut until = [0u8; 8];
        let mut id = [0u8; 16];
        failures.copy_from_slice(&bytes[1..5]);
        until.copy_from_slice(&bytes[5..13]);
        id.copy_from_slice(&bytes[14..30]);
        let boot_id = match bytes[13] {
            0 if id == [0; 16] => None,
            1 => Some(id),
            _ => return None,
        };
        Some(Self {
            failures: u32::from_be_bytes(failures),
            boot_until_ms: u64::from_be_bytes(until),
            boot_id,
        })
    }
}

/// Runs one unlock under the delay.
///
/// A password attempt is refused while the delay runs. Otherwise one failure
/// is recorded durably before `unlock` derives anything; if that record
/// cannot be written, the attempt is refused with `IO_FAILURE`. Any success
/// resets the count, whichever identity and factor it opened. A password
/// attempt that fails before any derivation gives its count back; every
/// attempt that derived keeps it.
pub(crate) fn guarded<T>(
    root: &VaultRoot,
    password: bool,
    clocks: impl Fn() -> Clocks,
    unlock: impl FnOnce() -> Result<T>,
) -> Result<T> {
    let before = if password {
        let _lock = RecordLock::acquire(root)?;
        let now = clocks();
        let (record, damaged) = load(root, now);
        let rebased = record.rebased(now);
        if rebased.remaining_ms(now) > 0 {
            if damaged || rebased != record {
                let _ = store(root, &rebased);
            }
            return Err(Error::new(
                ChurStatus::UnlockDelayed,
                "password unlock is delayed after failed attempts",
            ));
        }
        let counted = rebased.failed(now);
        store(root, &counted)?;
        Some((rebased, counted))
    } else {
        None
    };
    let result = unlock();
    let outcome = RecordLock::acquire(root).and_then(|_lock| match (&result, before) {
        (Ok(_), _) => reset(root),
        (Err(error), Some((previous, counted))) if untested(error.status()) => {
            // The password was not tested, so the attempt is not a failure.
            // A concurrent attempt that counted after this one keeps its own.
            let now = clocks();
            if load(root, now).0 == counted {
                store(root, &previous)
            } else {
                Ok(())
            }
        }
        (Err(_), _) => Ok(()),
    });
    // The unlock result is what the host must see. A reset that fails leaves
    // a count the next success clears.
    let _ = outcome;
    result
}

/// Whether an unlock error comes before any key derivation.
///
/// `unlock_with_password` refuses an unusable password and a device that
/// cannot allocate the Argon2id memory before the first candidate. Every
/// other error, `CONFLICT` for an identity open elsewhere included, comes
/// after the candidates were derived, so the attempt keeps its count.
const fn untested(status: ChurStatus) -> bool {
    matches!(
        status,
        ChurStatus::KdfMemoryUnavailable | ChurStatus::InvalidInput
    )
}

/// An exclusive lock on a sibling file of the record, held while one caller
/// reads and writes the record. It is released when the value drops.
struct RecordLock(File);

impl RecordLock {
    fn acquire(root: &VaultRoot) -> Result<Self> {
        let base = root.base();
        std::fs::create_dir_all(base).map_err(|_| io_failure())?;
        let file = std::fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .write(true)
            .open(base.join(LOCK_NAME))
            .map_err(|_| io_failure())?;
        file.lock().map_err(|_| io_failure())?;
        Ok(Self(file))
    }
}

impl Drop for RecordLock {
    fn drop(&mut self) {
        let _ = self.0.unlock();
    }
}

/// Forgets every failure.
fn reset(root: &VaultRoot) -> Result<()> {
    match std::fs::remove_file(path(root)) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(_) => Err(Error::new(
            ChurStatus::IoFailure,
            "the unlock attempt record could not be removed",
        )),
    }
}

fn path(root: &VaultRoot) -> PathBuf {
    root.base().join(FILE_NAME)
}

/// Reads the record, and whether it was damaged. An absent record is no
/// failure. A record that cannot be read or parsed counts as the first delayed
/// failure at `now`, so damage to the file neither removes the delay nor
/// extends it.
fn load(root: &VaultRoot, now: Clocks) -> (Record, bool) {
    match std::fs::read(path(root)) {
        Ok(bytes) => {
            Record::decode(&bytes).map_or((Record::damaged(now), true), |record| (record, false))
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => (Record::default(), false),
        Err(_) => (Record::damaged(now), true),
    }
}

/// Writes the record to a temporary file, syncs it, and renames it, so a
/// power loss leaves the old record or the new one.
fn store(root: &VaultRoot, record: &Record) -> Result<()> {
    let base = root.base();
    std::fs::create_dir_all(base).map_err(|_| io_failure())?;
    let temporary = base.join(TEMPORARY_NAME);
    write_synced(&temporary, &record.encode())?;
    std::fs::rename(&temporary, path(root)).map_err(|_| io_failure())?;
    chur_catalog::paths::sync_directory(base)
}

fn write_synced(path: &Path, bytes: &[u8]) -> Result<()> {
    let mut file = File::create(path).map_err(|_| io_failure())?;
    file.write_all(bytes).map_err(|_| io_failure())?;
    file.sync_all().map_err(|_| io_failure())
}

fn io_failure() -> Error {
    Error::new(
        ChurStatus::IoFailure,
        "the unlock attempt record could not be written",
    )
}

/// Milliseconds since boot, including time asleep.
///
/// Linux and Android name this clock `CLOCK_BOOTTIME`. On Apple platforms
/// `CLOCK_MONOTONIC` already counts time asleep.
#[cfg(unix)]
fn boot_ms() -> u64 {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    const CLOCK: libc::clockid_t = libc::CLOCK_BOOTTIME;
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    const CLOCK: libc::clockid_t = libc::CLOCK_MONOTONIC;
    let mut time = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    // SAFETY: `time` is a valid, writable `timespec` for the call, and the
    // clock identifier is one the platform defines.
    #[expect(unsafe_code, reason = "the boot clock has no standard-library API")]
    let status = unsafe { libc::clock_gettime(CLOCK, &raw mut time) };
    if status != 0 {
        return 0;
    }
    let seconds = u64::try_from(time.tv_sec).unwrap_or(0);
    let nanos = u64::try_from(time.tv_nsec).unwrap_or(0);
    seconds
        .saturating_mul(1000)
        .saturating_add(nanos / 1_000_000)
}

/// Without a boot clock, the wall clock is the only clock.
#[cfg(not(unix))]
fn boot_ms() -> u64 {
    crate::api::now_ms()
}

/// The boot identifier: `/proc/sys/kernel/random/boot_id` on Linux and
/// Android, `kern.bootsessionuuid` on Apple platforms. Both are UUID text,
/// and a value that does not parse is no identifier.
fn boot_id() -> Option<[u8; 16]> {
    parse_uuid(boot_id_text()?.trim())
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn boot_id_text() -> Option<String> {
    std::fs::read_to_string("/proc/sys/kernel/random/boot_id").ok()
}

#[cfg(target_vendor = "apple")]
fn boot_id_text() -> Option<String> {
    let mut buffer = [0u8; 64];
    let mut length: libc::size_t = buffer.len();
    // SAFETY: the name is a NUL-terminated string, `buffer` is writable for
    // `length` bytes, and no new value is supplied.
    #[expect(
        unsafe_code,
        reason = "the boot identifier has no standard-library API"
    )]
    let status = unsafe {
        libc::sysctlbyname(
            c"kern.bootsessionuuid".as_ptr(),
            buffer.as_mut_ptr().cast(),
            &raw mut length,
            std::ptr::null_mut(),
            0,
        )
    };
    if status != 0 {
        return None;
    }
    let text = buffer.get(..length)?;
    let end = text
        .iter()
        .position(|byte| *byte == 0)
        .unwrap_or(text.len());
    String::from_utf8(text[..end].to_vec()).ok()
}

#[cfg(not(any(target_os = "linux", target_os = "android", target_vendor = "apple")))]
fn boot_id_text() -> Option<String> {
    None
}

fn parse_uuid(text: &str) -> Option<[u8; 16]> {
    let digits: Vec<u8> = text.bytes().filter(|byte| *byte != b'-').collect();
    if digits.len() != 32 || text.len() != 36 {
        return None;
    }
    let mut id = [0u8; 16];
    for (index, pair) in digits.chunks(2).enumerate() {
        let pair = std::str::from_utf8(pair).ok()?;
        id[index] = u8::from_str_radix(pair, 16).ok()?;
    }
    (id != [0; 16]).then_some(id)
}

#[cfg(test)]
mod tests {
    #![allow(clippy::expect_used, clippy::unwrap_used, clippy::panic)]

    use super::*;

    const BOOT_A: Option<[u8; 16]> = Some([0xa; 16]);
    const BOOT_B: Option<[u8; 16]> = Some([0xb; 16]);

    fn root() -> VaultRoot {
        let path = std::env::temp_dir().join(format!(
            "chur-unlock-throttle-{}",
            chur_crypto::random::id().unwrap().to_hex()
        ));
        std::fs::create_dir_all(&path).unwrap();
        VaultRoot::new(path)
    }

    const fn at(boot_ms: u64, boot_id: Option<[u8; 16]>) -> Clocks {
        Clocks { boot_ms, boot_id }
    }

    fn fail(root: &VaultRoot, times: u32, now: Clocks) {
        for _ in 0..times {
            let refused = guarded(
                root,
                true,
                || now,
                || -> Result<()> { Err(Error::new(ChurStatus::AuthenticationFailed, "wrong")) },
            );
            assert_eq!(
                refused.unwrap_err().status(),
                ChurStatus::AuthenticationFailed
            );
        }
    }

    /// Writes a record of `failures` failures, the last one at `now`.
    fn set(root: &VaultRoot, failures: u32, now: Clocks) {
        let record = Record {
            failures: failures - 1,
            ..Record::default()
        }
        .failed(now);
        store(root, &record).unwrap();
    }

    fn open(root: &VaultRoot, now: Clocks) -> Result<()> {
        guarded(root, true, || now, || Ok(()))
    }

    fn failures(root: &VaultRoot) -> u32 {
        load(root, at(0, None)).0.failures
    }

    #[test]
    fn the_schedule_is_five_free_attempts_then_a_capped_increase() {
        let schedule: Vec<u64> = (0..12).map(delay_ms).collect();
        assert_eq!(
            schedule,
            [
                0, 0, 0, 0, 0, 30_000, 60_000, 300_000, 900_000, 900_000, 900_000, 900_000
            ]
        );
        assert_eq!(delay_ms(u32::MAX), 900_000);
    }

    #[test]
    fn the_delay_survives_a_restart_and_ends_on_time() {
        let root = root();
        fail(&root, 4, at(50_000, BOOT_A));
        open(&root, at(50_000, BOOT_A)).unwrap();
        fail(&root, 5, at(50_000, BOOT_A));
        // A new process reads the same file.
        assert_eq!(
            open(&root, at(60_000, BOOT_A)).unwrap_err().status(),
            ChurStatus::UnlockDelayed
        );
        assert_eq!(failures(&root), 5);
        open(&root, at(80_000, BOOT_A)).unwrap();
        assert!(!path(&root).exists());
        std::fs::remove_dir_all(root.base()).unwrap();
    }

    #[test]
    fn the_failure_is_durable_before_the_derivation_and_a_write_failure_refuses() {
        let root = root();
        let now = at(50_000, BOOT_A);
        fail(&root, 2, now);
        // The derivation sees the count already raised: a kill here keeps it.
        let seen = guarded(&root, true, || now, || Ok(failures(&root))).unwrap();
        assert_eq!(seen, 3);
        assert!(!path(&root).exists());
        // A non-credential failure gives the count back.
        fail(&root, 2, now);
        let memory = guarded(
            &root,
            true,
            || now,
            || -> Result<()> { Err(Error::new(ChurStatus::KdfMemoryUnavailable, "memory")) },
        );
        assert_eq!(
            memory.unwrap_err().status(),
            ChurStatus::KdfMemoryUnavailable
        );
        assert_eq!(failures(&root), 2);
        // A record that cannot be written refuses without deriving.
        std::fs::create_dir(root.base().join(TEMPORARY_NAME)).unwrap();
        let mut derived = false;
        let refused = guarded(
            &root,
            true,
            || now,
            || {
                derived = true;
                Ok(())
            },
        );
        assert_eq!(refused.unwrap_err().status(), ChurStatus::IoFailure);
        assert!(!derived);
        std::fs::remove_dir_all(root.base()).unwrap();
    }

    #[test]
    fn a_wall_clock_has_no_part_in_the_decision() {
        let root = root();
        set(&root, 6, at(50_000, BOOT_A));
        assert!(open(&root, at(109_999, BOOT_A)).is_err());
        open(&root, at(110_000, BOOT_A)).unwrap();
        std::fs::remove_dir_all(root.base()).unwrap();
    }

    #[test]
    fn a_reboot_restarts_the_full_delay_from_the_boot() {
        let root = root();
        set(&root, 9, at(5_000_000, BOOT_A));
        // A reboot with a short uptime: the full 15 minutes run from boot.
        assert_eq!(
            open(&root, at(20_000, BOOT_B)).unwrap_err().status(),
            ChurStatus::UnlockDelayed
        );
        assert!(open(&root, at(899_999, BOOT_B)).is_err());
        open(&root, at(900_000, BOOT_B)).unwrap();
        // A reboot whose uptime already passed the stored value is still a
        // reboot, found by the boot identifier.
        set(&root, 9, at(100_000, BOOT_B));
        assert!(open(&root, at(500_000, BOOT_A)).is_err());
        open(&root, at(900_000, BOOT_A)).unwrap();
        // Without identifiers, a boot clock below the failure shows a reboot.
        set(&root, 9, at(5_000_000, None));
        assert!(open(&root, at(1_000, None)).is_err());
        open(&root, at(900_000, None)).unwrap();
        std::fs::remove_dir_all(root.base()).unwrap();
    }

    #[test]
    fn a_damaged_record_delays_once() {
        let root = root();
        std::fs::write(path(&root), b"broken").unwrap();
        let now = at(50_000, BOOT_A);
        assert!(open(&root, now).is_err());
        assert!(open(&root, at(79_999, BOOT_A)).is_err());
        open(&root, at(80_000, BOOT_A)).unwrap();
        std::fs::remove_dir_all(root.base()).unwrap();
    }

    #[test]
    fn concurrent_runtimes_lose_no_failure() {
        let root = root();
        let threads: Vec<_> = (0..4)
            .map(|_| {
                let root = VaultRoot::new(root.base().to_path_buf());
                std::thread::spawn(move || fail(&root, 1, at(50_000, BOOT_A)))
            })
            .collect();
        for thread in threads {
            thread.join().unwrap();
        }
        assert_eq!(failures(&root), 4);
        std::fs::remove_dir_all(root.base()).unwrap();
    }

    fn attempt(root: &VaultRoot, password: &[u8], now: Clocks) -> Result<()> {
        guarded(
            root,
            true,
            || now,
            || chur_catalog::vault::unlock_with_password(root, password, 1),
        )
        .and_then(|mut session| session.lock())
    }

    #[test]
    fn a_decoy_success_resets_the_count_exactly_as_a_real_success_does() {
        const REAL: &[u8] = b"the real identity password";
        const DECOY: &[u8] = b"the decoy identity password";
        let root = root();
        for password in [REAL, DECOY] {
            chur_catalog::vault::create(&root, password, 1)
                .unwrap()
                .activate()
                .unwrap()
                .lock()
                .unwrap();
        }
        let mut after = Vec::new();
        for password in [DECOY, REAL] {
            let start = at(50_000, BOOT_A);
            for _ in 0..FREE_ATTEMPTS {
                assert_eq!(
                    attempt(&root, b"a wrong password", start)
                        .unwrap_err()
                        .status(),
                    ChurStatus::AuthenticationFailed
                );
            }
            let before = std::fs::read(path(&root)).unwrap();
            // The delay refuses every password before any derivation, the
            // valid ones of either identity included.
            for refused in [REAL, DECOY] {
                assert_eq!(
                    attempt(&root, refused, start).unwrap_err().status(),
                    ChurStatus::UnlockDelayed
                );
            }
            // The recovery factor is never delayed, and its success resets.
            guarded(&root, false, || start, || Ok(())).unwrap();
            assert!(!path(&root).exists());
            store(&root, &Record::decode(&before).unwrap()).unwrap();
            attempt(&root, password, at(80_000, BOOT_A)).unwrap();
            after.push((before, path(&root).exists()));
        }
        // The same failures leave the same bytes, and either success leaves
        // no record: nothing records which identity opened.
        assert_eq!(after[0], after[1]);
        assert!(!after[0].1);
        std::fs::remove_dir_all(root.base()).unwrap();
    }

    #[test]
    fn a_wrong_password_counts_while_another_identity_is_open() {
        const REAL: &[u8] = b"the real identity password";
        const DECOY: &[u8] = b"the decoy identity password";
        let root = root();
        chur_catalog::vault::create(&root, REAL, 1)
            .unwrap()
            .activate()
            .unwrap()
            .lock()
            .unwrap();
        // The second identity stays open, so its descriptor is held.
        let mut open = chur_catalog::vault::create(&root, DECOY, 1)
            .unwrap()
            .activate()
            .unwrap();
        let now = at(50_000, BOOT_A);
        let refused = attempt(&root, b"a wrong password", now).unwrap_err();
        assert_eq!(refused.status(), ChurStatus::Conflict);
        assert_eq!(failures(&root), 1);
        open.lock().unwrap();
        std::fs::remove_dir_all(root.base()).unwrap();
    }

    #[test]
    fn the_platform_clock_and_boot_identifier_read() {
        let first = boot_ms();
        assert!(first > 0);
        assert!(boot_ms() >= first);
        assert_eq!(boot_id(), boot_id());
        #[cfg(any(target_vendor = "apple", target_os = "android"))]
        assert!(boot_id().is_some());
        assert_eq!(
            parse_uuid("5e5f5093-7166-4cac-a627-7b20aa137cbb"),
            Some([
                0x5e, 0x5f, 0x50, 0x93, 0x71, 0x66, 0x4c, 0xac, 0xa6, 0x27, 0x7b, 0x20, 0xaa, 0x13,
                0x7c, 0xbb
            ])
        );
        assert_eq!(parse_uuid("not a uuid"), None);
    }
}
