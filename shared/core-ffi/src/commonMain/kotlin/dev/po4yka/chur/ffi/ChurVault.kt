package dev.po4yka.chur.ffi

import dev.po4yka.chur.core.model.ChurStatus

/**
 * The vault as a feature calls it.
 *
 * [ChurNative] is the C ABI transliterated; this is the half with Kotlin types,
 * exceptions instead of status codes, and out-parameters turned into return
 * values. Everything below is a thin composition over one export, so a reader
 * comparing this file with `docs/interop/FFI_CONTRACT.md` §6.2 and §6.5 sees
 * the same list.
 *
 * Nothing here is private state. A handle is an opaque `Long` the registry of
 * §3 owns, and this file holds no secret between calls: a recovery secret is
 * returned to the caller that asked for it and never kept.
 *
 * Calls block. §8 makes every native call synchronous, and the caller wraps
 * them on an I/O dispatcher.
 */
object ChurVault {

    // -----------------------------------------------------------------------
    // Handshake, §2
    // -----------------------------------------------------------------------

    /** The ABI the loaded library answers. */
    fun handshake(): NativeHandshake = NativeHandshake(
        major = ChurNative.abiVersionMajor(),
        minor = ChurNative.abiVersionMinor(),
        capabilities = ChurNative.capabilities(),
        objectFormatMin = ChurNative.objectFormatMin(),
        objectFormatMax = ChurNative.objectFormatMax(),
        keySlotFormatMin = ChurNative.keySlotFormatMin(),
        keySlotFormatMax = ChurNative.keySlotFormatMax(),
        buildFlavor = ChurNative.buildFlavor(),
    )

    /** Whether a status value is one this build allocates. */
    fun statusIsKnown(value: Int): Boolean = ChurNative.statusIsKnown(value)

    // -----------------------------------------------------------------------
    // Runtime and session
    // -----------------------------------------------------------------------

    /** Opens the one runtime of §14 over a storage root. */
    fun openRuntime(root: String): Long = handleOf("runtime open") { out ->
        ChurNative.runtimeOpen(root, out)
    }

    /** Closes the runtime and every handle it owns. */
    fun closeRuntime(runtime: Long) {
        ChurFailure.check(ChurNative.runtimeClose(runtime), "runtime close")
    }

    /** Whether the storage root holds a vault, `PROVISIONING.md` §2. */
    fun vaultPresent(runtime: Long): Boolean {
        val out = ByteArray(1)
        ChurFailure.check(ChurNative.vaultPresent(runtime, out), "vault present")
        return out[0].toInt() == 1
    }

    /** Begins vault creation, `PROVISIONING.md` §3 steps 3 and 4. */
    fun beginCreation(runtime: Long, password: ByteArray): Long =
        handleOf("vault create") { out ->
            ChurNative.vaultCreateBegin(runtime, password, 0, 0, 0, out)
        }

    /**
     * Offers the recovery slot, step 5, and returns the phrase.
     *
     * The phrase is the caller's to present and clear. `RECOVERY.md` §2 shows
     * it exactly once, and nothing here keeps a copy: a user who loses it
     * rotates the slot under §8 there rather than asking for it again.
     */
    fun creationAddRecoverySlot(creation: Long): String = phraseOf("creation recovery slot") {
        buffer, written ->
        ChurNative.vaultCreationAddRecoverySlot(creation, buffer, written)
    }

    /** Reaches `ACTIVE` and opens the session, step 6. */
    fun activateCreation(creation: Long): Long = handleOf("vault activate") { out ->
        ChurNative.vaultCreationActivate(creation, out)
    }

    /** Abandons a creation, leaving nothing openable. */
    fun abandonCreation(creation: Long) {
        ChurFailure.check(ChurNative.vaultCreationAbandon(creation), "vault abandon")
    }

    /** Unlocks with a password, `KEY_SLOTS.md` §8. */
    fun unlockWithPassword(runtime: Long, password: ByteArray): Long =
        handleOf("unlock") { out -> ChurNative.vaultUnlock(runtime, FACTOR_PASSWORD, password, out) }

    /** Unlocks with a recovery phrase. */
    fun unlockWithRecovery(runtime: Long, phrase: String): Long =
        handleOf("recover") { out ->
            ChurNative.vaultUnlock(runtime, FACTOR_RECOVERY, phrase.encodeToByteArray(), out)
        }

