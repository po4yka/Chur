package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.ThumbnailCache
import dev.po4yka.chur.app.vault.isValidVaultPin
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.AlbumSummary
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.ffi.LockReason
import dev.po4yka.chur.ffi.ObjectDetail
import dev.po4yka.chur.ffi.ObjectPage
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.ffi.OperationProgress
import dev.po4yka.chur.ffi.SharingIdentity
import dev.po4yka.chur.ffi.SharingMember
import dev.po4yka.chur.ffi.SharingOverview
import dev.po4yka.chur.ffi.SharingPermission
import dev.po4yka.chur.ffi.SharingRecipient
import dev.po4yka.chur.ffi.fromHex
import dev.po4yka.chur.ffi.QueryScope
import dev.po4yka.chur.sync.SyncCoordinator
import dev.po4yka.chur.sync.SyncStatus
import dev.po4yka.chur.ffi.SlotSummary
import dev.po4yka.chur.ffi.StreamKind
import dev.po4yka.chur.ffi.TagSummary
import dev.po4yka.chur.notes.InMemoryNoteStore
import dev.po4yka.chur.notes.Note
import dev.po4yka.chur.notes.NoteStore
import dev.po4yka.chur.vault.LockPolicy
import dev.po4yka.chur.vault.VaultRepository
import dev.po4yka.chur.vault.VaultState
import dev.po4yka.chur.imports.PickedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The application state machine, shared by both hosts.
 *
 * `docs/ARCHITECTURE.md` §9 puts binding in the composition root, and both
 * hosts have one; what they do not have is a reason to write this twice. The
 * two platform-specific pieces are injected: where an export lands, and the
 * privacy cover, because neither is expressible in common code.
 *
 * `docs/interop/FFI_CONTRACT.md` §14 permits one runtime per process, so a host
 * creates one of these. §8 makes every native call synchronous, so every call
 * below moves to the I/O dispatcher and none blocks the main thread.
 */
