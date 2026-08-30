package dev.po4yka.chur.ffi

/**
 * The Android and JVM binding, through the JNI adapter of ADR-0040.
 *
 * Every function delegates to [ChurJni] and unwraps a [ChurBuffer] into the
 * direct `ByteBuffer` JNI can take. There is nothing else here: the boundary's
 * behaviour is `chur-ffi`'s.
 */
internal actual object ChurNative {

    actual fun abiVersionMajor(): Int = ChurJni.abiVersionMajor()

    actual fun abiVersionMinor(): Int = ChurJni.abiVersionMinor()

    actual fun capabilities(): Long = ChurJni.capabilities()

    actual fun objectFormatMin(): Int = ChurJni.objectFormatMin()

    actual fun objectFormatMax(): Int = ChurJni.objectFormatMax()

    actual fun keySlotFormatMin(): Int = ChurJni.keySlotFormatMin()

    actual fun keySlotFormatMax(): Int = ChurJni.keySlotFormatMax()

    actual fun buildFlavor(): Int = ChurJni.buildFlavor()

    actual fun statusIsKnown(value: Int): Boolean = ChurJni.statusIsKnown(value)

    actual fun runtimeOpen(root: String, outRuntime: LongArray): Int = ChurJni.runtimeOpen(root, outRuntime)

    actual fun runtimeClose(runtime: Long): Int = ChurJni.runtimeClose(runtime)

    actual fun vaultPresent(runtime: Long, outPresent: ByteArray): Int = ChurJni.vaultPresent(runtime, outPresent)

    actual fun vaultCreateBegin(runtime: Long, password: ByteArray, memoryKib: Int, iterations: Int, parallelism: Int, outCreation: LongArray): Int = ChurJni.vaultCreateBegin(runtime, password, memoryKib, iterations, parallelism, outCreation)

    actual fun vaultCreationAddRecoverySlot(creation: Long, destination: ChurBuffer, outWritten: IntArray): Int =
        ChurJni.vaultCreationAddRecoverySlot(creation, destination.buffer, outWritten)

    actual fun vaultCreationActivate(creation: Long, outSession: LongArray): Int = ChurJni.vaultCreationActivate(creation, outSession)

    actual fun vaultCreationAbandon(creation: Long): Int = ChurJni.vaultCreationAbandon(creation)

    actual fun vaultUnlock(runtime: Long, factor: Int, secret: ByteArray, outSession: LongArray): Int = ChurJni.vaultUnlock(runtime, factor, secret, outSession)

    actual fun vaultLock(session: Long, reason: Int): Int = ChurJni.vaultLock(session, reason)

    actual fun sessionClose(session: Long): Int = ChurJni.sessionClose(session)

    actual fun sharingIdentity(session: Long, destination: ChurBuffer, outWritten: IntArray): Int =
        ChurJni.sharingIdentity(session, destination.buffer, outWritten)

    actual fun sharingPrepare(session: Long, collectionId: ByteArray, recipientEnrollment: ByteArray, permissions: Int, fingerprintVerified: Boolean, destination: ChurBuffer, outWritten: IntArray): Int =
        ChurJni.sharingPrepare(session, collectionId, recipientEnrollment, permissions, fingerprintVerified, destination.buffer, outWritten)

    actual fun sharingAccept(session: Long, bundle: ChurBuffer, length: Int): Int =
        ChurJni.sharingAccept(session, bundle.buffer, length)

    actual fun sharingRevoke(session: Long, collectionId: ByteArray, recipientVaultId: ByteArray, recipientDeviceId: ByteArray, acceptedAtMs: Long, destination: ChurBuffer, outWritten: IntArray): Int =
        ChurJni.sharingRevoke(session, collectionId, recipientVaultId, recipientDeviceId, acceptedAtMs, destination.buffer, outWritten)

    actual fun syncStage(runtime: Long, vaultId: ByteArray, kind: Int, stagedAtMs: Long, record: ChurBuffer, length: Int): Int =
        ChurJni.syncStage(runtime, vaultId, kind, stagedAtMs, record.buffer, length)

    actual fun syncProcess(session: Long, nowMs: Long, outCounts: LongArray, outStatus: IntArray): Int =
        ChurJni.syncProcess(session, nowMs, outCounts, outStatus)

    actual fun catalogQuery(session: Long, scope: Int, sort: Int, kinds: Int, limit: Int, scopeId: ByteArray, cursor: ByteArray?, terms: ByteArray?, destination: ChurBuffer, outWritten: IntArray): Int = ChurJni.catalogQuery(session, scope, sort, kinds, limit, scopeId, cursor, terms, destination.buffer, outWritten)

    actual fun importBegin(session: Long, sourceFd: Int, mediaClass: Int, width: Int, height: Int, durationMs: Long, knownLength: Long, captureTimeMs: Long, contentType: String, originalFilename: String?, outImport: LongArray): Int = ChurJni.importBegin(session, sourceFd, mediaClass, width, height, durationMs, knownLength, captureTimeMs, contentType, originalFilename, outImport)

    actual fun exportBegin(session: Long, objectId: ByteArray, destinationFd: Int, outExport: LongArray): Int = ChurJni.exportBegin(session, objectId, destinationFd, outExport)
    actual fun backupCreate(session: Long, destinationFd: Int, outOperation: LongArray): Int = ChurJni.backupCreate(session, destinationFd, outOperation)
    actual fun backupRestore(runtime: Long, sourceFd: Int, password: ByteArray, outOperation: LongArray): Int = ChurJni.backupRestore(runtime, sourceFd, password, outOperation)

    actual fun integrityScanBegin(session: Long, objectId: ByteArray?, outScan: LongArray): Int = ChurJni.integrityScanBegin(session, objectId, outScan)

    actual fun operationPoll(operation: Long, outCounts: LongArray, outStates: IntArray): Int = ChurJni.operationPoll(operation, outCounts, outStates)

    actual fun operationCancel(operation: Long): Int = ChurJni.operationCancel(operation)

    actual fun operationClose(operation: Long): Int = ChurJni.operationClose(operation)

    actual fun objectReaderOpen(session: Long, objectId: ByteArray, streamKind: Int, outReader: LongArray): Int = ChurJni.objectReaderOpen(session, objectId, streamKind, outReader)

    actual fun objectReaderSize(reader: Long, outSize: LongArray): Int = ChurJni.objectReaderSize(reader, outSize)

    actual fun objectReaderContentInfo(reader: Long, outNumbers: LongArray, outContentType: ByteArray): Int = ChurJni.objectReaderContentInfo(reader, outNumbers, outContentType)

    actual fun objectReaderReadAt(reader: Long, offset: Long, destination: ChurBuffer, outWritten: IntArray): Int = ChurJni.objectReaderReadAt(reader, offset, destination.buffer, outWritten)

    actual fun objectReaderVerifyComplete(reader: Long, outState: IntArray): Int = ChurJni.objectReaderVerifyComplete(reader, outState)

    actual fun objectReaderClose(reader: Long): Int = ChurJni.objectReaderClose(reader)

    actual fun vaultAddRecoverySlot(session: Long, destination: ChurBuffer, outWritten: IntArray): Int =
        ChurJni.vaultAddRecoverySlot(session, destination.buffer, outWritten)

    actual fun vaultAddDeviceSlot(session: Long, itemId: ByteArray, outSecret: ByteArray): Int = ChurJni.vaultAddDeviceSlot(session, itemId, outSecret)

    actual fun vaultRemoveSlot(session: Long, slotId: ByteArray): Int = ChurJni.vaultRemoveSlot(session, slotId)

    actual fun vaultChangePassword(session: Long, password: ByteArray): Int = ChurJni.vaultChangePassword(session, password)

    actual fun vaultSlots(session: Long, destination: ChurBuffer, outWritten: IntArray): Int = ChurJni.vaultSlots(session, destination.buffer, outWritten)

    actual fun vaultKeystoreBegin(session: Long, destination: ChurBuffer, outWritten: IntArray): Int =
        ChurJni.vaultKeystoreBegin(session, destination.buffer, outWritten)

    actual fun vaultKeystoreCommit(session: Long, gcmNonce: ByteArray, wrappedRootSecret: ByteArray): Int =
        ChurJni.vaultKeystoreCommit(session, gcmNonce, wrappedRootSecret)

    actual fun vaultKeystoreMaterial(runtime: Long, destination: ChurBuffer, outWritten: IntArray): Int =
        ChurJni.vaultKeystoreMaterial(runtime, destination.buffer, outWritten)

    actual fun objectSetFavorite(session: Long, objectId: ByteArray, favorite: Boolean): Int = ChurJni.objectSetFavorite(session, objectId, favorite)

    actual fun objectDelete(session: Long, objectId: ByteArray): Int = ChurJni.objectDelete(session, objectId)

    actual fun objectMetadata(session: Long, objectId: ByteArray, destination: ChurBuffer, outWritten: IntArray): Int = ChurJni.objectMetadata(session, objectId, destination.buffer, outWritten)

    actual fun albumCreate(session: Long, name: String, outAlbumId: ByteArray): Int = ChurJni.albumCreate(session, name, outAlbumId)

    actual fun albumSetMembership(session: Long, albumId: ByteArray, objectId: ByteArray, member: Boolean): Int = ChurJni.albumSetMembership(session, albumId, objectId, member)

    actual fun albumList(session: Long, destination: ChurBuffer, outWritten: IntArray): Int = ChurJni.albumList(session, destination.buffer, outWritten)

    actual fun tagCreate(session: Long, name: String, outTagId: ByteArray): Int = ChurJni.tagCreate(session, name, outTagId)

    actual fun objectSetTag(session: Long, tagId: ByteArray, objectId: ByteArray, tagged: Boolean): Int = ChurJni.objectSetTag(session, tagId, objectId, tagged)

    actual fun derivedPut(session: Long, objectId: ByteArray, kind: Int, width: Int, height: Int, source: ChurBuffer, length: Int): Int = ChurJni.derivedPut(session, objectId, kind, width, height, source.buffer, length)

    actual fun derivedRead(session: Long, objectId: ByteArray, kind: Int, destination: ChurBuffer, outWritten: IntArray): Int = ChurJni.derivedRead(session, objectId, kind, destination.buffer, outWritten)

}