    /** Unlocks with a `DeviceUnlockSecret` the platform keystore returned. */
    fun unlockWithDeviceSecret(runtime: Long, secret: ByteArray): Long =
        handleOf("device unlock") { out ->
            ChurNative.vaultUnlock(runtime, FACTOR_DEVICE, secret, out)
        }

    /** Locks a session, `PLAINTEXT_LIFECYCLE.md` §8. */
    fun lock(session: Long, reason: LockReason) {
        ChurFailure.check(ChurNative.vaultLock(session, reason.code), "lock")
    }

    /** Closes a session handle. */
    fun closeSession(session: Long) {
        ChurFailure.check(ChurNative.sessionClose(session), "session close")
    }

    /** Provisions or returns this device's public collection-sharing identity. */
    fun sharingIdentity(session: Long): SharingIdentity =
        withChurBuffer(SHARING_IDENTITY_CAPACITY) { buffer ->
            val written = IntArray(1)
            ChurFailure.check(
                ChurNative.sharingIdentity(session, buffer, written),
                "sharing identity",
            )
            decodeSharingIdentity(buffer.copyOut(written[0]), written[0])
        }

    /** Stages one opaque downloaded record without opening vault keys. */
    fun stageSync(
        runtime: Long,
        vaultId: ByteArray,
        kind: SyncRecordKind,
        stagedAtMs: Long,
        record: ByteArray,
    ) {
        withChurBuffer(record.size) { buffer ->
            buffer.copyIn(record)
            ChurFailure.check(
                ChurNative.syncStage(runtime, vaultId, kind.code, stagedAtMs, buffer, record.size),
                "sync stage",
            )
        }
    }

    /** Validates and applies the retained inbox after unlock. */
    fun processSync(session: Long, nowMs: Long): SyncProcessReport {
        val counts = LongArray(4)
        val status = IntArray(1)
        ChurFailure.check(ChurNative.syncProcess(session, nowMs, counts, status), "sync process")
        return SyncProcessReport(counts[0], counts[1], counts[2], counts[3], status[0])
    }

    // -----------------------------------------------------------------------
    // Key slots
    // -----------------------------------------------------------------------

    /** Adds a recovery slot to an active vault and returns the phrase, §8. */
    fun addRecoverySlot(session: Long): String = phraseOf("add recovery slot") { buffer, written ->
        ChurNative.vaultAddRecoverySlot(session, buffer, written)
    }

    /** Adds the Apple Keychain slot, `KEY_SLOTS.md` §5. */
    fun addDeviceSlot(session: Long, keychainItemId: ByteArray): ByteArray =
        secretOf("add device slot") { out ->
            ChurNative.vaultAddDeviceSlot(session, keychainItemId, out)
        }

    /**
     * Begins the Android Keystore enrollment, `KEY_SLOTS.md` §4.
     *
     * The result carries the vault root, because the Keystore performs the
     * AEAD. The caller wraps it, calls [commitKeystoreSlot], and clears the
     * array; ADR-0041 records why the exception exists.
     */
    fun beginKeystoreSlot(session: Long): KeystoreEnrollment =
        withChurBuffer(KEYSTORE_ENROLLMENT_CAPACITY) { buffer ->
            val written = IntArray(1)
            ChurFailure.check(
                ChurNative.vaultKeystoreBegin(session, buffer, written),
                "keystore begin",
            )
            decodeKeystoreEnrollment(buffer.copyOut(written[0]), written[0])
        }

    /** Stores what the Keystore wrap returned, completing the slot. */
    fun commitKeystoreSlot(session: Long, gcmNonce: ByteArray, wrappedRootSecret: ByteArray) {
        ChurFailure.check(
            ChurNative.vaultKeystoreCommit(session, gcmNonce, wrappedRootSecret),
            "keystore commit",
        )
    }

    /** What every enrolled Keystore slot needs for its unwrap, on a locked runtime. */
    fun keystoreMaterial(runtime: Long): List<KeystoreMaterial> =
        withChurBuffer(KEYSTORE_MATERIAL_CAPACITY) { buffer ->
            val written = IntArray(1)
            ChurFailure.check(
                ChurNative.vaultKeystoreMaterial(runtime, buffer, written),
                "keystore material",
            )
            decodeKeystoreMaterial(buffer.copyOut(written[0]), written[0])
        }

    /** Unlocks with the root an Android Keystore unwrap returned. */
    fun unlockWithKeystoreRoot(runtime: Long, rootSecret: ByteArray): Long =
        handleOf("keystore unlock") { out ->
            ChurNative.vaultUnlock(runtime, FACTOR_KEYSTORE, rootSecret, out)
        }

