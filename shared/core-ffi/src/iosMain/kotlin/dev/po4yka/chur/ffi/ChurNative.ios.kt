@file:OptIn(ExperimentalForeignApi::class)

package dev.po4yka.chur.ffi

import dev.po4yka.chur.native.ChurContentInfoV1
import dev.po4yka.chur.native.ChurCreateRequestV1
import dev.po4yka.chur.native.ChurImportRequestV1
import dev.po4yka.chur.native.ChurObjectRefV1
import dev.po4yka.chur.native.ChurProgressV1
import dev.po4yka.chur.native.ChurQueryV1
import dev.po4yka.chur.native.ChurRuntimeConfigV1
import dev.po4yka.chur.native.ChurScanRequestV1
import dev.po4yka.chur.native.ChurUnlockRequestV1
import dev.po4yka.chur.native.chur_abi_version_major
import dev.po4yka.chur.native.chur_abi_version_minor
import dev.po4yka.chur.native.chur_album_create
import dev.po4yka.chur.native.chur_album_list
import dev.po4yka.chur.native.chur_album_set_membership
import dev.po4yka.chur.native.chur_build_flavor
import dev.po4yka.chur.native.chur_capabilities
import dev.po4yka.chur.native.chur_catalog_query
import dev.po4yka.chur.native.chur_derived_put
import dev.po4yka.chur.native.chur_derived_read
import dev.po4yka.chur.native.chur_export_begin
import dev.po4yka.chur.native.chur_handle_tVar
import dev.po4yka.chur.native.chur_import_begin
import dev.po4yka.chur.native.chur_integrity_scan_begin
import dev.po4yka.chur.native.chur_key_slot_format_max
import dev.po4yka.chur.native.chur_key_slot_format_min
import dev.po4yka.chur.native.chur_object_delete
import dev.po4yka.chur.native.chur_object_format_max
import dev.po4yka.chur.native.chur_object_format_min
import dev.po4yka.chur.native.chur_object_metadata
import dev.po4yka.chur.native.chur_object_reader_close
import dev.po4yka.chur.native.chur_object_reader_content_info
import dev.po4yka.chur.native.chur_object_reader_open
import dev.po4yka.chur.native.chur_object_reader_read_at
import dev.po4yka.chur.native.chur_object_reader_size
import dev.po4yka.chur.native.chur_object_reader_verify_complete
import dev.po4yka.chur.native.chur_object_set_favorite
import dev.po4yka.chur.native.chur_object_set_tag
import dev.po4yka.chur.native.chur_operation_cancel
import dev.po4yka.chur.native.chur_operation_close
import dev.po4yka.chur.native.chur_operation_poll
import dev.po4yka.chur.native.chur_runtime_close
import dev.po4yka.chur.native.chur_runtime_open
import dev.po4yka.chur.native.chur_session_close
import dev.po4yka.chur.native.chur_status_is_known
import dev.po4yka.chur.native.chur_tag_create
import dev.po4yka.chur.native.chur_vault_add_device_slot
import dev.po4yka.chur.native.chur_vault_add_recovery_slot
import dev.po4yka.chur.native.chur_vault_change_password
import dev.po4yka.chur.native.chur_vault_create_begin
import dev.po4yka.chur.native.chur_vault_creation_abandon
import dev.po4yka.chur.native.chur_vault_creation_activate
import dev.po4yka.chur.native.chur_vault_creation_add_recovery_slot
import dev.po4yka.chur.native.chur_vault_lock
import dev.po4yka.chur.native.chur_vault_present
import dev.po4yka.chur.native.chur_vault_remove_slot
import dev.po4yka.chur.native.chur_vault_slots
import dev.po4yka.chur.native.chur_vault_unlock
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/**
 * The iOS binding, through cinterop.
 *
 * `docs/interop/FFI_CONTRACT.md` §1 has Kotlin/Native reach the C ABI directly,
 * so there is no adapter library and no second boundary: these functions call
 * the same exports Android reaches through the JNI adapter of ADR-0040.
 *
 * Two conversions repeat and are factored out. A Kotlin `ByteArray` becomes a
 * C pointer by pinning, and an empty array becomes a null pointer rather than
 * the address of nothing, which `addressOf(0)` refuses. A fixed-size C array
 * inside a structure is written element by element, because cinterop exposes it
 * as a pointer rather than as a value that can be assigned.
 */