class ChurController(
    storageRoot: String,
    private val privacy: PrivacyCover,
    private val exports: ExportSink,
    private val deviceUnlock: DeviceUnlock = NoDeviceUnlock,
    private val appleDeviceUnlock: AppleDeviceUnlock = NoAppleDeviceUnlock,
    /**
     * The per-vault device-slot policy of `KEY_SLOTS.md` §1.
     *
     * A host without a device slot keeps the unset default, and the
     * settings row never appears, because [deviceUnlockAvailable] is
     * false with it.
     */
    private val deviceSlotPolicy: DeviceSlotPolicySetting = DeviceSlotPolicySetting.unset(),
    private val appLockSetting: AppLockSetting = AppLockSetting.unset(),
    private val clock: () -> Long,
    private val notes: NoteStore = InMemoryNoteStore(),
    private val policy: LockPolicy = LockPolicy(),
    /**
     * The sync engine, when the host binds one.
     *
     * A host that passes none gets no sync surface at all: the settings
     * section reads a `null` status and renders nothing, so an unbound
     * controller is unchanged rather than half-configured.
     */
    private val sync: SyncCoordinator? = null,
) {
    /**
     * The last net under every coroutine this controller starts.
     *
     * [guarded] wraps a surface action, but a `launch` of its own re-roots the
     * work on [scope], where the guard around the caller cannot see it, and an
     * `Error` escapes the guard as well. Either one reached the platform
     * default handler, which ends the process — the crash `PLAINTEXT_LIFECYCLE`
     * §8 replaces with an orderly lock. `SupervisorJob` does not help here: it
     * stops the cancellation of a sibling, not the report of a failure.
     */
    private val uncaught = CoroutineExceptionHandler { _, _ ->
        _message.value = ChurStatus.INTERNAL_FAILURE.name
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + uncaught)
    private val repository = VaultRepository(storageRoot, clock, policy)

    private val initiallyLockWholeApp = runCatching { appLockSetting.read() }.getOrDefault(false)
    private val _route = MutableStateFlow<AppRoute>(
        if (initiallyLockWholeApp) AppRoute.AppUnlock else AppRoute.PublicShell,
    )
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    private val _disclosureDue = MutableStateFlow(false)
    private val _page = MutableStateFlow(ObjectPage(emptyList(), 0, 0, null))
    private val _albums = MutableStateFlow<List<AlbumSummary>>(emptyList())
    private val _tags = MutableStateFlow<List<TagSummary>>(emptyList())
    private var currentQuery = ObjectQuery()
    private val _slots = MutableStateFlow<List<SlotSummary>>(emptyList())
    private val _sharingIdentity = MutableStateFlow<SharingIdentity?>(null)
    private val _sharingOverview = MutableStateFlow<SharingOverview?>(null)
    private val _sharingRecipient = MutableStateFlow<SharingRecipient?>(null)
    private var recipientEnrollment: ByteArray? = null
    private val _message = MutableStateFlow<String?>(null)
    private val _activeOperation = MutableStateFlow<ActiveOperation?>(null)
    private var operationToken = 0L
    private val _recoveryPhrase = MutableStateFlow<String?>(null)
    private val _deviceUnlockOffered = MutableStateFlow(false)

    /**
     * The decoded-image cache of `PLAINTEXT_LIFECYCLE.md` §4, which the
     * lock transitions below clear.
     *
     * It lives here rather than in a composition because a lock does not
     * wait for a library screen: the background lock fires while the host
     * is stopped, and a cache owned by composition would keep decoded
     * private pixels in exactly the locked process §8 step 7 is about.
     */
    private val thumbnails = ThumbnailCache()

    /** The cache the library renders from, cleared on every lock. */
    val thumbnailCache: ThumbnailCache get() = thumbnails

    private val _deviceSlotStrict = MutableStateFlow(false)
    private val _appLockEnabled = MutableStateFlow(initiallyLockWholeApp)

    /** Whether the entire app is gated on start and return from background. */
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    /** Whether the device slot requires biometry only, `KEY_SLOTS.md` §1. */
    val deviceSlotStrict: StateFlow<Boolean> = _deviceSlotStrict.asStateFlow()

    /** Whether this platform can hold a device slot at all. */
    val deviceUnlockAvailable: Boolean get() = deviceUnlock.available || appleDeviceUnlock.available

    /**
     * How many activities the host launched and is still waiting on.
     *
     * Touched only from the main dispatcher, which is where both the surface
     * callbacks and the lifecycle callbacks run, so it needs no synchronization.
     */
    private var hostActivities = 0
    private var lockEpoch = 0L

    /**
     * Whether [start] has already run.
     *
     * Touched only from the main dispatcher, which is where a host calls
     * [start], so it needs no synchronization. §14 of
     * `docs/interop/FFI_CONTRACT.md` permits one runtime per process, and a
     * host whose window the platform recreated calls [start] again on this
     * same controller.
     */
    private var started = false

    /** Where the application is, `DESIGN.md` §10.3. */
    val route: StateFlow<AppRoute> = _route.asStateFlow()

    /** The vault state machine. */
    val vaultState: StateFlow<VaultState> = repository.state

    /** The public notes. */
    val notesState: StateFlow<List<Note>> = _notes.asStateFlow()

    /** The current library page. */
    val page: StateFlow<ObjectPage> = _page.asStateFlow()

    /** The albums. */
    val albums: StateFlow<List<AlbumSummary>> = _albums.asStateFlow()
    val tags: StateFlow<List<TagSummary>> = _tags.asStateFlow()

    /** The key slots. */
    val slots: StateFlow<List<SlotSummary>> = _slots.asStateFlow()

    val sharingIdentity: StateFlow<SharingIdentity?> = _sharingIdentity.asStateFlow()
    val sharingOverview: StateFlow<SharingOverview?> = _sharingOverview.asStateFlow()
    val sharingRecipient: StateFlow<SharingRecipient?> = _sharingRecipient.asStateFlow()

    /** A bounded message that carries no private value. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The current user-visible import, export, backup, restore, or scan. */
    val activeOperation: StateFlow<ActiveOperation?> = _activeOperation.asStateFlow()

    /** The recovery phrase, held only until the user acknowledges it. */
    val recoveryPhrase: StateFlow<String?> = _recoveryPhrase.asStateFlow()

    /**
     * The sync engine's state, or a constant `null` when no engine is bound.
     *
     * A settings surface renders the section from this: `null` means the host
     * bound no engine, so the section is absent rather than empty, and a
     * non-null status is already bounded by `SyncStatus`'s contract.
     */
    val syncStatus: StateFlow<SyncStatus?> = sync?.status ?: MutableStateFlow(null)

    /**
     * Whether the unlock screen may offer the device slot.
     *
     * It is false unless the platform has one and the vault enrolled it, which
     * `DESIGN.md` §14.1 requires of every affordance on that screen: an offer
     * that appears when no slot exists would say a slot exists.
     */
    val deviceUnlockOffered: StateFlow<Boolean> = _deviceUnlockOffered.asStateFlow()

    /** The repository, for a host flow that drives operations itself. */
    val vault: VaultRepository get() = repository

    /**
     * Opens the runtime, loads the public shell, and starts the idle timer.
     *
     * It runs once. An Android activity is destroyed and recreated for a
     * locale change, a font-scale change, a display-size change and a
     * multi-window resize, and each recreation calls this again. The timer
     * [runIdleTimer] starts lives on [scope], which no host cancels, so a
     * second run would leave a second one-second wakeup over the one session
     * and keep this controller - and the window its cover holds - alive for
     * the life of the process.
     *
     * It runs on [begin] rather than on a scope of the host's, so nothing
     * cancels it part-way. A cancelled start would leave the flag unset with
     * no timer armed, and the next launch would arm a second one.
     */
    suspend fun start() {
        if (started) return
        val initialState = withContext(Dispatchers.Default) { repository.start() }
        if (initialState is VaultState.NoVault && _appLockEnabled.value) {
            withContext(Dispatchers.Default) { appLockSetting.write(false) }
            _appLockEnabled.value = false
            _route.value = AppRoute.PublicShell
        }
        _notes.value = notes.all()
        _deviceSlotStrict.value =
            withContext(Dispatchers.Default) { deviceSlotPolicy.read() }
        refreshDeviceUnlockOffer()
        guarded { runIdleTimer() }
        started = true
    }

    /**
     * [start], on this controller's own scope.
     *
     * A host that launched it on a window-bound scope lost it to the next
     * recreation: an Android activity's `lifecycleScope` is cancelled at
     * `onDestroy`, so a start cancelled at one of its two suspension points
     * armed no idle timer and left `started` unset, and the next window armed
     * a second timer over the one session. This scope has the life of the
     * process, which §14 of `docs/interop/FFI_CONTRACT.md` gives the runtime.
     *
     * [guarded] is the second reason. A refused start used to travel out of an
     * uncaught `launch` and end the process, which is the crash
     * `PLAINTEXT_LIFECYCLE.md` §8 replaces with an orderly lock.
     */
    fun begin() = guarded { start() }

    /**
     * The timer behind the auto-lock choices of `DESIGN.md` §14.4.
     *
     * It lives here rather than in each host because the rule is the same on
     * both and a timer neither host started is the failure this replaces: the
     * idle check existed and nothing called it, so a timed lock never happened.
     *
     * `collectLatest` cancels the loop as soon as the session is not unlocked,
     * so no wakeup runs while there is nothing to lock.
     */
    private suspend fun runIdleTimer() {
        repository.state.collectLatest { current ->
            if (current !is VaultState.Unlocked) return@collectLatest
            while (true) {
                delay(IDLE_TICK_MS)
                checkIdle()
            }
        }
    }

    /** Creates a vault, `PROVISIONING.md` §3. */
    fun create(password: String, offerRecovery: Boolean) = guarded {
        if (password.isNotEmpty() && password.all { it in '0'..'9' } &&
            password.length <= 20 && !isValidVaultPin(password)) {
            _message.value = "Use at least 12 digits for a vault PIN."
            return@guarded
        }
        if (repository.state.value is VaultState.Unlocked) {
            lockEpoch += 1
            _activeOperation.value = null
            exports.cancelPending()
        }
        val bytes = password.encodeToByteArray()
        val phrase = try {
            withContext(Dispatchers.Default) { repository.create(bytes, offerRecovery) }
        } catch (failure: ChurFailure) {
            // One sentence for every reason a creation is refused.
            // `DECOY_VAULT.md` §10 forbids a surface that differs by whether a
            // sibling exists, and the two statuses that reach here differ by
            // exactly that: `RESOURCE_LIMIT_EXCEEDED` means the registry
            // already holds the two identities §11 admits, and `CONFLICT`
            // means this credential already opens one. A message naming either
            // would answer, from inside a decoy session, the question the
            // design refuses to answer. The residual signal that a creation
            // failed at all is structural and is recorded in §5 there.
            _message.value = "This vault could not be created. Try a different credential."
            return@guarded
        } finally {
            bytes.fill(0)
        }
        if (phrase != null) _recoveryPhrase.value = phrase else enterVault()
    }

    /** Acknowledges the phrase, which is the only way past that screen. */
    fun acknowledgeRecoveryPhrase() {
        _recoveryPhrase.value = null
        if (repository.state.value is VaultState.Unlocked) {
            guarded { enterVault() }
        }
    }

    /** Unlocks with a password. */
    fun unlock(password: String) = guarded {
        val target = _route.value
        val epoch = lockEpoch
        val bytes = password.encodeToByteArray()
        try {
            withContext(Dispatchers.Default) { repository.unlock(bytes) }
        } finally {
            bytes.fill(0)
        }
        completeUnlock(target, epoch)
    }

    /** Unlocks with the recovery phrase. */
    fun recover(phrase: String) = guarded {
        val target = _route.value
        val epoch = lockEpoch
        withContext(Dispatchers.Default) { repository.unlockWithRecovery(phrase.trim()) }
        completeUnlock(target, epoch)
    }

    /**
     * Unlocks through the platform device slot, `KEY_SLOTS.md` §4.
     *
     * The platform performs the unwrap and hands back the root, which the
     * repository clears once the session is open. Every enrolled slot is tried
     * in turn, because the material names no identity.
     */
    fun unlockWithDevice() = guarded {
        val target = _route.value
        val epoch = lockEpoch
        val material = withContext(Dispatchers.Default) { repository.keystoreMaterial() }
        // Not `firstNotNullOfOrNull`: the unwrap suspends now, because the
        // platform asks the user to authorize it first.
        var root: ByteArray? = null
        beginHostActivity()
        try {
            withContext(Dispatchers.Default) {
                for (entry in material) {
                    root = deviceUnlock.unwrap(
                        entry.alias,
                        entry.aad,
                        entry.gcmNonce,
                        entry.wrappedRootSecret,
                    )
                    if (root != null) break
                }
            }
        } finally {
            endHostActivity()
        }
        val opened = root ?: throw ChurFailure(ChurStatus.AUTHENTICATION_FAILED, "no device slot opened")
        withContext(Dispatchers.Default) { repository.unlockWithKeystoreRoot(opened) }
        completeUnlock(target, epoch)
    }

    /** Opens the vault with a Keychain secret released by local authorization. */
    fun unlockWithAppleDevice() = guarded {
        val target = _route.value
        val epoch = lockEpoch
        beginHostActivity()
        try {
            withContext(Dispatchers.Default) {
                appleDeviceUnlock.beginUnlock()
                try {
                    var opened = false
                    for (itemId in appleDeviceUnlock.itemIds()) {
                        val secret = appleDeviceUnlock.releaseSecret(itemId)
                        try {
                            try {
                                repository.unlockWithDeviceSecret(secret)
                                opened = true
                                break
                            } catch (failure: ChurFailure) {
                                if (failure.status != ChurStatus.AUTHENTICATION_FAILED) throw failure
                            }
                        } finally {
                            secret.fill(0)
                        }
                    }
                    if (!opened) {
                        throw ChurFailure(ChurStatus.AUTHENTICATION_FAILED, "no device slot opened")
                    }
                } finally {
                    appleDeviceUnlock.endUnlock()
                }
            }
        } finally {
            endHostActivity()
        }
        completeUnlock(target, epoch)
    }

    /**
     * Enrolls the platform device slot on the open session.
     *
     * The enrolment is bracketed the way an import is: authorizing the platform
     * key can put a system credential screen in front of this one, and the
     * background lock would otherwise close the session the enrolment needs.
     */
    fun enrollDeviceSlot() = guarded {
        beginHostActivity()
        try {
            // The only guarded action that used to stay on the main dispatcher,
            // and the one that could least afford to: the enrolment generates a
            // Keystore key, runs an interactive prompt and finishes an AEAD.
            // The prompt itself hops back to the main thread on its own, which
            // is where the platform requires it.
            withContext(Dispatchers.Default) {
                repository.enrollKeystoreSlot { alias, aad, root ->
                    deviceUnlock.wrap(alias, aad, root)
                }
            }
        } finally {
            endHostActivity()
        }
        _message.value = "This device can now open the vault."
        loadSlots()
        refreshDeviceUnlockOffer()
    }

    /** Stores a random Rust device secret under Keychain authorization. */
    fun enrollAppleDeviceSlot() = guarded {
        beginHostActivity()
        try {
            withContext(Dispatchers.Default) {
                val itemId = appleDeviceUnlock.newItemId()
                repository.enrollAppleSlot(itemId) { secret ->
                    appleDeviceUnlock.storeSecret(itemId, secret, deviceSlotPolicy.read())
                }
            }
        } finally {
            endHostActivity()
        }
        _message.value = "This device can now open the vault."
        loadSlots()
        refreshDeviceUnlockOffer()
    }

    /** Recomputes whether the unlock screen may offer the device slot. */
    private fun refreshDeviceUnlockOffer() {
        if (!deviceUnlockAvailable) {
            _deviceUnlockOffered.value = false
            return
        }
        scope.launch {
            _deviceUnlockOffered.value = runCatching {
                withContext(Dispatchers.Default) {
                    if (appleDeviceUnlock.available) {
                        appleDeviceUnlock.itemIds().isNotEmpty()
                    } else {
                        repository.keystoreMaterial().isNotEmpty()
                    }
                }
            }.getOrDefault(false)
        }
    }

    /**
     * Switches the device-slot policy of `KEY_SLOTS.md` §1.
     *
     * §1 shows the choice at device-slot creation, so the next enrollment
     * and the next unlock read what this wrote; a slot enrolled under the
     * other policy keeps the key it has until it is removed.
     */
    fun toggleDeviceSlotPolicy() =
        guarded {
            val next = !_deviceSlotStrict.value
            withContext(Dispatchers.Default) { deviceSlotPolicy.write(next) }
            _deviceSlotStrict.value = next
        }

    /** Selects whether the public shell also needs a vault credential. */
    fun toggleAppLock() = guarded {
        if (repository.state.value !is VaultState.Unlocked) {
            throw ChurFailure(ChurStatus.VAULT_LOCKED, "the vault is locked")
        }
        val next = !_appLockEnabled.value
        withContext(Dispatchers.Default) { appLockSetting.write(next) }
        _appLockEnabled.value = next
    }

    /** Whether the public-shell disclosure is owed to the user right now. */
    val disclosureDue: StateFlow<Boolean> = _disclosureDue.asStateFlow()

    /**
     * Reads one derived asset, or `null` when the object carries none.
     *
     * A derivative that is absent is an ordinary state rather than a failure:
     * `MEDIA_PIPELINE.md` §13 says a codec failure must not commit a catalog
     * entry claiming a derivative exists, so an object imported when the codec
     * could not read it simply has none, and the surface shows what it has.
     */
    suspend fun derivativeOf(objectId: ByteArray, kind: StreamKind): ByteArray? = try {
        withContext(Dispatchers.Default) { repository.readDerived(objectId, kind) }
    } catch (_: ChurFailure) {
        null
    }

    /** Locks now, `DESIGN.md` §14.3. */
    fun lock(reason: LockReason = LockReason.USER) = guarded {
        lockEpoch += 1
        _activeOperation.value = null
        exports.cancelPending()
        withContext(Dispatchers.Default) { repository.lock(reason) }
        privacy.setEnabled(false)
        clearPrivateProjections()
        _route.value = if (_appLockEnabled.value) AppRoute.AppUnlock else AppRoute.PublicShell
    }

    /**
     * Locks immediately, `DISCREET_MODE.md` "The panic gesture".
     *
     * It is the same transition as [lock] and differs in urgency only: the
     * reason reaches Rust as `PANIC`, which the vault records and treats
     * identically. A reason that changed the transition would make panic a
     * different operation, and that section says it is not.
     */
    fun panic() = lock(LockReason.PANIC)

    /**
     * Marks that the host is running an activity it launched itself.
     *
     * The background lock cannot tell the user leaving from the application
     * asking the platform for something: both stop the activity. The media
     * picker of `ANDROID.md` §14.1 is the case that matters, because the whole
     * import path goes through it — the lock fired as the picker came up, and
     * the result arrived at a locked vault, so no import could ever finish.
     *
     * The cover still goes on, so the switcher entry is covered either way.
     * What is suppressed is the lock alone, and only while the host says a
     * launch of its own is outstanding. The idle timer is untouched and stays
     * the backstop for a user who walks away from an open picker.
     */
    fun beginHostActivity() {
        hostActivities += 1
    }

    /** Ends what [beginHostActivity] began, on the result or on a dismissal. */
    fun endHostActivity() {
        if (hostActivities > 0) hostActivities -= 1
    }

    /** The application left the foreground. */
    suspend fun onBackground() {
        privacy.setEnabled(true)
        if (hostActivities > 0) return
        lockEpoch += 1
        if (_appLockEnabled.value || policy.lockOnBackground) {
            _activeOperation.value = null
            exports.cancelPending()
        }
        withContext(Dispatchers.Default) {
            if (_appLockEnabled.value) repository.lock(LockReason.BACKGROUND)
            else repository.onBackground()
        }
        if (repository.state.value !is VaultState.Unlocked) {
            clearPrivateProjections()
            _route.value = if (_appLockEnabled.value) AppRoute.AppUnlock else AppRoute.PublicShell
        }
    }

    /**
     * The same transition, on this controller's own scope.
     *
     * A host whose window is going away cannot carry the lock: an Android
     * activity's `lifecycleScope` is cancelled at `onDestroy`, and a back
     * press runs `onPause` and `onDestroy` in one pass, so the lock the pause
     * started was cancelled at its first suspension point and the session
     * stayed open in a process the platform keeps. This scope is the
     * controller's, which §14 of `docs/interop/FFI_CONTRACT.md` gives the life
     * of the process, so the lock of `PLAINTEXT_LIFECYCLE.md` §8 completes
     * whatever the window does.
     */
    fun background() = guarded { onBackground() }

    /** The idle check of `DESIGN.md` §14.4, which [runIdleTimer] drives. */
    suspend fun checkIdle() {
        if (withContext(Dispatchers.Default) {
            repository.lockIfIdle {
                withContext(NonCancellable + Dispatchers.Main) {
                    lockEpoch += 1
                    _activeOperation.value = null
                    exports.cancelPending()
                }
            }
        }) {
            privacy.setEnabled(false)
            clearPrivateProjections()
            _route.value = if (_appLockEnabled.value) AppRoute.AppUnlock else AppRoute.PublicShell
        }
    }

    /** Moves to a route the public shell offers. */
    fun goTo(next: AppRoute) {
        if (next == AppRoute.Unlock || next == AppRoute.AppUnlock) _message.value = null
        _route.value = next
    }

    /** The route the visible settings entry of §2 leads to. */
    fun openVaultEntry() {
        _message.value = null
        _route.value = if (repository.state.value is VaultState.NoVault) {
            AppRoute.CreateVault
        } else {
            AppRoute.Unlock
        }
    }

    /** Loads one query scope. */
    fun load(query: ObjectQuery) = guarded(clearMessage = false) {
        currentQuery = query
        _page.value = withContext(Dispatchers.Default) { repository.page(query) }
    }

    private var loadingNextPage = false

    /** Appends one bounded page when the user reaches the end of the grid. */
    fun loadNextPage() = guarded(clearMessage = false) {
        if (loadingNextPage) return@guarded
        val previous = _page.value
        val cursor = previous.nextCursor ?: return@guarded
        val query = currentQuery
        loadingNextPage = true
        try {
            val next = withContext(Dispatchers.Default) { repository.page(query.copy(cursor = cursor)) }
            if (currentQuery !== query) return@guarded
            _page.value = if (next.catalogGeneration != previous.catalogGeneration) {
                withContext(Dispatchers.Default) { repository.page(query) }
            } else {
                next.copy(objects = previous.objects + next.objects)
            }
        } finally {
            loadingNextPage = false
        }
    }

    /** Loads the albums. */
    fun loadAlbums() = guarded(clearMessage = false) {
        _albums.value = withContext(Dispatchers.Default) { repository.albums() }
    }

    /** Loads tags only for the unlocked selection picker. */
    fun loadTags() = guarded {
        _tags.value = withContext(Dispatchers.Default) { repository.tags() }
    }

    /** Refreshes the visible projections after a host command changes the vault. */
    suspend fun refreshExternalChanges(albums: Boolean = false, tags: Boolean = false) {
        reload()
        if (albums) refreshAlbums()
        if (tags) _tags.value = withContext(Dispatchers.Default) { repository.tags() }
    }

    /** Loads the key slots. */
    fun loadSlots() = guarded(clearMessage = false) {
        _slots.value = withContext(Dispatchers.Default) { repository.slots() }
    }

    /** Searches, `CATALOG_SCHEMA_V1.md` §16.4. */
    fun search(terms: String) = guarded {
        val query = ObjectQuery(QueryScope.SEARCH, terms = terms)
        currentQuery = query
        _page.value = withContext(Dispatchers.Default) {
            repository.page(query)
        }
    }

    /** Sets or clears the favourite flag. */
    fun setFavorite(objectId: ByteArray, favorite: Boolean) = guarded {
        withContext(Dispatchers.Default) { repository.setFavorite(objectId, favorite) }
        reload()
    }

    /** Deletes an object, `CATALOG_SCHEMA_V1.md` §14.1. */
    fun delete(objectId: ByteArray, onSuccess: () -> Unit = {}) = guarded {
        withContext(Dispatchers.Default) { repository.delete(objectId) }
        reload()
        refreshAlbums()
        onSuccess()
    }

    /**
     * Deletes every selected object, `DESIGN.md` §11.4.
     *
     * One reload rather than one per object: the page is read once at the end,
     * so a selection of two hundred does not redraw the grid two hundred times.
     */
    fun deleteAll(objectIds: List<ByteArray>, onSuccess: () -> Unit = {}) = guarded {
        withContext(Dispatchers.Default) {
            objectIds.forEach { repository.delete(it) }
        }
        reload()
        refreshAlbums()
        onSuccess()
    }

    /**
     * Removes every selected object from one album, §11.4.
     *
     * It is a separate action from [deleteAll] because §11.4 forbids collapsing
     * the two: one changes a membership and the other destroys the object.
     */
    fun removeAllFromAlbum(
        albumId: ByteArray,
        objectIds: List<ByteArray>,
        onSuccess: () -> Unit = {},
    ) = guarded {
        withContext(Dispatchers.Default) {
            objectIds.forEach { repository.setAlbumMembership(albumId, it, false) }
        }
        reload()
        refreshAlbums()
        onSuccess()
    }

    /** Adds a selection to an album, then removes the old membership for a move. */
    fun putAllInAlbum(
        albumId: ByteArray,
        objectIds: List<ByteArray>,
        fromAlbumId: ByteArray? = null,
        onSuccess: () -> Unit = {},
    ) = guarded {
        withContext(Dispatchers.Default) {
            objectIds.forEach { repository.setAlbumMembership(albumId, it, true) }
            if (fromAlbumId != null && !fromAlbumId.contentEquals(albumId)) {
                objectIds.forEach { repository.setAlbumMembership(fromAlbumId, it, false) }
            }
        }
        reload()
        refreshAlbums()
        onSuccess()
    }

    /** Creates an album and adds the selected objects to it. */
    fun createAlbumWithObjects(
        name: String,
        objectIds: List<ByteArray>,
        fromAlbumId: ByteArray? = null,
        onSuccess: () -> Unit = {},
    ) = guarded {
        val albumId = withContext(Dispatchers.Default) { repository.createAlbum(name) }
        withContext(Dispatchers.Default) {
            objectIds.forEach { repository.setAlbumMembership(albumId, it, true) }
            if (fromAlbumId != null) {
                objectIds.forEach { repository.setAlbumMembership(fromAlbumId, it, false) }
            }
        }
        reload()
        refreshAlbums()
        onSuccess()
    }

    /** Creates an album and reloads the list. */
    fun createAlbum(name: String) = guarded {
        withContext(Dispatchers.Default) { repository.createAlbum(name) }
        refreshAlbums()
    }

    fun renameAlbum(albumId: ByteArray, name: String) = guarded {
        withContext(Dispatchers.Default) { repository.renameAlbum(albumId, name) }
        refreshAlbums()
    }

    fun deleteAlbum(albumId: ByteArray, onSuccess: () -> Unit = {}) = guarded {
        withContext(Dispatchers.Default) { repository.deleteAlbum(albumId) }
        reload()
        refreshAlbums()
        onSuccess()
    }

    fun moveAlbum(
        albumId: ByteArray,
        parentId: ByteArray?,
        beforeId: ByteArray?,
        onSuccess: () -> Unit = {},
    ) = guarded {
        withContext(Dispatchers.Default) { repository.moveAlbum(albumId, parentId, beforeId) }
        refreshAlbums()
        onSuccess()
    }

    fun moveAlbumMember(
        albumId: ByteArray,
        objectId: ByteArray,
        beforeId: ByteArray?,
        onSuccess: () -> Unit = {},
    ) = guarded {
        withContext(Dispatchers.Default) { repository.moveAlbumMember(albumId, objectId, beforeId) }
        reload()
        onSuccess()
    }

    /** Applies or removes one tag across the selected objects. */
    fun setTagForAll(
        tagId: ByteArray,
        objectIds: List<ByteArray>,
        tagged: Boolean,
        onSuccess: () -> Unit = {},
    ) = guarded {
        withContext(Dispatchers.Default) {
            objectIds.forEach { repository.setObjectTag(tagId, it, tagged) }
        }
        reload()
        onSuccess()
    }

    /** Creates a tag and applies it to the selection. */
    fun createTagWithObjects(name: String, objectIds: List<ByteArray>, onSuccess: () -> Unit = {}) = guarded {
        val tagId = withContext(Dispatchers.Default) { repository.createTag(name) }
        withContext(Dispatchers.Default) {
            objectIds.forEach { repository.setObjectTag(tagId, it, true) }
        }
        reload()
        _tags.value = withContext(Dispatchers.Default) { repository.tags() }
        onSuccess()
    }

    // -----------------------------------------------------------------------
    // Sync, `docs/sync/SYNC_PROTOCOL_V1.md`
    // -----------------------------------------------------------------------

    /**
     * Connects the open vault to the server the user named, §6.
     *
     * The secret is the operator bootstrap secret and lives only for this
     * call. A refusal lands in [message] the way every other boundary failure
     * does, and the engine's own status keeps whatever it showed before.
     */
    fun configureSync(serverUrl: String, bootstrapSecret: String) = guarded {
        sync?.configure(serverUrl, bootstrapSecret)
    }

    /** Runs one sync cycle now, which the settings entry offers. */
    fun syncNow() = guarded {
        sync?.syncNow()
    }

    /** Forgets the server, which the settings entry offers beside the run. */
    fun disconnectSync() = guarded {
        sync?.disconnect()
    }

    /** Loads public sharing identity and current active recipients for Settings. */
    fun loadSharing() = guarded(clearMessage = false) {
        _sharingIdentity.value = withContext(Dispatchers.Default) { repository.syncIdentity() }
        _sharingOverview.value = withContext(Dispatchers.Default) { repository.sharingOverview() }
    }

    /** Validates the pasted self-signed enrollment and shows its fingerprint. */
    fun inspectSharingRecipient(enrollmentHex: String) = guarded {
        recipientEnrollment = null
        _sharingRecipient.value = null
        val compact = enrollmentHex.filterNot(Char::isWhitespace)
        if (compact.length > 2048) throw ChurFailure(ChurStatus.INVALID_INPUT, "enrollment is too long")
        val bytes = try {
            compact.fromHex()
        } catch (_: IllegalArgumentException) {
            throw ChurFailure(ChurStatus.INVALID_INPUT, "enrollment is not hexadecimal")
        }
        val recipient = withContext(Dispatchers.Default) { repository.inspectEnrollment(bytes) }
        recipientEnrollment = bytes
        _sharingRecipient.value = recipient
    }

    /** Publishes a grant after the UI confirms both identity and vault scope. */
    fun shareWithRecipient(permission: SharingPermission) = guarded {
        val enrollment = recipientEnrollment ?: throw ChurFailure(ChurStatus.INVALID_INPUT, "recipient is not verified")
        val collection = _sharingOverview.value?.collectionId
            ?: throw ChurFailure(ChurStatus.INVALID_INPUT, "sharing overview is not loaded")
        val engine = sync ?: throw ChurFailure(ChurStatus.INVALID_INPUT, "sync is unavailable")
        if (!engine.status.value.configured) throw ChurFailure(ChurStatus.INVALID_INPUT, "connect sync first")
        engine.requireActiveVault()
        val share = withContext(Dispatchers.Default) {
            repository.prepareShare(collection, enrollment, permission)
        }
        engine.publishShare(share)
        _sharingOverview.value = withContext(Dispatchers.Default) { repository.sharingOverview() }
        recipientEnrollment = null
        _sharingRecipient.value = null
        _message.value = "Access published."
    }

    /** Rotates the collection key and publishes the forward-only revocation. */
    fun revokeSharingMember(member: SharingMember) = guarded {
        val collection = _sharingOverview.value?.collectionId
            ?: throw ChurFailure(ChurStatus.INVALID_INPUT, "sharing overview is not loaded")
        val engine = sync ?: throw ChurFailure(ChurStatus.INVALID_INPUT, "sync is unavailable")
        if (!engine.status.value.configured) throw ChurFailure(ChurStatus.INVALID_INPUT, "connect sync first")
        engine.requireActiveVault()
        var batch = withContext(Dispatchers.Default) {
            repository.revokeShare(collection, member.vaultId, member.deviceId)
        }
        while (true) {
            engine.publishRevocation(batch)
            if (batch.rotationComplete) break
            batch = withContext(Dispatchers.Default) {
                repository.revokeShare(collection, member.vaultId, member.deviceId)
            }
        }
        _sharingOverview.value = withContext(Dispatchers.Default) { repository.sharingOverview() }
        _message.value = "Access revoked for future updates."
    }

    /**
     * One sync cycle a background schedule asked for.
     *
     * The iOS host calls this from a BGTask handler, a context where nothing
     * waits on a screen; the result is the engine's status, not a return
     * value. It runs regardless of lock state: staging is allowed locked, §7,
     * and application happens at the next unlock.
     */
    suspend fun runBackgroundSync() {
        withContext(Dispatchers.Default) { repository.start() }
        sync?.syncNow()
    }

    /**
     * Unbinds the engine from the vault, which a finishing host does.
     *
     * After the repository's runtime closes, a staged record has nowhere to
     * land, so the engine learns "no vault" rather than calling into closed
     * handles.
     */
    fun unbindSync() {
        sync?.bind(null)
    }

    /** Adds a recovery slot and shows the phrase once. */
    fun addRecoverySlot() = guarded {
        _recoveryPhrase.value = withContext(Dispatchers.Default) { repository.addRecoverySlot() }
        _slots.value = withContext(Dispatchers.Default) { repository.slots() }
    }

    /** Replaces the password slot with a password or a long numeric PIN. */
    fun changePassword(password: String) = guarded {
        if (password.isEmpty() ||
            (password.all { it in '0'..'9' } && password.length <= 20 && !isValidVaultPin(password))) {
            _message.value = "Use a password or a PIN of at least 12 digits."
            return@guarded
        }
        val bytes = password.encodeToByteArray()
        try {
            withContext(Dispatchers.Default) { repository.changePassword(bytes) }
        } finally {
            bytes.fill(0)
        }
        _message.value = "Vault credential changed. Existing backups still use the old credential."
        _slots.value = withContext(Dispatchers.Default) { repository.slots() }
    }

    /** One object's detail record, §6.5. */
    suspend fun detailOf(objectId: ByteArray): ObjectDetail? = try {
        withContext(Dispatchers.Default) { repository.detail(objectId) }
    } catch (failure: ChurFailure) {
        _message.value = failure.status.name
        null
    }

    /**
     * Exports one object, `PLAINTEXT_LIFECYCLE.md` §6.
     *
     * The message says the copy is outside the vault rather than reporting a
     * silent success: §6 makes this the moment the user deliberately leaves the
     * boundary, and recipients, editors, and share extensions persist plaintext
     * under their own policies from here on.
     */
    fun export(objectId: ByteArray, target: ExportTarget = ExportTarget.DEFAULT, uri: String? = null) =
        guarded { tracked("export") { token -> exportOne(objectId, token, target, uri) } }

    private suspend fun exportOne(objectId: ByteArray, token: Long, target: ExportTarget, uri: String?) {
        // A SAF document exists as soon as the picker returns. Own it before
        // reading catalog metadata so even an invalidated object deletes it.
        var destination = if (target == ExportTarget.FILES && uri != null) {
            exports.create("", "", target, uri)
                ?: throw ChurFailure(ChurStatus.IO_FAILURE, "the export destination")
        } else null
        var published = false
        try {
            val detail = withContext(Dispatchers.Default) { repository.detail(objectId) }
            val output = destination ?: exports.create(
                detail.filename.ifBlank { "chur-export" }, detail.contentType, target, uri,
            ) ?: throw ChurFailure(ChurStatus.IO_FAILURE, "the export destination")
            destination = output
            val operation = withContext(Dispatchers.Default) {
                repository.beginExport(objectId, output.descriptor)
            }
            val terminal = try {
                drain(operation, token)
            } finally {
                closeOperation(operation)
            }
            if (terminal != 0) {
                throw ChurFailure(ChurStatus.fromValue(terminal), "the export")
            }
            if (_activeOperation.value?.id != token) {
                throw ChurFailure(ChurStatus.CANCELLED, "the export")
            }
            output.publish()
            published = true
            _message.value = "Export prepared. The destination may keep a plaintext copy."
        } finally {
            try {
                if (!published) destination?.discard()
            } finally {
                destination?.close()
            }
        }
    }

    /**
     * Verifies every object, `CATALOG_SCHEMA_V1.md` §13.
     *
     * The scan runs on a worker inside Rust and this polls it, which §10 makes
     * the only way to observe a terminal result. The message carries a count
     * and a status name and nothing private.
     */
    /**
     * Exports every selected object, one destination each.
     *
     * A single archive would be a second container format, which
     * `CANONICAL_ENCODING_V1.md` §13 does not admit, so the loop is the design
     * rather than a simplification.
     */
    fun exportAll(
        objectIds: List<ByteArray>,
        target: ExportTarget = ExportTarget.DEFAULT,
        onSuccess: () -> Unit = {},
    ) = guarded {
        var exported = 0
        try {
            tracked("export") { token ->
                objectIds.forEach {
                    if (cancellationRequested(token)) {
                        throw ChurFailure(ChurStatus.CANCELLED, "the export")
                    }
                    exportOne(it, token, target, null)
                    exported += 1
                }
                if (cancellationRequested(token)) {
                    throw ChurFailure(ChurStatus.CANCELLED, "the export")
                }
                onSuccess()
            }
        } catch (failure: ChurFailure) {
            if (failure.status == ChurStatus.CANCELLED && exported > 0) {
                _message.value = "Cancelled after exporting $exported. Exported copies remain outside the vault."
            } else {
                throw failure
            }
        }
    }

    /**
     * Writes a backup package, `BACKUP_FORMAT_V1.md` §5 and §7.
     *
     * The destination is the host's, exactly as an export's is: the picker and
     * the provider are the platform's and this asks for a descriptor. The
     * package is published only on a terminal success, so an interrupted write
     * leaves nothing that looks complete — §7's "incomplete state remains
     * explicitly marked and is never advertised as complete".
     *
     * The message carries counts and no identity. §9 already tells anyone
     * holding the file its size and its creation time; it does not tell them
     * whose vault it is, and neither does this.
     */
    fun createBackup() = guarded {
        tracked("backup") { token ->
            val destination = exports.create("chur-backup", "application/octet-stream")
                ?: throw ChurFailure(ChurStatus.IO_FAILURE, "the backup destination")
            var published = false
            try {
                val operation = withContext(Dispatchers.Default) {
                    repository.beginBackup(destination.descriptor)
                }
                val terminal = try {
                    drain(operation, token)
                } finally {
                    closeOperation(operation)
                }
                if (terminal != 0) {
                    throw ChurFailure(ChurStatus.fromValue(terminal), "the backup")
                }
                if (_activeOperation.value?.id != token) {
                    throw ChurFailure(ChurStatus.CANCELLED, "the backup")
                }
                destination.publish()
                published = true
                _message.value =
                    "Backup written. It opens with the password or phrase you use now."
            } finally {
                try {
                    if (!published) destination.discard()
                } finally {
                    destination.close()
                }
            }
        }
    }

    /**
     * Restores a backup package, `BACKUP_FORMAT_V1.md` §8.
     *
     * It is [createBackup] read backwards and differs in two things, both of
     * them §8's. The operation runs from the runtime rather than from a
     * session, because a restore installs an identity and there may be no
     * session and no vault when it starts; and the credential is the package's
     * own, taken from its portable descriptor at step 2.
     *
     * The source is the host's rather than the [ExportSink]'s. An export
     * destination is one the application creates; a package is a file the user
     * picks, which is a platform picker and not a call. Rust duplicates the
     * descriptor at the boundary, §13 of the FFI contract, so the host may
     * close its own as soon as this returns; [close] returns it here rather
     * than at the host so one lambda ends the attempt on a refusal exactly as
     * on a success, and a descriptor the application keeps is a file the
     * platform cannot reclaim.
     *
     * The bracket is [enrollDeviceSlot]'s, for a longer reason. A restore is
     * the longest operation here, and `VaultRepository.lock` publishes `Locked`
     * whether or not a session or a vault exists, so a background transition
     * during one would leave a storage root that holds no identity reported as
     * locked - an unlock gate with nothing behind it, and no route back to
     * creation.
     *
     * The repository published `NoVault` and nothing has asked it since;
     * `start` is that question, it opens no runtime that is already open, and
     * it is what moves the shell from creation to the unlock gate.
     *
     * The message is the status name and nothing more, as every other boundary
     * failure is: `docs/ERROR_MODEL.md` "Safe metadata" keeps a private value
     * out of it, and the package's password is one.
     */
    fun restoreBackup(sourceFd: Int, password: String, close: () -> Unit) = guarded {
        val bytes = password.encodeToByteArray()
        beginHostActivity()
        try {
            tracked("restore") { token ->
                val operation = withContext(Dispatchers.Default) {
                    repository.beginRestore(sourceFd, bytes)
                }
                val terminal = try {
                    drain(operation, token)
                } finally {
                    closeOperation(operation)
                }
                if (terminal != 0) {
                    throw ChurFailure(ChurStatus.fromValue(terminal), "the restore")
                }
                withContext(Dispatchers.Default) { repository.start() }
                _route.value = AppRoute.Unlock
            }
        } finally {
            bytes.fill(0)
            endHostActivity()
            close()
        }
    }

    /**
     * Provisions a second vault identity, `DECOY_VAULT.md` §3.
     *
     * It routes to the ordinary creation screen. There is no separate decoy
     * flow, and that is the design rather than an omission: §2 gives feature
     * code an opaque session and no durable `isDecoy`, so a second identity is
     * created the way the first one was, with its own root, its own slots, and
     * its own credential.
     *
     * `vault::create` refuses a credential that already opens an identity here,
     * which is §3's distinct-credential rule and also the reason a second
     * identity under a shared password would be unreachable forever.
     */
    fun createSecondIdentity() {
        _route.value = AppRoute.CreateVault
    }

    fun verifyEverything() = guarded {
        tracked("verification") { token ->
            val operation = withContext(Dispatchers.Default) { repository.beginIntegrityScan(null) }
            try {
                val result = drainProgress(operation, token)
                _message.value = if (result.status == 0) {
                    "Verified ${result.processed} object(s)."
                } else {
                    statusMessage(result.status)
                }
            } finally {
                closeOperation(operation)
                if (repository.state.value is VaultState.Unlocked) reload()
            }
        }
    }

    /** Writes a public note. */
    fun putNote(note: Note) = guarded {
        val first = !notes.disclosureAcknowledged()
        notes.put(note)
        _notes.value = notes.all()
        // `DISCREET_MODE.md`: the statement appears on the first public-shell
        // write, which is this one. It is raised after the write rather than
        // before it, so the note the user was making is never interrupted.
        if (first) {
            _disclosureDue.value = true
        }
    }

    /**
     * Records that the public-shell disclosure has been shown.
     *
     * The flag is persisted in public storage, so it survives a process
     * restart: `DISCREET_MODE.md` says once, and once means once.
     */
    fun acknowledgeDisclosure() = guarded {
        notes.acknowledgeDisclosure()
        _disclosureDue.value = false
    }

    /** Removes a public note. */
    fun removeNote(id: String) = guarded {
        notes.remove(id)
        _notes.value = notes.all()
    }

    /** Reports the outcome of a host-driven import, §13 of the media pipeline. */
    fun reportImport(message: String?) = guarded {
        // The order is the point: `guarded` clears the message first, so the
        // one this call carries is set after it, not before.
        _message.value = message
        reload()
    }

    /** Sets the message a host flow produced. */
    fun report(message: String?) {
        _message.value = message
    }

    /** Requests cancellation; the worker sends it to Rust before its next poll. */
    fun cancelActiveOperation() {
        _activeOperation.update { current ->
            current?.takeIf { it.cancellable }?.copy(cancelling = true) ?: current
        }
    }

    /** Runs a picker import with the same progress and cancellation as other work. */
    suspend fun importMedia(importer: MediaImporter, open: () -> PickedMedia?): MediaImporter.Outcome? =
        try {
            tracked("import") { token ->
                withContext(Dispatchers.Default) {
                    importer.import(
                        repository = repository,
                        source = open(),
                        onProgress = { updateOperation(token, it, keepCancellableOnSuccess = true) },
                        cancelRequested = { cancellationRequested(token) },
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ChurFailure) {
            MediaImporter.Outcome.Refused(statusMessage(failure.status.value))
        } catch (_: Exception) {
            MediaImporter.Outcome.Refused(ChurStatus.INTERNAL_FAILURE.name)
        }

    /** Closes the runtime, which a finishing host does. */
    suspend fun shutdown() {
        withContext(Dispatchers.Default) { repository.shutdown() }
    }

    private suspend fun <T> tracked(name: String, body: suspend (Long) -> T): T? {
        if (_activeOperation.value != null) {
            _message.value = "Finish the current operation first."
            return null
        }
        val token = ++operationToken
        _activeOperation.value = ActiveOperation(token, name)
        try {
            val result = body(token)
            return result.takeIf { _activeOperation.value?.id == token }
        } finally {
            _activeOperation.update { current -> current?.takeUnless { it.id == token } }
        }
    }

    private fun cancellationRequested(token: Long): Boolean =
        _activeOperation.value?.let { it.id != token || it.cancelling } ?: true

    private fun updateOperation(
        token: Long,
        progress: OperationProgress,
        keepCancellableOnSuccess: Boolean = false,
    ) {
        _activeOperation.update { current ->
            current?.takeIf { it.id == token }?.copy(
                processed = progress.processed,
                total = progress.total,
                stage = progress.stage,
                cancellable = !progress.terminal || (keepCancellableOnSuccess && progress.status == 0),
            ) ?: current
        }
    }

    private suspend fun closeOperation(operation: Long) {
        withContext(NonCancellable + Dispatchers.Default) {
            try {
                repository.cancel(operation)
            } finally {
                repository.closeOperation(operation)
            }
        }
    }

    private fun statusMessage(status: Int): String =
        if (status == ChurStatus.CANCELLED.value) "Cancelled." else ChurStatus.fromValue(status).name

    /** Polls one native operation and forwards its redacted snapshot to the UI. */
    private suspend fun drainProgress(operation: Long, token: Long): OperationProgress {
        var signalled = false
        while (true) {
            if (!signalled && cancellationRequested(token)) {
                withContext(Dispatchers.Default) { repository.cancel(operation) }
                signalled = true
            }
            val progress =
                withContext(Dispatchers.Default) { repository.poll(operation) }
            updateOperation(token, progress)
            if (progress.terminal) return progress
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun drain(operation: Long, token: Long): Int = drainProgress(operation, token).status

    private suspend fun reload() {
        if (repository.state.value is VaultState.Unlocked) {
            _page.value = withContext(Dispatchers.Default) { repository.page(currentQuery) }
        }
    }

    private suspend fun refreshAlbums() {
        _albums.value = withContext(Dispatchers.Default) { repository.albums() }
    }

    private suspend fun clearPrivateProjections() {
        _activeOperation.value = null
        exports.cancelPending()
        currentQuery = ObjectQuery()
        // §10.3: a lock transition destroys private back-stack projections, and
        // these flows are that projection. §4 of `PLAINTEXT_LIFECYCLE.md` and
        // §8 step 7 add the decoded-image cache, which leaves with them.
        thumbnails.clear()
        _page.value = ObjectPage(emptyList(), 0, 0, null)
        _albums.value = emptyList()
        _tags.value = emptyList()
        _slots.value = emptyList()
        _sharingIdentity.value = null
        _sharingOverview.value = null
        _sharingRecipient.value = null
        recipientEnrollment = null
        // The phrase is one of them, and the most valuable: it opens the vault
        // on its own. Both route tables draw it ahead of the route, so leaving
        // it set kept a full credential on screen after the lock had already
        // moved the route to the public shell — and the same transition takes
        // the cover off, which put it in the switcher snapshot too. §8 step 7
        // clears feature projections and step 9 shows a neutral surface; a
        // phrase that survived the lock did neither.
        //
        // Losing an unacknowledged phrase to a lock is the intended cost.
        // `RECOVERY.md` §2 shows it exactly once, the password still opens the
        // vault, and §8 there is how a user gets another one.
        _recoveryPhrase.value = null
        // A launch whose result never arrived belonged to the session that just
        // ended. Carrying its count forward would suppress the background lock
        // of the next session, so the count ends with the session.
        hostActivities = 0
    }

    private suspend fun completeUnlock(target: AppRoute, epoch: Long) {
        if (epoch != lockEpoch || _route.value != target) {
            exports.cancelPending()
            withContext(Dispatchers.Default) { repository.lock(LockReason.BACKGROUND) }
            clearPrivateProjections()
            _route.value = if (_appLockEnabled.value) AppRoute.AppUnlock else AppRoute.PublicShell
            return
        }
        if (target == AppRoute.AppUnlock || target == AppRoute.AppRecover) {
            exports.cancelPending()
            withContext(Dispatchers.Default) { repository.lock(LockReason.USER) }
            privacy.setEnabled(false)
            _route.value = AppRoute.PublicShell
        } else {
            enterVault()
        }
    }

    private suspend fun enterVault() {
        privacy.setEnabled(true)
        _route.value = AppRoute.Vault
        _page.value = withContext(Dispatchers.Default) { repository.page(ObjectQuery()) }
        // `SYNC_PROTOCOL_V1.md` §7: "Decrypted application occurs after
        // explicit unlock". Whatever the locked puller staged while the vault
        // was closed is validated and applied here, off the first frame, and
        // then a configured engine pulls what arrived since the last run.
        sync?.takeIf { it.status.value.configured }?.let { engine ->
            guarded {
                withContext(Dispatchers.Default) { repository.processSync() }
                engine.syncNow()
            }
        }
    }

    /**
     * Runs work and turns a boundary failure into a message.
     *
     * `docs/ERROR_MODEL.md` keeps a private value out of a message, and the
     * boundary carries only a status, so the message is the status name.
     *
     * The second catch is the backstop. Every action a surface can invoke goes
     * through here, and a platform adapter that raised something other than a
     * [ChurFailure] would otherwise leave an uncaught exception in a coroutine
     * and take the process with it — a crash that ends the session without the
     * orderly lock `PLAINTEXT_LIFECYCLE.md` §8 describes, reached by tapping an
     * ordinary settings row. `ERROR_MODEL.md` requires a platform layer to
     * normalize before a feature sees it; this says what happens when one did
     * not, and folds the unknown into [ChurStatus.INTERNAL_FAILURE] the same
     * way `fromValue` folds an unrecognized code. `CancellationException` is
     * re-thrown ahead of it, because swallowing it would break the cancellation
     * of the scope itself.
     */
    private fun guarded(clearMessage: Boolean = true, body: suspend () -> Unit) {
        scope.launch {
            try {
                if (clearMessage) _message.value = null
                body()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: ChurFailure) {
                _message.value = statusMessage(failure.status.value)
            } catch (_: Exception) {
                _message.value = ChurStatus.INTERNAL_FAILURE.name
            }
        }
    }

    private companion object {
        /** Fast enough to feel immediate, slow enough not to spin a core. */
        const val POLL_INTERVAL_MS = 50L

        /**
         * How often the idle timer looks.
         *
         * The shortest choice of §14.4 is "immediately", so the tick has to be
         * short enough that the shortest choice still reads as immediate; a
         * second is that, and it is one wakeup a second only while a session is
         * unlocked.
         */
        const val IDLE_TICK_MS = 1_000L
    }
}

/**
 * Where an export lands, `PLAINTEXT_LIFECYCLE.md` §6.
 *
 * Android streams to MediaStore or a selected document and shares through a
 * temporary FileProvider URI. iOS exports through Photos, Files, or the share
 * sheet. All destinations receive a verified stream and remove incomplete
 * results where the provider permits it.
 */
interface ExportSink {
    /** Revokes and deletes temporary results still owned by this application. Safe to repeat. */
    fun cancelPending()

    /** One open destination. */
    interface Destination {
        /** The descriptor Rust writes into; §13 has Rust duplicate it. */
        val descriptor: Int

        /** Makes the result visible once the whole object is written. */
        fun publish()

        /** Removes a destination whose export failed. */
        fun discard()

        /** Closes the descriptor, which the caller owns. */
        fun close()
    }

    /** Creates a destination for one export. */
    fun create(displayName: String, contentType: String): Destination?

    fun create(
        displayName: String,
        contentType: String,
        target: ExportTarget,
        uri: String?,
    ): Destination?
}

enum class ExportTarget { DEFAULT, FILES, MEDIA_LIBRARY, SHARE }