    /** Removes one slot, `KEY_SLOTS.md` §9. */
    fun removeSlot(session: Long, slotId: ByteArray) {
        ChurFailure.check(ChurNative.vaultRemoveSlot(session, slotId), "remove slot")
    }

    /** Replaces the password slot. */
    fun changePassword(session: Long, password: ByteArray) {
        ChurFailure.check(ChurNative.vaultChangePassword(session, password), "change password")
    }

    /** The slots this vault carries. */
    fun slots(session: Long): List<SlotSummary> = withChurBuffer(SLOT_LIST_CAPACITY) { buffer ->
        val written = IntArray(1)
        ChurFailure.check(ChurNative.vaultSlots(session, buffer, written), "slots")
        decodeSlotList(buffer.copyOut(written[0]), written[0])
    }

    // -----------------------------------------------------------------------
    // Library
    // -----------------------------------------------------------------------

    /** Reads one page of a scope, `CATALOG_SCHEMA_V1.md` §16.2. */
    fun query(session: Long, query: ObjectQuery): ObjectPage {
        val limit = if (query.limit == 0) DEFAULT_PAGE_LIMIT else query.limit
        val capacity = PAGE_HEADER_LENGTH + PROJECTION_LENGTH * limit
        return withChurBuffer(capacity) { buffer ->
            val written = IntArray(1)
            ChurFailure.check(
                ChurNative.catalogQuery(
                    session,
                    query.scope.code,
                    query.sort.code,
                    query.kinds,
                    query.limit,
                    query.scopeId ?: ByteArray(ID_LENGTH),
                    query.cursor,
                    query.terms?.encodeToByteArray(),
                    buffer,
                    written,
                ),
                "catalog query",
            )
            decodeObjectPage(buffer.copyOut(written[0]), written[0])
        }
    }

    /** One object's detail record, §6.5. */
    fun detail(session: Long, objectId: ByteArray): ObjectDetail =
        withChurBuffer(DETAIL_CAPACITY) { buffer ->
            val written = IntArray(1)
            ChurFailure.check(
                ChurNative.objectMetadata(session, objectId, buffer, written),
                "object metadata",
            )
            decodeObjectDetail(buffer.copyOut(written[0]), written[0])
        }

    /** Sets or clears the favourite flag. */
    fun setFavorite(session: Long, objectId: ByteArray, favorite: Boolean) {
        ChurFailure.check(
            ChurNative.objectSetFavorite(session, objectId, favorite),
            "set favourite",
        )
    }

    /** Deletes an object, `CATALOG_SCHEMA_V1.md` §14.1. */
    fun deleteObject(session: Long, objectId: ByteArray) {
        ChurFailure.check(ChurNative.objectDelete(session, objectId), "delete object")
    }

    /** Creates an album and returns its identifier. */
    fun createAlbum(session: Long, name: String): ByteArray {
        val out = ByteArray(ID_LENGTH)
        ChurFailure.check(ChurNative.albumCreate(session, name, out), "create album")
        return out
    }

    /** Adds or removes one album membership. */
    fun setAlbumMembership(session: Long, albumId: ByteArray, objectId: ByteArray, member: Boolean) {
        ChurFailure.check(
            ChurNative.albumSetMembership(session, albumId, objectId, member),
            "album membership",
        )
    }

    /** Every album, with its membership count. */
    fun albums(session: Long): List<AlbumSummary> = withChurBuffer(ALBUM_LIST_CAPACITY) { buffer ->
        val written = IntArray(1)
        ChurFailure.check(ChurNative.albumList(session, buffer, written), "album list")
        decodeAlbumList(buffer.copyOut(written[0]), written[0])
    }

    /** Creates a tag and returns its identifier. */
    fun createTag(session: Long, name: String): ByteArray {
        val out = ByteArray(ID_LENGTH)
        ChurFailure.check(ChurNative.tagCreate(session, name, out), "create tag")
        return out
    }

    /** Applies or removes one tag on one object. */
    fun setObjectTag(session: Long, tagId: ByteArray, objectId: ByteArray, tagged: Boolean) {
        ChurFailure.check(ChurNative.objectSetTag(session, tagId, objectId, tagged), "set tag")
    }

    // -----------------------------------------------------------------------
    // Derived assets and reading
    // -----------------------------------------------------------------------