internal actual object ChurNative {

    // -----------------------------------------------------------------------
    // Handshake
    // -----------------------------------------------------------------------

    actual fun abiVersionMajor(): Int = chur_abi_version_major().toInt()

    actual fun abiVersionMinor(): Int = chur_abi_version_minor().toInt()

    actual fun capabilities(): Long = chur_capabilities().toLong()

    actual fun objectFormatMin(): Int = chur_object_format_min().toInt()

    actual fun objectFormatMax(): Int = chur_object_format_max().toInt()

    actual fun keySlotFormatMin(): Int = chur_key_slot_format_min().toInt()

    actual fun keySlotFormatMax(): Int = chur_key_slot_format_max().toInt()

    actual fun buildFlavor(): Int = chur_build_flavor().toInt()

    actual fun statusIsKnown(value: Int): Boolean = chur_status_is_known(value)

    // -----------------------------------------------------------------------
    // Runtime, session, and creation
    // -----------------------------------------------------------------------

    actual fun runtimeOpen(root: String, outRuntime: LongArray): Int = memScoped {
        val bytes = root.encodeToByteArray()
        bytes.pinnedPointer { pointer ->
            val config = alloc<ChurRuntimeConfigV1>()
            config.root_path = pointer
            config.root_path_length = bytes.size.toUInt()
            handleCall(outRuntime) { out -> chur_runtime_open(config.ptr, out) }
        }
    }

    actual fun runtimeClose(runtime: Long): Int = chur_runtime_close(runtime.toULong())

    actual fun vaultPresent(runtime: Long, outPresent: ByteArray): Int = memScoped {
        val present = alloc<UByteVar>()
        val status = chur_vault_present(runtime.toULong(), present.ptr)
        if (status == 0) outPresent[0] = present.value.toByte()
        status
    }

    actual fun vaultCreateBegin(
        runtime: Long,
        password: ByteArray,
        memoryKib: Int,
        iterations: Int,
        parallelism: Int,
        outCreation: LongArray,
    ): Int = memScoped {
        password.pinnedPointer { pointer ->
            val request = alloc<ChurCreateRequestV1>()
            request.password = pointer
            request.password_length = password.size.toUInt()
            request.memory_kib = memoryKib.toUInt()
            request.iterations = iterations.toUInt()
            request.parallelism = parallelism.toUInt()
            handleCall(outCreation) { out ->
                chur_vault_create_begin(runtime.toULong(), request.ptr, out)
            }
        }
    }

    actual fun vaultCreationAddRecoverySlot(
        creation: Long,
        destination: ChurBuffer,
        outWritten: IntArray,
    ): Int = memScoped {
        writtenCall(outWritten) { written ->
            chur_vault_creation_add_recovery_slot(
                creation.toULong(),
                destination.pointer,
                destination.size.toULong(),
                written,
            )
        }
    }

    actual fun vaultCreationActivate(creation: Long, outSession: LongArray): Int = memScoped {
        handleCall(outSession) { out -> chur_vault_creation_activate(creation.toULong(), out) }
    }

    actual fun vaultCreationAbandon(creation: Long): Int =
        chur_vault_creation_abandon(creation.toULong())

    actual fun vaultUnlock(
        runtime: Long,
        factor: Int,
        secret: ByteArray,
        outSession: LongArray,
    ): Int = memScoped {
        secret.pinnedPointer { pointer ->
            val request = alloc<ChurUnlockRequestV1>()
            request.factor = factor.toUByte()
            request.secret = pointer
            request.secret_length = secret.size.toUInt()
            handleCall(outSession) { out -> chur_vault_unlock(runtime.toULong(), request.ptr, out) }
        }
    }

    actual fun vaultLock(session: Long, reason: Int): Int =
        chur_vault_lock(session.toULong(), reason.toUInt())

    actual fun sessionClose(session: Long): Int = chur_session_close(session.toULong())

    // -----------------------------------------------------------------------
    // Catalog queries
    // -----------------------------------------------------------------------

    actual fun catalogQuery(
        session: Long,
        scope: Int,
        sort: Int,
        kinds: Int,
        limit: Int,
        scopeId: ByteArray,
        cursor: ByteArray?,
        terms: ByteArray?,
        destination: ChurBuffer,
        outWritten: IntArray,
    ): Int = memScoped {
        val termBytes = terms ?: ByteArray(0)
        termBytes.pinnedPointer { termPointer ->
            val query = alloc<ChurQueryV1>()
            query.scope = scope.toUByte()
            query.sort = sort.toUByte()
            query.kinds = kinds.toUShort()
            query.limit = limit.toUInt()
            query.scope_id.fill(scopeId)
            query.cursor_present = if (cursor != null) 1u else 0u
            query.cursor.fill(cursor ?: ByteArray(CURSOR_LENGTH))
            query.terms = termPointer
            query.terms_length = termBytes.size.toUInt()
            writtenCall(outWritten) { written ->
                chur_catalog_query(
                    session.toULong(),
                    query.ptr,
                    destination.pointer,
                    destination.size.toULong(),
                    written,
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Operations
    // -----------------------------------------------------------------------

    actual fun importBegin(
        session: Long,
        sourceFd: Int,
        mediaClass: Int,
        width: Int,
        height: Int,
        durationMs: Long,
        knownLength: Long,
        captureTimeMs: Long,
        contentType: String,
        originalFilename: String?,
        outImport: LongArray,
    ): Int = memScoped {
        val typeBytes = contentType.encodeToByteArray()
        val nameBytes = originalFilename?.encodeToByteArray()
        typeBytes.pinnedPointer { typePointer ->
            (nameBytes ?: ByteArray(0)).pinnedPointer { namePointer ->
                val request = alloc<ChurImportRequestV1>()
                request.seekable = 1u
                request.known_length_present = if (knownLength >= 0) 1u else 0u
                request.media_class = mediaClass.toUByte()
                request.width = width.toUInt()
                request.height = height.toUInt()
                request.duration_ms = maxOf(durationMs, 0L).toULong()
                request.known_length = maxOf(knownLength, 0L).toULong()
                request.capture_time_ms = maxOf(captureTimeMs, 0L).toULong()
                request.capture_time_present = if (captureTimeMs >= 0) 1u else 0u
                request.content_type = typePointer
                request.content_type_length = typeBytes.size.toUInt()
                request.original_filename = if (nameBytes == null) null else namePointer
                request.original_filename_length = (nameBytes?.size ?: 0).toUInt()
                handleCall(outImport) { out ->
                    chur_import_begin(session.toULong(), sourceFd, request.ptr, out)
                }
            }
        }
    }

    actual fun exportBegin(
        session: Long,
        objectId: ByteArray,
        destinationFd: Int,
        outExport: LongArray,
    ): Int = memScoped {
        val reference = objectReference(objectId)
        handleCall(outExport) { out ->
            chur_export_begin(session.toULong(), reference.ptr, destinationFd, out)
        }
    }

    actual fun integrityScanBegin(session: Long, objectId: ByteArray?, outScan: LongArray): Int =
        memScoped {
            val request = alloc<ChurScanRequestV1>()
            request.single_object = if (objectId != null) 1u else 0u
            request.object_id.fill(objectId ?: ByteArray(ID_LENGTH))
            handleCall(outScan) { out ->
                chur_integrity_scan_begin(session.toULong(), request.ptr, out)
            }
        }

    actual fun operationPoll(operation: Long, outCounts: LongArray, outStates: IntArray): Int =
        memScoped {
            val progress = alloc<ChurProgressV1>()
            val status = chur_operation_poll(operation.toULong(), progress.ptr)
            if (status == 0) {
                outCounts[0] = progress.processed.toLong()
                outCounts[1] = progress.total.toLong()
                outStates[0] = progress.kind.toInt()
                outStates[1] = progress.stage.toInt()
                outStates[2] = progress.terminal.toInt()
                outStates[3] = progress.status
            }
            status
        }

    actual fun operationCancel(operation: Long): Int = chur_operation_cancel(operation.toULong())

    actual fun operationClose(operation: Long): Int = chur_operation_close(operation.toULong())

    // -----------------------------------------------------------------------
    // Object reader
    // -----------------------------------------------------------------------

    actual fun objectReaderOpen(
        session: Long,
        objectId: ByteArray,
        streamKind: Int,
        outReader: LongArray,
    ): Int = memScoped {
        val reference = objectReference(objectId)
        handleCall(outReader) { out ->
            chur_object_reader_open(session.toULong(), reference.ptr, streamKind.toUInt(), out)
        }
    }

    actual fun objectReaderSize(reader: Long, outSize: LongArray): Int = memScoped {
        val size = alloc<ULongVar>()
        val status = chur_object_reader_size(reader.toULong(), size.ptr)
        if (status == 0) outSize[0] = size.value.toLong()
        status
    }

    actual fun objectReaderContentInfo(
        reader: Long,
        outNumbers: LongArray,
        outContentType: ByteArray,
    ): Int = memScoped {
        val info = alloc<ChurContentInfoV1>()
        val status = chur_object_reader_content_info(reader.toULong(), info.ptr)
        if (status == 0) {
            outNumbers[0] = info.plaintext_size.toLong()
            outNumbers[1] = info.media_kind.toLong()
            outNumbers[2] = info.byte_range_supported.toLong()
            outNumbers[3] = info.complete.toLong()
            for (index in outContentType.indices) {
                outContentType[index] =
                    if (index < CONTENT_TYPE_LENGTH) info.content_type[index].toByte() else 0
            }
        }
        status
    }

    actual fun objectReaderReadAt(
        reader: Long,
        offset: Long,
        destination: ChurBuffer,
        outWritten: IntArray,
    ): Int = memScoped {
        writtenCall(outWritten) { written ->
            chur_object_reader_read_at(
                reader.toULong(),
                offset.toULong(),
                destination.pointer,
                destination.size.toULong(),
                written,
            )
        }
    }

    actual fun objectReaderVerifyComplete(reader: Long, outState: IntArray): Int = memScoped {
        val state = alloc<UIntVar>()
        val status = chur_object_reader_verify_complete(reader.toULong(), state.ptr)
        if (status == 0) outState[0] = state.value.toInt()
        status
    }

    actual fun objectReaderClose(reader: Long): Int = chur_object_reader_close(reader.toULong())

    // -----------------------------------------------------------------------
    // The §6.5 product surface
    // -----------------------------------------------------------------------

    actual fun vaultAddRecoverySlot(
        session: Long,
        destination: ChurBuffer,
        outWritten: IntArray,
    ): Int = memScoped {
        writtenCall(outWritten) { written ->
            chur_vault_add_recovery_slot(
                session.toULong(),
                destination.pointer,
                destination.size.toULong(),
                written,
            )
        }
    }

    actual fun vaultAddDeviceSlot(session: Long, itemId: ByteArray, outSecret: ByteArray): Int =
        itemId.pinnedPointer { item ->
            secretCall(outSecret) { pointer ->
                chur_vault_add_device_slot(session.toULong(), item, pointer)
            }
        }

    actual fun vaultRemoveSlot(session: Long, slotId: ByteArray): Int =
        slotId.pinnedPointer { pointer -> chur_vault_remove_slot(session.toULong(), pointer) }

    actual fun vaultChangePassword(session: Long, password: ByteArray): Int = memScoped {
        password.pinnedPointer { pointer ->
            val request = alloc<ChurUnlockRequestV1>()
            request.factor = 1u
            request.secret = pointer
            request.secret_length = password.size.toUInt()
            chur_vault_change_password(session.toULong(), request.ptr)
        }
    }

    actual fun vaultSlots(session: Long, destination: ChurBuffer, outWritten: IntArray): Int =
        memScoped {
            writtenCall(outWritten) { written ->
                chur_vault_slots(
                    session.toULong(),
                    destination.pointer,
                    destination.size.toULong(),
                    written,
                )
            }
        }

    actual fun objectSetFavorite(session: Long, objectId: ByteArray, favorite: Boolean): Int =
        memScoped {
            val reference = objectReference(objectId)
            chur_object_set_favorite(
                session.toULong(),
                reference.ptr,
                if (favorite) 1u else 0u,
            )
        }

    actual fun objectDelete(session: Long, objectId: ByteArray): Int = memScoped {
        val reference = objectReference(objectId)
        chur_object_delete(session.toULong(), reference.ptr)
    }

    actual fun objectMetadata(
        session: Long,
        objectId: ByteArray,
        destination: ChurBuffer,
        outWritten: IntArray,
    ): Int = memScoped {
        val reference = objectReference(objectId)
        writtenCall(outWritten) { written ->
            chur_object_metadata(
                session.toULong(),
                reference.ptr,
                destination.pointer,
                destination.size.toULong(),
                written,
            )
        }
    }

    actual fun albumCreate(session: Long, name: String, outAlbumId: ByteArray): Int {
        val bytes = name.encodeToByteArray()
        return bytes.pinnedPointer { pointer ->
            identifierCall(outAlbumId) { out ->
                chur_album_create(session.toULong(), pointer, bytes.size.toUInt(), out)
            }
        }
    }

    actual fun albumSetMembership(
        session: Long,
        albumId: ByteArray,
        objectId: ByteArray,
        member: Boolean,
    ): Int = memScoped {
        val reference = objectReference(objectId)
        albumId.pinnedPointer { album ->
            chur_album_set_membership(
                session.toULong(),
                album,
                reference.ptr,
                if (member) 1u else 0u,
            )
        }
    }

    actual fun albumList(session: Long, destination: ChurBuffer, outWritten: IntArray): Int =
        memScoped {
            writtenCall(outWritten) { written ->
                chur_album_list(
                    session.toULong(),
                    destination.pointer,
                    destination.size.toULong(),
                    written,
                )
            }
        }

    actual fun tagCreate(session: Long, name: String, outTagId: ByteArray): Int {
        val bytes = name.encodeToByteArray()
        return bytes.pinnedPointer { pointer ->
            identifierCall(outTagId) { out ->
                chur_tag_create(session.toULong(), pointer, bytes.size.toUInt(), out)
            }
        }
    }

    actual fun objectSetTag(
        session: Long,
        tagId: ByteArray,
        objectId: ByteArray,
        tagged: Boolean,
    ): Int = memScoped {
        val reference = objectReference(objectId)
        tagId.pinnedPointer { tag ->
            chur_object_set_tag(session.toULong(), tag, reference.ptr, if (tagged) 1u else 0u)
        }
    }

    actual fun derivedPut(
        session: Long,
        objectId: ByteArray,
        kind: Int,
        width: Int,
        height: Int,
        source: ChurBuffer,
        length: Int,
    ): Int = memScoped {
        val reference = objectReference(objectId)
        chur_derived_put(
            session.toULong(),
            reference.ptr,
            kind.toUInt(),
            width.toUInt(),
            height.toUInt(),
            source.pointer,
            length.toUInt(),
        )
    }

    actual fun derivedRead(
        session: Long,
        objectId: ByteArray,
        kind: Int,
        destination: ChurBuffer,
        outWritten: IntArray,
    ): Int = memScoped {
        val reference = objectReference(objectId)
        writtenCall(outWritten) { written ->
            chur_derived_read(
                session.toULong(),
                reference.ptr,
                kind.toUInt(),
                destination.pointer,
                destination.size.toULong(),
                written,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Conversions
    // -----------------------------------------------------------------------

    /** The C array length of `ChurContentInfoV1.content_type`. */
    private const val CONTENT_TYPE_LENGTH = 64

    /**
     * Pins a `ByteArray` and gives its address, or null for an empty array.
     *
     * `addressOf(0)` refuses an empty array, and a length of zero with a null
     * pointer is what the boundary already accepts, so null is the honest
     * translation rather than a workaround.
     */
    private inline fun <T> ByteArray.pinnedPointer(body: (CPointer<UByteVar>?) -> T): T =
        if (isEmpty()) {
            body(null)
        } else {
            usePinned { pinned -> body(pinned.addressOf(0).reinterpret()) }
        }

    /** Writes a fixed-size C array inside a structure, element by element. */
    private fun CPointer<UByteVar>.fill(bytes: ByteArray) {
        for (index in bytes.indices) {
            this[index] = bytes[index].toUByte()
        }
    }

    /** Builds a `ChurObjectRefV1` in the enclosing scope. */
    private fun MemScope.objectReference(objectId: ByteArray): ChurObjectRefV1 {
        val reference = alloc<ChurObjectRefV1>()
        reference.object_id.fill(objectId)
        return reference
    }

    /** Runs a call whose result is a handle. */
    private inline fun MemScope.handleCall(
        out: LongArray,
        body: (CPointer<chur_handle_tVar>) -> Int,
    ): Int {
        val handle = alloc<chur_handle_tVar>()
        val status = body(handle.ptr)
        if (status == 0) out[0] = handle.value.toLong()
        return status
    }

    /** Runs a call whose result is a byte count, set on every call. */
    private inline fun MemScope.writtenCall(
        out: IntArray,
        body: (CPointer<ULongVar>) -> Int,
    ): Int {
        val written = alloc<ULongVar>()
        val status = body(written.ptr)
        out[0] = written.value.toInt()
        return status
    }

    /** Runs a call whose result is a 32-byte secret, and clears the native copy. */
    private inline fun secretCall(out: ByteArray, body: (CPointer<UByteVar>) -> Int): Int =
        memScoped {
            val secret = allocArray<UByteVar>(SECRET_LENGTH)
            val status = body(secret)
            if (status == 0) {
                for (index in 0 until SECRET_LENGTH) {
                    out[index] = secret[index].toByte()
                }
            }
            for (index in 0 until SECRET_LENGTH) {
                secret[index] = 0u
            }
            status
        }

    /** Runs a call whose result is a 16-byte identifier. */
    private inline fun identifierCall(out: ByteArray, body: (CPointer<UByteVar>) -> Int): Int =
        memScoped {
            val value = allocArray<UByteVar>(ID_LENGTH)
            val status = body(value)
            if (status == 0) {
                for (index in 0 until ID_LENGTH) {
                    out[index] = value[index].toByte()
                }
            }
            status
        }
}
