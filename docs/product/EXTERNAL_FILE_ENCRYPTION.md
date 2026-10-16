# Encrypt external files

> **Status:** Implemented product contract; physical-device and release acceptance remain separate

Open **Public Settings → Encrypt external files**, or **Vault → Settings → Backup → Encrypt external files**. This tool works before a vault is created, while it is locked, and from an unlocked vault. It does not add files to the library or change the current vault's credentials, objects, albums, notes, or synchronization state.

Choose files through the system document picker. Enter a new archive password and repeat it, then use **Save to Files** or **Share**. The suggested filename is `chur-archive.churbak`. The source files are kept. The archive password is independent of any local vault password. Keep it: the archive has no password reset or recovery phrase.

## Format and contents

The result is an ordinary [CHURBAK1 backup](../format/BACKUP_FORMAT_V1.md). Rust creates an isolated temporary vault with new keys and identifiers, streams the chosen bytes into encrypted opaque file objects, and creates a backup. No media decoder is required. Original bytes, file names, and MIME hints are retained inside encrypted metadata. Chosen images and audio are file objects; the tool does not generate previews, tags, albums, or notes.

The existing **Restore a backup or archive** path opens the result as a separate vault under its archive password. **Import encrypted archive** adds fresh copies to an existing unlocked vault. Neither path needs a new archive format or the source vault's credentials.

## Ownership, privacy, and limits

- A request accepts 1–32 files with at most 4 GiB of actual source bytes. Unknown lengths are supported. Known lengths are checked against the bytes read; a length mismatch refuses publication.
- The destination must be a fresh, empty, seekable regular file. Files providers can refuse this capability. Share and iOS Files use the existing private encrypted-output scratch budget; a provider or the available space can impose a smaller limit.
- Inputs are streamed in bounded buffers. The app makes no plaintext staging copy. Rust's temporary vault and the output contain ciphertext. File providers retain control of their original files and any provider-side copies.
- The host keeps source descriptors and iOS coordinated-read leases until the native operation has stopped. Rust duplicates descriptors before returning from begin. The host must not read or seek a selected descriptor during that operation.
- Password fields and selections are transient and use no saved-state registry. The route uses the private-window and switcher-cover policy even when no vault exists. Password/request byte buffers are cleared after handoff or refusal; managed input strings do not provide physical erasure guarantees.
- Progress and errors contain no source names. Cancel, Back, disposal, lock, and stale picker results prevent publication and release owned inputs and credentials. Existing background-lock policy applies. External work does not refresh an unlocked vault's idle timeout.
- Cancellation is cooperative. A blocked operating-system read or write can delay completion. UI teardown signals cancellation; cleanup and waiting for the worker run away from the UI thread.
- Failed output is discarded where the provider permits it. Process exit can leave an incomplete provider file; it is not reported as a completed archive. Normal cleanup and runtime startup remove abandoned owned encrypted staging. Logical deletion does not promise physical erasure on flash storage.

Only one external archive job can own a runtime's staging area at a time. The tool rejects symbolic links in its staging and claim paths before creating, scanning, or removing them. Completed external archives remain independent copies after later source deletion or local password changes.

## Validation

Native and host tests cover external bytes → CHURBAK1 → restore and logical import, an absent or locked local vault, empty and arbitrary file types, independent identifiers, unchanged source bytes and existing records, input/output bounds, wrong passwords, invalid descriptors, cancellation, staging ownership and symbolic links, stale picker callbacks, and cleanup. Emulator, simulator, and physical-device evidence remain separate.