    /** Encrypts and records one derived asset the platform produced. */
    fun putDerived(
        session: Long,
        objectId: ByteArray,
        kind: StreamKind,
        width: Int,
        height: Int,
        bytes: ByteArray,
    ) {
        withChurBuffer(bytes.size) { buffer ->
            buffer.copyIn(bytes)
            ChurFailure.check(
                ChurNative.derivedPut(
                    session,
                    objectId,
                    kind.code,
                    width,
                    height,
                    buffer,
                    bytes.size,
                ),
                "put derived asset",
            )
        }
    }

    /** Reads one derived asset, which the timeline does for every visible row. */
    fun readDerived(session: Long, objectId: ByteArray, kind: StreamKind): ByteArray =
        withChurBuffer(DERIVED_CAPACITY) { buffer ->
            val written = IntArray(1)
            ChurFailure.check(
                ChurNative.derivedRead(session, objectId, kind.code, buffer, written),
                "read derived asset",
            )
            buffer.copyOut(written[0])
        }

    /** Opens a random-access reader on one stream. */
    fun openReader(session: Long, objectId: ByteArray, kind: StreamKind): Long =
        handleOf("open reader") { out ->
            ChurNative.objectReaderOpen(session, objectId, kind.code, out)
        }

    /** The authenticated plaintext size. */
    fun readerSize(reader: Long): Long {
        val out = LongArray(1)
        ChurFailure.check(ChurNative.objectReaderSize(reader, out), "reader size")
        return out[0]
    }

    /** The content information a player needs before its first range request. */
    fun readerContentInfo(reader: Long): ContentInfo {
        val numbers = LongArray(4)
        val contentType = ByteArray(64)
        ChurFailure.check(
            ChurNative.objectReaderContentInfo(reader, numbers, contentType),
            "content info",
        )
        val terminator = contentType.indexOfFirst { it.toInt() == 0 }
        val length = if (terminator < 0) contentType.size else terminator
        return ContentInfo(
            plaintextSize = numbers[0],
            mediaKind = numbers[1].toInt(),
            byteRangeSupported = numbers[2] == 1L,
            complete = numbers[3] == 1L,
            contentType = contentType.decodeToString(0, length),
        )
    }

    /**
     * Reads a range, looping until it has the bytes or reaches the end.
     *
     * §6.3 permits a short read at any offset, so the loop is the contract
     * rather than a precaution: a caller that read once and trusted the count
     * would silently truncate.
     */
    fun readRange(reader: Long, offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        withChurBuffer(length) { buffer ->
            val written = IntArray(1)
            var at = 0
            while (at < length) {
                ChurFailure.check(
                    ChurNative.objectReaderReadAt(reader, offset + at, buffer, written),
                    "read at",
                )
                if (written[0] == 0) break
                // The reader fills up to the buffer's capacity, which is the
                // whole range rather than what is left of it, so a read that
                // starts part way through returns more than the caller still
                // needs. Taking the surplus would run off the end of `out`; the
                // next iteration re-reads from the authenticated container, so
                // dropping it costs one chunk decrypt on the last step only.
                val take = minOf(written[0], length - at)
                buffer.copyOut(take).copyInto(out, at)
                at += take
            }
            if (at < length) {
                throw ChurFailure(ChurStatus.OBJECT_INCOMPLETE, "the range ended early")
            }
        }
        return out
    }

    /** Runs complete verification and returns the state it reached. */
    fun verifyComplete(reader: Long): Int {
        val out = IntArray(1)
        ChurFailure.check(ChurNative.objectReaderVerifyComplete(reader, out), "verify")
        return out[0]
    }

    /** Closes a reader handle. */
    fun closeReader(reader: Long) {
        ChurFailure.check(ChurNative.objectReaderClose(reader), "reader close")
    }

    // -----------------------------------------------------------------------
    // Operations
    // -----------------------------------------------------------------------

    /** Starts an import from a file descriptor the platform opened. */
    fun beginImport(session: Long, sourceFd: Int, request: ImportRequest): Long =
        handleOf("import begin") { out ->
            ChurNative.importBegin(
                session,
                sourceFd,
                request.mediaClass,
                request.width,
                request.height,
                request.durationMs,
                request.knownLength ?: -1L,
                request.captureTimeMs ?: -1L,
                request.contentType,
                request.originalFilename,
                out,
            )
        }

    /** Starts an export to a file descriptor the platform opened. */
    fun beginExport(session: Long, objectId: ByteArray, destinationFd: Int): Long =
        handleOf("export begin") { out ->
            ChurNative.exportBegin(session, objectId, destinationFd, out)
        }

