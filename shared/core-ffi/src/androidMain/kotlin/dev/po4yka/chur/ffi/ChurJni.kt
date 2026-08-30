package dev.po4yka.chur.ffi

import java.nio.ByteBuffer

/**
 * The raw JNI binding, ADR-0040.
 *
 * It is a separate object from [ChurNative] because the two cannot be one: the
 * `actual` takes the common [ChurBuffer], and JNI can only take a
 * `java.nio.ByteBuffer`. Every symbol here is
 * `Java_dev_po4yka_chur_ffi_ChurJni_<method>`, which
 * `rust/crates/chur-jni/tests/surface.rs` checks against `chur.h` in both
 * directions.
 *
 * `libchur_jni` links `chur-ffi` statically, so loading it loads the whole
 * vault. It is loaded once, in the initializer, because
 * `docs/interop/FFI_CONTRACT.md` §14 forbids duplicate Rust runtimes in one
 * process and a second load would be one.
 */
internal object ChurJni {
    init {
        System.loadLibrary("chur_jni")
    }

    external fun abiVersionMajor(): Int

    external fun abiVersionMinor(): Int

    external fun capabilities(): Long

    external fun objectFormatMin(): Int

    external fun objectFormatMax(): Int

    external fun keySlotFormatMin(): Int

    external fun keySlotFormatMax(): Int

    external fun buildFlavor(): Int

    external fun statusIsKnown(value: Int): Boolean

    external fun runtimeOpen(root: String, outRuntime: LongArray): Int

    external fun runtimeClose(runtime: Long): Int

    external fun vaultPresent(runtime: Long, outPresent: ByteArray): Int

    external fun vaultCreateBegin(runtime: Long, password: ByteArray, memoryKib: Int, iterations: Int, parallelism: Int, outCreation: LongArray): Int

    external fun vaultCreationAddRecoverySlot(creation: Long, destination: ByteBuffer, outWritten: IntArray): Int

    external fun vaultCreationActivate(creation: Long, outSession: LongArray): Int

    external fun vaultCreationAbandon(creation: Long): Int

    external fun vaultUnlock(runtime: Long, factor: Int, secret: ByteArray, outSession: LongArray): Int

    external fun vaultLock(session: Long, reason: Int): Int

    external fun sessionClose(session: Long): Int

    external fun sharingIdentity(session: Long, destination: ByteBuffer, outWritten: IntArray): Int

    external fun sharingPrepare(session: Long, collectionId: ByteArray, recipientEnrollment: ByteArray, permissions: Int, fingerprintVerified: Boolean, destination: ByteBuffer, outWritten: IntArray): Int

    external fun sharingAccept(session: Long, bundle: ByteBuffer, length: Int): Int

    external fun sharingRevoke(session: Long, collectionId: ByteArray, recipientVaultId: ByteArray, recipientDeviceId: ByteArray, acceptedAtMs: Long, destination: ByteBuffer, outWritten: IntArray): Int

    external fun syncStage(runtime: Long, vaultId: ByteArray, kind: Int, stagedAtMs: Long, record: ByteBuffer, length: Int): Int

    external fun syncProcess(session: Long, nowMs: Long, outCounts: LongArray, outStatus: IntArray): Int

    external fun catalogQuery(session: Long, scope: Int, sort: Int, kinds: Int, limit: Int, scopeId: ByteArray, cursor: ByteArray?, terms: ByteArray?, destination: ByteBuffer, outWritten: IntArray): Int

    external fun importBegin(session: Long, sourceFd: Int, mediaClass: Int, width: Int, height: Int, durationMs: Long, knownLength: Long, captureTimeMs: Long, contentType: String, originalFilename: String?, outImport: LongArray): Int

    external fun exportBegin(session: Long, objectId: ByteArray, destinationFd: Int, outExport: LongArray): Int
    external fun backupCreate(session: Long, destinationFd: Int, outOperation: LongArray): Int
    external fun backupRestore(runtime: Long, sourceFd: Int, password: ByteArray, outOperation: LongArray): Int

    external fun integrityScanBegin(session: Long, objectId: ByteArray?, outScan: LongArray): Int

    external fun operationPoll(operation: Long, outCounts: LongArray, outStates: IntArray): Int

    external fun operationCancel(operation: Long): Int

    external fun operationClose(operation: Long): Int

    external fun objectReaderOpen(session: Long, objectId: ByteArray, streamKind: Int, outReader: LongArray): Int

    external fun objectReaderSize(reader: Long, outSize: LongArray): Int

    external fun objectReaderContentInfo(reader: Long, outNumbers: LongArray, outContentType: ByteArray): Int

    external fun objectReaderReadAt(reader: Long, offset: Long, destination: ByteBuffer, outWritten: IntArray): Int

    external fun objectReaderVerifyComplete(reader: Long, outState: IntArray): Int

    external fun objectReaderClose(reader: Long): Int

    external fun vaultAddRecoverySlot(session: Long, destination: ByteBuffer, outWritten: IntArray): Int

    external fun vaultAddDeviceSlot(session: Long, itemId: ByteArray, outSecret: ByteArray): Int

    external fun vaultRemoveSlot(session: Long, slotId: ByteArray): Int

    external fun vaultChangePassword(session: Long, password: ByteArray): Int

    external fun vaultSlots(session: Long, destination: ByteBuffer, outWritten: IntArray): Int

    external fun vaultKeystoreBegin(session: Long, destination: ByteBuffer, outWritten: IntArray): Int

    external fun vaultKeystoreCommit(session: Long, gcmNonce: ByteArray, wrappedRootSecret: ByteArray): Int

    external fun vaultKeystoreMaterial(runtime: Long, destination: ByteBuffer, outWritten: IntArray): Int

    external fun objectSetFavorite(session: Long, objectId: ByteArray, favorite: Boolean): Int

    external fun objectDelete(session: Long, objectId: ByteArray): Int

    external fun objectMetadata(session: Long, objectId: ByteArray, destination: ByteBuffer, outWritten: IntArray): Int

    external fun albumCreate(session: Long, name: String, outAlbumId: ByteArray): Int

    external fun albumSetMembership(session: Long, albumId: ByteArray, objectId: ByteArray, member: Boolean): Int

    external fun albumList(session: Long, destination: ByteBuffer, outWritten: IntArray): Int

    external fun tagCreate(session: Long, name: String, outTagId: ByteArray): Int

    external fun objectSetTag(session: Long, tagId: ByteArray, objectId: ByteArray, tagged: Boolean): Int

    external fun derivedPut(session: Long, objectId: ByteArray, kind: Int, width: Int, height: Int, source: ByteBuffer, length: Int): Int

    external fun derivedRead(session: Long, objectId: ByteArray, kind: Int, destination: ByteBuffer, outWritten: IntArray): Int

}
