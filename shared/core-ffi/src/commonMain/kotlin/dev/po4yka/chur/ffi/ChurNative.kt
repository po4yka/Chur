package dev.po4yka.chur.ffi

/**
 * The raw C ABI of `docs/interop/FFI_CONTRACT.md`, one Kotlin function per
 * export.
 *
 * Nothing outside this package calls it. It is the mechanical half of the
 * boundary: every function returns the `chur_status_t` the export returned and
 * writes its result through an array or a buffer, exactly as the C signature
 * does. [ChurVault] is the half a feature calls, and it is what turns a status
 * into a [ChurFailure] and an out-parameter into a return value.
 *
 * The Kotlin name of an export is the export's name without `chur_`, camel
 * cased. `rust/crates/chur-jni/tests/surface.rs` checks that mapping against
 * the header in both directions, so a drift is a failing build rather than an
 * unresolved symbol at run time.
 *
 * Android reaches these through the JNI adapter of ADR-0040. iOS reaches the
 * same exports through cinterop and loads no adapter.
 */
internal expect object ChurNative {
    fun abiVersionMajor(): Int
    fun abiVersionMinor(): Int
    fun capabilities(): Long
    fun objectFormatMin(): Int
    fun objectFormatMax(): Int
    fun keySlotFormatMin(): Int
    fun keySlotFormatMax(): Int
    fun buildFlavor(): Int
    fun statusIsKnown(value: Int): Boolean
    fun runtimeOpen(root: String, outRuntime: LongArray): Int
    fun runtimeClose(runtime: Long): Int
    fun vaultPresent(runtime: Long, outPresent: ByteArray): Int
    fun vaultCreateBegin(runtime: Long, password: ByteArray, memoryKib: Int, iterations: Int, parallelism: Int, outCreation: LongArray): Int
    fun vaultCreationAddRecoverySlot(creation: Long, destination: ChurBuffer, outWritten: IntArray): Int
    fun vaultCreationActivate(creation: Long, outSession: LongArray): Int
    fun vaultCreationAbandon(creation: Long): Int
    fun vaultUnlock(runtime: Long, factor: Int, secret: ByteArray, outSession: LongArray): Int
    fun vaultLock(session: Long, reason: Int): Int
    fun sessionClose(session: Long): Int
    fun sharingIdentity(session: Long, destination: ChurBuffer, outWritten: IntArray): Int
    fun sharingPrepare(session: Long, collectionId: ByteArray, recipientEnrollment: ByteArray, permissions: Int, fingerprintVerified: Boolean, destination: ChurBuffer, outWritten: IntArray): Int
    fun sharingAccept(session: Long, bundle: ChurBuffer, length: Int): Int
    fun sharingRevoke(session: Long, collectionId: ByteArray, recipientVaultId: ByteArray, recipientDeviceId: ByteArray, acceptedAtMs: Long, destination: ChurBuffer, outWritten: IntArray): Int
    fun syncStage(runtime: Long, vaultId: ByteArray, kind: Int, stagedAtMs: Long, record: ChurBuffer, length: Int): Int
    fun syncProcess(session: Long, nowMs: Long, outCounts: LongArray, outStatus: IntArray): Int
    fun catalogQuery(session: Long, scope: Int, sort: Int, kinds: Int, limit: Int, scopeId: ByteArray, cursor: ByteArray?, terms: ByteArray?, destination: ChurBuffer, outWritten: IntArray): Int
    fun importBegin(session: Long, sourceFd: Int, mediaClass: Int, width: Int, height: Int, durationMs: Long, knownLength: Long, captureTimeMs: Long, contentType: String, originalFilename: String?, outImport: LongArray): Int
    fun exportBegin(session: Long, objectId: ByteArray, destinationFd: Int, outExport: LongArray): Int
    fun backupCreate(session: Long, destinationFd: Int, outOperation: LongArray): Int
    fun backupRestore(runtime: Long, sourceFd: Int, password: ByteArray, outOperation: LongArray): Int
    fun integrityScanBegin(session: Long, objectId: ByteArray?, outScan: LongArray): Int
    fun operationPoll(operation: Long, outCounts: LongArray, outStates: IntArray): Int
    fun operationCancel(operation: Long): Int
    fun operationClose(operation: Long): Int
    fun objectReaderOpen(session: Long, objectId: ByteArray, streamKind: Int, outReader: LongArray): Int
    fun objectReaderSize(reader: Long, outSize: LongArray): Int
    fun objectReaderContentInfo(reader: Long, outNumbers: LongArray, outContentType: ByteArray): Int
    fun objectReaderReadAt(reader: Long, offset: Long, destination: ChurBuffer, outWritten: IntArray): Int
    fun objectReaderVerifyComplete(reader: Long, outState: IntArray): Int
    fun objectReaderClose(reader: Long): Int
    fun vaultAddRecoverySlot(session: Long, destination: ChurBuffer, outWritten: IntArray): Int
    fun vaultAddDeviceSlot(session: Long, itemId: ByteArray, outSecret: ByteArray): Int
    fun vaultRemoveSlot(session: Long, slotId: ByteArray): Int
    fun vaultChangePassword(session: Long, password: ByteArray): Int
    fun vaultSlots(session: Long, destination: ChurBuffer, outWritten: IntArray): Int
    fun vaultKeystoreBegin(session: Long, destination: ChurBuffer, outWritten: IntArray): Int
    fun vaultKeystoreCommit(session: Long, gcmNonce: ByteArray, wrappedRootSecret: ByteArray): Int
    fun vaultKeystoreMaterial(runtime: Long, destination: ChurBuffer, outWritten: IntArray): Int
    fun objectSetFavorite(session: Long, objectId: ByteArray, favorite: Boolean): Int
    fun objectDelete(session: Long, objectId: ByteArray): Int
    fun objectMetadata(session: Long, objectId: ByteArray, destination: ChurBuffer, outWritten: IntArray): Int
    fun albumCreate(session: Long, name: String, outAlbumId: ByteArray): Int
    fun albumSetMembership(session: Long, albumId: ByteArray, objectId: ByteArray, member: Boolean): Int
    fun albumList(session: Long, destination: ChurBuffer, outWritten: IntArray): Int
    fun tagCreate(session: Long, name: String, outTagId: ByteArray): Int
    fun objectSetTag(session: Long, tagId: ByteArray, objectId: ByteArray, tagged: Boolean): Int
    fun derivedPut(session: Long, objectId: ByteArray, kind: Int, width: Int, height: Int, source: ChurBuffer, length: Int): Int
    fun derivedRead(session: Long, objectId: ByteArray, kind: Int, destination: ChurBuffer, outWritten: IntArray): Int
}