    /**
     * Starts writing a backup package to a descriptor, `FFI_CONTRACT.md` §6.7.
     *
     * The descriptor must be writable and seekable:
     * `docs/format/BACKUP_FORMAT_V1.md` §7 writes the public preamble before
     * the records and learns the record count only after the inventory pass, so
     * a pipe is not a destination. An application that uploads a package writes
     * it to a file and uploads that.
     */
    fun beginBackup(session: Long, destinationFd: Int): Long =
        handleOf("backup create") { out ->
            ChurNative.backupCreate(session, destinationFd, out)
        }

    /**
     * Starts restoring a backup package, §6.7.
     *
     * It takes the runtime rather than a session, because a restore installs an
     * identity: at the moment it runs there may be no session and no vault at
     * all, and §8 obtains the credential from the package's own portable
     * descriptor.
     */
    fun beginRestore(runtime: Long, sourceFd: Int, password: ByteArray): Long =
        handleOf("backup restore") { out ->
            ChurNative.backupRestore(runtime, sourceFd, password, out)
        }

    /** Starts an integrity scan; a null identifier scans every object. */
    fun beginIntegrityScan(session: Long, objectId: ByteArray?): Long =
        handleOf("scan begin") { out ->
            ChurNative.integrityScanBegin(session, objectId, out)
        }

    /** One progress snapshot, §10. */
    fun poll(operation: Long): OperationProgress {
        val counts = LongArray(2)
        val states = IntArray(4)
        ChurFailure.check(ChurNative.operationPoll(operation, counts, states), "poll")
        return OperationProgress(
            processed = counts[0],
            total = counts[1],
            kind = states[0],
            stage = states[1],
            terminal = states[2] == 1,
            status = states[3],
        )
    }

    /** Asks an operation to stop, §9. */
    fun cancel(operation: Long) {
        ChurFailure.check(ChurNative.operationCancel(operation), "cancel")
    }

    /** Closes an operation handle, waiting for its worker. */
    fun closeOperation(operation: Long) {
        ChurFailure.check(ChurNative.operationClose(operation), "operation close")
    }

    // -----------------------------------------------------------------------

    private inline fun handleOf(where: String, body: (LongArray) -> Int): Long {
        val out = LongArray(1)
        ChurFailure.check(body(out), where)
        return out[0]
    }

    /**
     * Runs a call whose result is a recovery phrase.
     *
     * The buffer is cleared before it is released, so the phrase exists in
     * native memory only for the call and in the returned string until the
     * caller drops it.
     */
    private inline fun phraseOf(where: String, body: (ChurBuffer, IntArray) -> Int): String =
        withChurBuffer(RECOVERY_PHRASE_MAX) { buffer ->
            val written = IntArray(1)
            ChurFailure.check(body(buffer, written), where)
            buffer.copyOut(written[0]).decodeToString()
        }

    private inline fun secretOf(where: String, body: (ByteArray) -> Int): ByteArray {
        val out = ByteArray(SECRET_LENGTH)
        val code = body(out)
        if (code != 0) {
            out.fill(0)
            ChurFailure.check(code, where)
        }
        return out
    }

    /** `CHUR_FACTOR_PASSWORD`. */
    private const val FACTOR_PASSWORD = 1

    /** `CHUR_FACTOR_RECOVERY`. */
    private const val FACTOR_RECOVERY = 2

    /** `CHUR_FACTOR_APPLE_KEYCHAIN`. */
    private const val FACTOR_DEVICE = 3

    /** The Android Keystore factor, whose secret is the unwrapped root. */
    private const val FACTOR_KEYSTORE = 4

    /** A length-prefixed alias and AAD and a 32-byte root, §6.6. */
    private const val KEYSTORE_ENROLLMENT_CAPACITY = 4 + 64 + 4 + 160 + 32

    /** A count and two entries, which is every identity the registry admits. */
    private const val KEYSTORE_MATERIAL_CAPACITY = 4 + 2 * (4 + 64 + 4 + 160 + 12 + 48)

    /** The bounded public identity, enrollment, and initial operation record. */
    private const val SHARING_IDENTITY_CAPACITY = 4 * 1024

    /** The default page size of §16.2. */
    private const val DEFAULT_PAGE_LIMIT = 200

    /** Sixteen slots at 25 bytes each plus the count, §13 of the descriptor. */
    private const val SLOT_LIST_CAPACITY = 4 + 16 * 25

    /** Ten thousand albums is the §21 bound; the buffer holds a realistic page
     *  of them and a longer list is `RESOURCE_LIMIT_EXCEEDED`, which the caller
     *  surfaces rather than truncating. */
    private const val ALBUM_LIST_CAPACITY = 64 * 1024

    /** A detail record is bounded by §12's metadata bounds. */
    private const val DETAIL_CAPACITY = 96 * 1024

    /** A screen preview at 2048 px is the largest derivative §12 allows. */
    private const val DERIVED_CAPACITY = 8 * 1024 * 1024
}

/** Opaque record families accepted by the locked sync inbox. */
enum class SyncRecordKind(internal val code: Int) {
    OPERATION(1),
    CHECKPOINT(2),
}

/** Counts from one unlocked inbox pass. */
data class SyncProcessReport(
    val applied: Long,
    val duplicates: Long,
    val pending: Long,
    val rejected: Long,
    val firstRejection: Int,
)

/** The handshake facts of §2. */
data class NativeHandshake(
    val major: Int,
    val minor: Int,
    val capabilities: Long,
    val objectFormatMin: Int,
    val objectFormatMax: Int,
    val keySlotFormatMin: Int,
    val keySlotFormatMax: Int,
    val buildFlavor: Int,
)

/** The content information of §6.1. */
data class ContentInfo(
    val plaintextSize: Long,
    val contentType: String,
    val mediaKind: Int,
    val byteRangeSupported: Boolean,
    val complete: Boolean,
)

/** One progress snapshot, §10. */
data class OperationProgress(
    val processed: Long,
    val total: Long,
    val kind: Int,
    val stage: Int,
    val terminal: Boolean,
    val status: Int,
)

/** The reason a session locked, which reaches no private state. */
enum class LockReason(val code: Int) {
    /** The user asked. */
    USER(1),

    /** The idle timer expired. */
    TIMEOUT(2),

    /** The application went to the background. */
    BACKGROUND(3),

    /** The panic gesture, `DESIGN.md` §14. */
    PANIC(4),
}

/** The query scopes of §16.2. */
enum class QueryScope(val code: Int) {
    /** Every listable object. */
    TIMELINE(1),

    /** The members of one album. */
    ALBUM(2),

    /** Every favourite. */
    FAVORITES(3),

    /** Every object carrying one tag. */
    TAG(4),

    /** The FTS5 query of §16.4. */
    SEARCH(5),

    /** The objects §16.2 keeps out of the ordinary library. */
    QUARANTINE(6),
}

/** The sorts of §16.2. */
enum class QuerySort(val code: Int) {
    /** Capture time descending, the default. */
    CAPTURE_DESC(1),

    /** Capture time ascending. */
    CAPTURE_ASC(2),

    /** Import time descending. */
    IMPORT_DESC(3),
}

/** The stream kinds of `CANONICAL_ENCODING_V1.md` §15.4. */
enum class StreamKind(val code: Int) {
    /** The imported bytes as received. */
    ORIGINAL(1),

    /** The small thumbnail the timeline reads. */
    THUMBNAIL(2),

    /** The grid preview. */
    GRID_PREVIEW(3),

    /** The screen preview the viewer reads. */
    SCREEN_PREVIEW(4),

    /** A video poster frame. */
    VIDEO_POSTER(5),

    /**
     * An audio waveform, `docs/interop/MEDIA_PIPELINE.md` §6.1.
     *
     * Unlike every kind above it, this one is not a picture. Its bytes are the
     * peak-envelope record that section defines, which shared code draws.
     */
    AUDIO_WAVEFORM(6),
}

/** One page request. */
data class ObjectQuery(
    val scope: QueryScope = QueryScope.TIMELINE,
    val sort: QuerySort = QuerySort.CAPTURE_DESC,
    val kinds: Int = 0,
    val limit: Int = 0,
    val scopeId: ByteArray? = null,
    val terms: String? = null,
    val cursor: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is ObjectQuery && scope == other.scope && sort == other.sort &&
            kinds == other.kinds && limit == other.limit && terms == other.terms

    override fun hashCode(): Int = scope.hashCode() * 31 + (terms?.hashCode() ?: 0)
}

/** What the platform reports about an import source, `MEDIA_PIPELINE.md` §3. */
data class ImportRequest(
    val contentType: String,
    val mediaClass: Int,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val knownLength: Long? = null,
    val captureTimeMs: Long? = null,
    val originalFilename: String? = null,
)
