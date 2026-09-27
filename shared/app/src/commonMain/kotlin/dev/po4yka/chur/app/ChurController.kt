package dev.po4yka.chur.app

import dev.po4yka.chur.app.vault.PresentedState
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
import dev.po4yka.chur.ffi.QuerySort
import dev.po4yka.chur.sync.SyncCoordinator
import dev.po4yka.chur.sync.SyncStatus
import dev.po4yka.chur.sync.SyncTransportFailure
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

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
    private val uncaught = CoroutineExceptionHandler { context, _ ->
        post(userCopy(ChurStatus.INTERNAL_FAILURE), context[RouteVisit]?.number ?: routeVisit)
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
    private var queryRevision = 0L
    private val _slots = MutableStateFlow<List<SlotSummary>>(emptyList())
    private val _sharingIdentity = MutableStateFlow<SharingIdentity?>(null)
    private val _sharingOverview = MutableStateFlow<SharingOverview?>(null)
    private val _sharingRecipient = MutableStateFlow<SharingRecipient?>(null)
    private var recipientEnrollment: ByteArray? = null
    private val _notice = MutableStateFlow<Notice?>(null)
    private val _formError = MutableStateFlow<String?>(null)
    private var noticeCount = 0L

    /**
     * How many times the route has changed, which [post] compares.
     *
     * Touched only from the main dispatcher, where every route write runs.
     */
    private var routeVisit = 0L
    private val _activeOperation = MutableStateFlow<ActiveOperation?>(null)
    private var operationToken = 0L
    private val _recoveryPhrase = MutableStateFlow<String?>(null)
    private val _deviceUnlockOffered = MutableStateFlow(false)
    private val _unlocking = MutableStateFlow(false)

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

    /**
     * Whether the switcher must not show the screen now: [needsPrivacyCover]
     * of the current state.
     *
     * The iOS scene delegate reads it on `sceneWillResignActive` and on
     * `sceneDidEnterBackground`. Kotlin/Native gives Swift a `StateFlow` value
     * only as an untyped object, so the check is made here and not in the host.
     */
    val privacyCoverNeeded: Boolean
        get() = needsPrivacyCover(vaultState.value, route.value, recoveryPhrase.value != null)

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

    /**
     * The outcome of the last action on the vault, shown once as the snackbar
     * of `DESIGN.md` §26.
     *
     * Its text is [userCopy], [syncCopy], a count, or a fixed line, so it
     * carries no private value, `ERROR_MODEL.md` "Safe metadata". Only the
     * vault shell and the viewer read it. A route change clears it, so an
     * outcome never reaches another screen: not the public shell, which must
     * not show a vault outcome (`DECOY_VAULT.md` §10), and not a credential
     * form, where it would read as a refusal.
     */
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    /**
     * Why the credential form on screen was refused: creation, restore,
     * unlock or recovery.
     *
     * It is separate from [notice] because a form shows its refusal beside
     * its fields for as long as the form is up, and an unlock screen reads
     * any refusal as a failed attempt. A route change clears it.
     */
    val formError: StateFlow<String?> = _formError.asStateFlow()

    /** Marks [notice] [id] as shown, so no screen shows it again. */
    fun consume(id: Long) {
        _notice.update { current -> current?.takeUnless { it.id == id } }
    }

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

    /**
     * Whether an unlock or a recovery is being tried, [attempt].
     *
     * The unlock and recovery forms are busy while it holds: the button says
     * "Opening" and cannot be pressed again, and the error region is blank.
     * The repository keeps an equal `Locked` state after a second refusal, so
     * without the blank a screen reader would hear only the first one,
     * `DESIGN.md` §23.2.
     */
    val unlocking: StateFlow<Boolean> = _unlocking.asStateFlow()

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
            setRoute(AppRoute.PublicShell)
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
     * so no wakeup runs while there is nothing to lock. A creation waiting for
     * its recovery phrase to be confirmed counts: it holds a root and shows a
     * full credential, so it times out as a session does.
     */
    private suspend fun runIdleTimer() {
        repository.state.collectLatest { current ->
            if (current !is VaultState.Unlocked && current !is VaultState.Creating) return@collectLatest
            while (true) {
                delay(IDLE_TICK_MS)
                checkIdle()
            }
        }
    }

    /**
     * Whether a request that ends with a recovery phrase on screen is in
     * flight, [create] or [addRecoverySlot].
     *
     * A second request abandons what the first staged while the first phrase
     * can already be on screen, so that phrase opens nothing, and a Continue
     * on it would commit the second before its phrase was ever shown. So a
     * request is ignored while another runs or its phrase is shown. The flag
     * is set on Main before the work starts, because the vault state reads
     * `Creating` only once the work has reached the repository.
     */
    private var phraseRequested = false

    private fun requestPhrase(body: suspend () -> Unit) {
        if (phraseRequested || _recoveryPhrase.value != null) return
        phraseRequested = true
        guarded {
            try {
                body()
            } finally {
                phraseRequested = false
            }
        }
    }

    /** Creates a vault, `PROVISIONING.md` §3. */
    fun create(password: String, offerRecovery: Boolean) = requestPhrase {
        if (password.isNotEmpty() && password.all { it in '0'..'9' } &&
            password.length <= 20 && !isValidVaultPin(password)) {
            say("Use at least 12 digits for a vault PIN.")
            return@requestPhrase
        }
        endOpenSession()
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
            say("This vault could not be created. Try a different credential.")
            return@requestPhrase
        } finally {
            bytes.fill(0)
        }
        if (phrase != null) _recoveryPhrase.value = phrase else enterVault()
    }

    /**
     * Acknowledges the phrase, which is the only way past that screen.
     *
     * The slot commits here and not before: `PROVISIONING.md` §4 runs the
     * presentation and confirmation "before the slot commits", and
     * `RECOVERY.md` §8 confirms before the new descriptor generation. The
     * phrase leaves the screen at the tap, so it is shown once and a second
     * tap does nothing. A lock that reached the repository first already
     * discarded what the phrase belonged to, and the user is told that the
     * phrase they wrote down was not saved.
     *
     * An activation that fails reaches the user as a message, as a failed
     * [create] does, and leaves nothing to clear: the repository abandons the
     * creation, and [endOpenSession] cleared the projections of an open
     * session before the creation began.
     */
    fun acknowledgeRecoveryPhrase() {
        if (_recoveryPhrase.value == null) return
        _recoveryPhrase.value = null
        guarded {
            if (withContext(Dispatchers.Default) { repository.confirmRecoveryPhrase() }) {
                enterVault()
            } else {
                say("This recovery phrase was not saved.")
            }
        }
    }

    /** Unlocks with a password. */
    fun unlock(password: String) = attempt {
        endOpenSession()
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

    /** Unlocks with the recovery phrase, and says why a phrase was refused. */
    fun recover(phrase: String) = attempt(::recoveryCopy) {
        endOpenSession()
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
    fun unlockWithDevice() = attempt {
        endOpenSession()
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
    fun unlockWithAppleDevice() = attempt {
        endOpenSession()
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
            withContext(Dispatchers.Default) { addKeystoreSlot() }
        } finally {
            endHostActivity()
        }
        say("This device can now open the vault.")
        loadSlots()
        refreshDeviceUnlockOffer()
    }

    /** Stores a random Rust device secret under Keychain authorization. */
    fun enrollAppleDeviceSlot() = guarded {
        beginHostActivity()
        try {
            withContext(Dispatchers.Default) { addAppleSlot() }
        } finally {
            endHostActivity()
        }
        say("This device can now open the vault.")
        loadSlots()
        refreshDeviceUnlockOffer()
    }

    /**
     * Enrolls a Keystore slot under the policy [deviceSlotPolicy] holds now,
     * which the Android binding reads when it generates the key.
     */
    private suspend fun addKeystoreSlot() {
        repository.enrollKeystoreSlot { alias, aad, root ->
            deviceUnlock.wrap(alias, aad, root)
        }
    }

    /** Enrolls a Keychain slot under the policy [deviceSlotPolicy] holds now. */
    private suspend fun addAppleSlot() {
        val itemId = appleDeviceUnlock.newItemId()
        repository.enrollAppleSlot(itemId) { secret ->
            appleDeviceUnlock.storeSecret(itemId, secret, deviceSlotPolicy.read())
        }
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
     * Switches the device-slot policy of `KEY_SLOTS.md` §1 and replaces the
     * device slot the open vault holds under the old one.
     *
     * The platform fixes a slot's authentication when it creates the key or
     * the Keychain item, and the Android unwrap follows the stored key rather
     * than this setting. Writing the setting alone therefore left a convenient
     * slot that the device unlock code still opened after the user chose
     * biometrics only, which is the adversary `THREAT_MODEL.md` A2 names.
     * `ANDROID.md` §9.3 makes a mode change create or replace the platform
     * slot, so this enrolls a slot under the new policy through the path, and
     * the prompt, an enrollment uses. It removes the old slots only after the
     * new one commits, the order of `KEY_SLOTS.md` §9 and `ANDROID.md` §9.4
     * steps 5 and 6. With no slot enrolled, the change is the creation-time
     * choice §1 describes and nothing else happens.
     *
     * [deviceSlotStrict] comes from the slots that survive, not from the
     * request. A cancelled prompt or a failed removal therefore never shows a
     * policy that is not in force. The platform binding reads the new policy
     * from the setting during the swap, so the setting is written first; a
     * process that dies inside the swap skips the read-back and can keep the
     * new policy in the setting until the next toggle replaces the slot.
     */
    fun toggleDeviceSlotPolicy() = guarded {
        val previous = _deviceSlotStrict.value
        val next = !previous
        // The slot types [SlotSummary.familyName] gives: 2 is the Android
        // Keystore, 3 the Apple Keychain. The Keychain binding wins when it is
        // bound, the same choice [refreshDeviceUnlockOffer] makes.
        val family = if (appleDeviceUnlock.available) 3 else 2
        beginHostActivity()
        try {
            withContext(Dispatchers.Default) {
                val before = repository.slots().filter { it.slotType == family }
                deviceSlotPolicy.write(next)
                try {
                    if (before.isNotEmpty()) {
                        if (family == 3) addAppleSlot() else addKeystoreSlot()
                        // Every old slot goes even when one fails: a removal
                        // can fail after the descriptor dropped the slot, and
                        // stopping there would keep the next old slot.
                        before.mapNotNull { slot ->
                            runCatching { repository.removeSlot(slot.slotId) }.exceptionOrNull()
                        }.firstOrNull()?.let { throw it }
                    }
                } finally {
                    // A slot listed in `before` holds the old policy and any
                    // other slot holds the new one. A mix of the two always
                    // includes a convenient slot, which the device unlock code
                    // opens. If the vault cannot be read, assume it holds one.
                    val after = runCatching {
                        repository.slots().filter { it.slotType == family }
                    }.getOrNull()
                    val inForce = when {
                        after == null -> false
                        after.none { it in before } -> next
                        else -> previous && after.all { it in before }
                    }
                    deviceSlotPolicy.write(inForce)
                    _deviceSlotStrict.value = inForce
                }
            }
        } finally {
            endHostActivity()
            loadSlots()
            refreshDeviceUnlockOffer()
        }
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
        setRoute(if (_appLockEnabled.value) AppRoute.AppUnlock else AppRoute.PublicShell)
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
        // A background that did not lock keeps a creation waiting for its
        // phrase together with the phrase.
        val after = repository.state.value
        if (after !is VaultState.Unlocked && after !is VaultState.Creating) {
            clearPrivateProjections()
            setRoute(if (_appLockEnabled.value) AppRoute.AppUnlock else AppRoute.PublicShell)
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

    /**
     * The idle check of `DESIGN.md` §14.4, which [runIdleTimer] drives.
     *
     * The lock publishes the new state before this check resumes on Main, and
     * the `collectLatest` of [runIdleTimer] then cancels the check. Without
     * [NonCancellable] the check stopped after the lock and before the
     * clearing: the route stayed in the vault, and a recovery phrase whose
     * slot the lock had discarded stayed on screen with the display awake,
     * against the clearing policy of `DESIGN.md` §17.2.
     */
    suspend fun checkIdle() {
        withContext(NonCancellable) {
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
                setRoute(if (_appLockEnabled.value) AppRoute.AppUnlock else AppRoute.PublicShell)
            }
        }
    }

    /** Moves to a route the public shell offers. */
    fun goTo(next: AppRoute) {
        setRoute(next)
    }

    /** The route the visible settings entry of §2 leads to. */
    fun openVaultEntry() {
        setRoute(if (repository.state.value is VaultState.NoVault) {
            AppRoute.CreateVault
        } else {
            AppRoute.Unlock
        })
    }

    /** Loads one query scope. */
    fun load(query: ObjectQuery) {
        currentQuery = query
        val revision = ++queryRevision
        _page.value = ObjectPage(emptyList(), 0, 0, null)
        if (query.scope == QueryScope.SEARCH && query.terms.isNullOrBlank()) return
        guarded(clearMessage = false) {
            val result = withContext(Dispatchers.Default) { repository.page(query) }
            if (revision == queryRevision) _page.value = result
        }
    }

    private var loadingNextPageRevision: Long? = null

    /** Appends one bounded page when the user reaches the end of the grid. */
    fun loadNextPage() = guarded(clearMessage = false) {
        val query = currentQuery
        val revision = queryRevision
        if (loadingNextPageRevision == revision) return@guarded
        val previous = _page.value
        val cursor = previous.nextCursor ?: return@guarded
        loadingNextPageRevision = revision
        try {
            val next = withContext(Dispatchers.Default) { repository.page(query.copy(cursor = cursor)) }
            if (revision != queryRevision) return@guarded
            val result = if (next.catalogGeneration != previous.catalogGeneration) {
                pageThrough(query, previous.objects.size + next.objects.size)
            } else {
                next.copy(objects = previous.objects + next.objects)
            }
            if (revision == queryRevision) _page.value = result
        } finally {
            if (loadingNextPageRevision == revision) loadingNextPageRevision = null
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

    /** Sets or clears the favourite flag. */
    fun setFavorite(objectId: ByteArray, favorite: Boolean, onSuccess: () -> Unit = {}) = guarded {
        withContext(Dispatchers.Default) { repository.setFavorite(objectId, favorite) }
        reload()
        onSuccess()
    }

    fun setFavoritesForAll(objectIds: List<ByteArray>, favorite: Boolean,
                           onSuccess: () -> Unit = {}) = guarded {
        withContext(Dispatchers.Default) { repository.setFavorites(objectIds, favorite) }
        reload()
        say("Updated ${items(objectIds.size)}.")
        onSuccess()
    }

    /**
     * Moves an object to the recoverable trash.
     *
     * The move is confirmed with a count and an undo, `DESIGN.md` §26: a
     * mis-tap is taken back from the snackbar rather than from Trash. The
     * notice is set before [onSuccess], so a host that closes the viewer
     * there shows it in the shell.
     */
    fun delete(objectId: ByteArray, onSuccess: () -> Unit = {}) = guarded {
        withContext(Dispatchers.Default) { repository.delete(objectId) }
        reload()
        refreshAlbums()
        sayTrashed(listOf(objectId))
        onSuccess()
    }

    /**
     * Moves every selected object to trash in one transaction.
     *
     * One reload rather than one per object: the page is read once at the end,
     * so a selection of two hundred does not redraw the grid two hundred times.
     */
    fun deleteAll(objectIds: List<ByteArray>, onSuccess: () -> Unit = {}) = guarded {
        withContext(Dispatchers.Default) { repository.deleteAll(objectIds) }
        reload()
        refreshAlbums()
        sayTrashed(objectIds)
        onSuccess()
    }

    fun restoreAll(objectIds: List<ByteArray>, onSuccess: () -> Unit = {}) = guarded {
        withContext(Dispatchers.Default) { repository.restoreAll(objectIds) }
        reload()
        refreshAlbums()
        say("Restored ${items(objectIds.size)}.")
        onSuccess()
    }

    fun permanentlyDeleteAll(objectIds: List<ByteArray>, onSuccess: () -> Unit = {}) = guarded {
        try {
            withContext(Dispatchers.Default) { objectIds.forEach { repository.permanentlyDelete(it) } }
        } finally {
            thumbnails.clear()
            reload()
            refreshAlbums()
        }
        say("Deleted permanently.")
        onSuccess()
    }

    fun emptyTrash(onSuccess: () -> Unit = {}) = guarded {
        try {
            withContext(Dispatchers.Default) { repository.emptyTrash() }
        } finally {
            thumbnails.clear()
            reload()
            refreshAlbums()
        }
        say("Deleted permanently.")
        onSuccess()
    }

    fun restoreTrash(onSuccess: () -> Unit = {}) = guarded {
        withContext(Dispatchers.Default) { repository.restoreTrash() }
        reload()
        refreshAlbums()
        // The engine does not return how many it restored.
        say("Restored everything in Trash.")
        onSuccess()
    }

    /**
     * Removes every selected object from one album, §11.4.
     *
     * It is a separate action from [deleteAll]: one changes a membership and
     * the other moves the object out of ordinary library scopes.
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
        say("Removed ${items(objectIds.size)} from the album.")
        onSuccess()
    }

    /**
     * Adds or moves a selection in one catalog transaction.
     *
     * The notice names a count and never the album: `DESIGN.md` §26 keeps
     * private names out of a snackbar. A host that reports its own outcome
     * from [onSuccess], as an import into an album does, replaces it.
     */
    fun placeObjectsInAlbum(
        albumId: ByteArray,
        objectIds: List<ByteArray>,
        fromAlbumId: ByteArray? = null,
        move: Boolean = false,
        onSuccess: () -> Unit = {},
    ) = guarded {
        withContext(Dispatchers.Default) {
            repository.placeAlbumObjects(albumId, "", null, fromAlbumId.takeIf { move }, objectIds, move)
        }
        reload()
        refreshAlbums()
        sayPlaced(objectIds.size, move)
        onSuccess()
    }

    /** Creates a destination and places the selection atomically. */
    fun createAlbumWithObjects(
        name: String,
        parentId: ByteArray?,
        objectIds: List<ByteArray>,
        fromAlbumId: ByteArray? = null,
        move: Boolean = false,
        onSuccess: () -> Unit = {},
    ) = guarded {
        withContext(Dispatchers.Default) {
            repository.placeAlbumObjects(null, name, parentId, fromAlbumId.takeIf { move }, objectIds, move)
        }
        reload()
        refreshAlbums()
        sayPlaced(objectIds.size, move)
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
        say("Album deleted.")
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
            repository.applyTagSelection(tagId, "", objectIds, tagged)
        }
        reload()
        say("Updated ${items(objectIds.size)}.")
        onSuccess()
    }

    /** Creates a tag and applies it to the selection. */
    fun createTagWithObjects(name: String, objectIds: List<ByteArray>, onSuccess: () -> Unit = {}) = guarded {
        withContext(Dispatchers.Default) {
            repository.applyTagSelection(null, name, objectIds, true)
        }
        reload()
        _tags.value = withContext(Dispatchers.Default) { repository.tags() }
        say("Updated ${items(objectIds.size)}.")
        onSuccess()
    }

    fun createTag(name: String) = guarded {
        withContext(Dispatchers.Default) { repository.createTag(name) }
        _tags.value = withContext(Dispatchers.Default) { repository.tags() }
    }

    fun renameTag(tagId: ByteArray, name: String) = guarded {
        withContext(Dispatchers.Default) { repository.renameTag(tagId, name) }
        reload()
        _tags.value = withContext(Dispatchers.Default) { repository.tags() }
    }

    fun deleteTag(tagId: ByteArray, onSuccess: () -> Unit = {}) = guarded {
        withContext(Dispatchers.Default) { repository.deleteTag(tagId) }
        reload()
        _tags.value = withContext(Dispatchers.Default) { repository.tags() }
        say("Tag deleted.")
        onSuccess()
    }

    /** Confirms a move to Trash with its count and an undo that restores it. */
    private suspend fun sayTrashed(objectIds: List<ByteArray>) {
        say("Moved ${items(objectIds.size)} to Trash.", action = "Undo" to { restoreAll(objectIds) })
    }

    private suspend fun sayPlaced(count: Int, move: Boolean) {
        say("${if (move) "Moved" else "Added"} ${items(count)} to the album.")
    }

    // -----------------------------------------------------------------------
    // Sync, `docs/sync/SYNC_PROTOCOL_V1.md`
    // -----------------------------------------------------------------------

    /**
     * Connects the open vault to the server the user named, §6.
     *
     * The secret is the operator bootstrap secret and lives only for this
     * call. A refusal lands in [notice] the way every other boundary failure
     * does, and an engine that was already configured keeps the status it
     * showed before.
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
        say("Access published.")
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
        say("Access revoked for future updates.")
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

    /**
     * Stages a recovery slot and shows the phrase once.
     *
     * Nothing is committed yet, so the slot list stays as it is:
     * [acknowledgeRecoveryPhrase] commits it, and Settings reloads the slots
     * when it is entered again.
     */
    fun addRecoverySlot() = requestPhrase {
        _recoveryPhrase.value = withContext(Dispatchers.Default) { repository.beginRecoverySlot() }
    }

    /** Replaces the password slot with a password or a long numeric PIN. */
    fun changePassword(password: String) = guarded {
        if (password.isEmpty() ||
            (password.all { it in '0'..'9' } && password.length <= 20 && !isValidVaultPin(password))) {
            say("Use a password or a PIN of at least 12 digits.")
            return@guarded
        }
        val bytes = password.encodeToByteArray()
        try {
            withContext(Dispatchers.Default) { repository.changePassword(bytes) }
        } finally {
            bytes.fill(0)
        }
        say("Vault credential changed. Existing backups still use the old credential.")
        _slots.value = withContext(Dispatchers.Default) { repository.slots() }
    }

    /** One object's detail record, §6.5. */
    suspend fun detailOf(objectId: ByteArray): ObjectDetail? = try {
        withContext(Dispatchers.Default) { repository.detail(objectId) }
    } catch (failure: ChurFailure) {
        say(userCopy(failure.status))
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
            say("Export prepared. The destination may keep a plaintext copy.")
        } finally {
            try {
                if (!published) destination?.discard()
            } finally {
                destination?.close()
            }
        }
    }

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
                say("Cancelled after exporting $exported. Exported copies remain outside the vault.")
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
                say("Backup written. It opens with the password or phrase you use now.")
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
     * the longest operation here, and a background transition during one would
     * move the shell to the public route and take the restore screen, and the
     * progress it shows, with it.
     *
     * The repository published `NoVault` and nothing has asked it since;
     * `start` is that question, it opens no runtime that is already open, and
     * it is what moves the shell from creation to the unlock gate.
     *
     * The message is the status's [userCopy] and nothing more, as every other
     * boundary failure's is: `docs/ERROR_MODEL.md` "Safe metadata" keeps a
     * private value out of it, and the package's password is one. [close] is
     * told whether the user cancelled, so the route can show that calmly
     * rather than as a failure (principle 4 of the same document) without
     * reading the text, which its "Layer mapping" forbids a branch on.
     */
    fun restoreBackup(sourceFd: Int, password: String, close: (cancelled: Boolean) -> Unit) = guarded {
        val bytes = password.encodeToByteArray()
        var cancelled = false
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
                    cancelled = terminal == ChurStatus.CANCELLED.value
                    throw ChurFailure(ChurStatus.fromValue(terminal), "the restore")
                }
                withContext(Dispatchers.Default) { repository.start() }
                setRoute(AppRoute.Unlock)
            }
        } finally {
            bytes.fill(0)
            endHostActivity()
            close(cancelled)
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
        setRoute(AppRoute.CreateVault)
    }

    /**
     * Checks every library object and says what the check found, `DESIGN.md` §20.
     *
     * "Verified" is the §20.1 name of one presented state, so the result never
     * uses it for a count: it says how many objects were checked and names each
     * kind of problem. The scan's status carries proven corruption alone,
     * `ERROR_MODEL.md` "Integrity states versus errors", and a `CORRUPT` row is
     * in no `CATALOG_SCHEMA_V1.md` §16.2 scope, so "at least one" is the only
     * count there is for it.
     * The other verdicts are integrity states the catalog records, read back
     * after the scan: the quarantine scope's total and the timeline rows left
     * Incomplete or Unsupported. Both are the catalog's present state rather
     * than a difference from before the scan, so an import or a sync racing the
     * scan cannot turn a problem into "no problems found".
     */
    fun verifyEverything() = guarded {
        tracked("verification") { token ->
            val operation = withContext(Dispatchers.Default) { repository.beginIntegrityScan(null) }
            try {
                val result = drainProgress(operation, token)
                val corrupt = result.status == ChurStatus.OBJECT_CORRUPT.value
                if (result.status == 0 || corrupt) {
                    val quarantined = quarantinedCount()
                    val unverifiable = unverifiableCount()
                    // A problem found is an integrity verdict the user acts
                    // on, so it stays until dismissed, `DESIGN.md` §26.
                    say(
                        verificationSummary(result.processed, corrupt, quarantined, unverifiable),
                        security = corrupt || quarantined > 0 || unverifiable > 0,
                    )
                } else {
                    val status = ChurStatus.fromValue(result.status)
                    say(userCopy(status), security = status in SECURITY_STATUSES)
                }
            } finally {
                closeOperation(operation)
                if (repository.state.value is VaultState.Unlocked) reload()
            }
        }
    }

    /**
     * Writes a public note, stamped with the time of the write.
     *
     * The editor calls this as the user types and again as it closes, so this
     * is where a write is told apart from a repeat. A note equal to the stored
     * one apart from its time is not written again, and neither is a new note
     * the user never wrote in: "Create note" and straight back out is not the
     * first public-shell write `DISCREET_MODE.md` discloses. A blank edit of a
     * stored note is written: this cannot tell a note the user emptied, which
     * stays, from one "Create note" made that was written and erased again.
     * The editor knows which note it created, and removes that one through
     * [removeNote] as it leaves it blank. The comparison reads the store
     * rather than [notesState]: the store's lock places the read after every
     * write queued ahead of it, and the projection is refreshed only once each
     * has finished.
     *
     * The time is this controller's clock rather than the one the note carries,
     * which is when it was opened, so an edited note moves up a list that
     * `Notes.ordered` sorts by it.
     */
    fun putNote(note: Note) = guarded {
        val stored = notes.all().firstOrNull { it.id == note.id }
        if (stored == null && note.title.isBlank() && note.body.isBlank()) return@guarded
        if (stored != null && stored.copy(updatedMs = note.updatedMs) == note) return@guarded
        val first = !notes.disclosureAcknowledged()
        notes.put(note.copy(updatedMs = clock()))
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

    /**
     * Removes a public note.
     *
     * The editor calls this for Delete, and as it leaves a note that "Create
     * note" made and that is blank. It never calls it for a stored note only
     * because that note is blank: a note the user emptied stays.
     */
    fun removeNote(id: String) = guarded {
        notes.remove(id)
        _notes.value = notes.all()
    }

    /** Reports the outcome of a host-driven import, §13 of the media pipeline. */
    fun reportImport(message: String?) = guarded {
        // The order is the point: `guarded` clears the message first, so the
        // one this call carries is set after it, not before.
        say(message)
        reload()
    }

    /**
     * Sets the message a host flow produced, or clears it with `null`.
     *
     * A credential form shows it as its [formError], and every other route as
     * its [notice].
     */
    fun report(message: String?) {
        post(message)
    }

    /** Requests cancellation; the worker sends it to Rust before its next poll. */
    fun cancelActiveOperation() {
        _activeOperation.update { current ->
            current?.takeIf { it.cancellable }?.copy(cancelling = true) ?: current
        }
    }

    /**
     * Runs a single import with the same progress and cancellation as other
     * work. It is a pick of one, so its card reads in the `DESIGN.md` §15.2
     * phases, as [importAll] does, and not in bytes.
     */
    suspend fun importMedia(importer: MediaImporter, open: () -> PickedMedia?): MediaImporter.Outcome? =
        tracked("import") { token ->
            _activeOperation.update { current ->
                current?.takeIf { it.id == token }?.copy(item = 1, items = 1) ?: current
            }
            importItem(importer, token) { open() }
        }

    /**
     * Imports what one picker session returned, as one operation.
     *
     * A picker hands back many items at once, and each used to need its own
     * round trip through the picker, because a second import was refused
     * while the first ran. Here the items run one after another under one
     * progress card, "Importing item 3 of 12", `DESIGN.md` §15.2 and §23.2.
     * One at a time keeps the bound of `MEDIA_PIPELINE.md` §12 on what is in
     * flight.
     *
     * Each item commits on its own, so a cancel or a lock keeps the items
     * already imported and stops before the next one. An item that cannot be
     * opened or is refused does not stop the rest, unless the refusal is about
     * the vault or the device rather than the item, [PICK_STOPPING_STATUSES]:
     * the rest would meet the same full disk or damaged vault, so none of them
     * opens an import. The imported items go into [albumId] in one placement,
     * and one line then says what happened to the whole pick; no line follows
     * a lock, whose route change drops it anyway.
     *
     * [open] opens the item at an index. The picked items stay with the host,
     * which never keeps them past this call, `ANDROID.md` §14.2.
     */
    suspend fun importAll(
        importer: MediaImporter,
        count: Int,
        albumId: ByteArray?,
        open: suspend (Int) -> PickedMedia?,
    ): List<MediaImporter.Outcome> {
        val outcomes = mutableListOf<MediaImporter.Outcome>()
        tracked("import") { token ->
            for (index in 0 until count) {
                if (cancellationRequested(token) || repository.state.value !is VaultState.Unlocked) break
                _activeOperation.update { current ->
                    current?.takeIf { it.id == token }?.copy(
                        processed = 0,
                        total = 0,
                        stage = 1,
                        cancellable = true,
                        item = index + 1,
                        items = count,
                    ) ?: current
                }
                val outcome = importItem(importer, token) { open(index) }
                outcomes += outcome
                if (outcome is MediaImporter.Outcome.Refused && outcome.status in PICK_STOPPING_STATUSES) break
            }
        } ?: return outcomes
        if (outcomes.isEmpty() || repository.state.value !is VaultState.Unlocked) return outcomes
        val summary = importSummary(outcomes, count, intoAlbum = albumId != null)
        val imported = outcomes.filterIsInstance<MediaImporter.Outcome.Imported>().map { it.objectId }
        if (albumId != null && imported.isNotEmpty()) {
            placeObjectsInAlbum(albumId, imported) { report(summary) }
        } else {
            reportImport(summary)
        }
        return outcomes
    }

    /** Closes the runtime, which a finishing host does. */
    suspend fun shutdown() {
        withContext(Dispatchers.Default) { repository.shutdown() }
    }

    private suspend fun <T> tracked(name: String, body: suspend (Long) -> T): T? {
        if (_activeOperation.value != null) {
            say("Finish the current operation first.")
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

    /** Imports one picked item under the operation that [token] owns. */
    private suspend fun importItem(
        importer: MediaImporter,
        token: Long,
        open: suspend () -> PickedMedia?,
    ): MediaImporter.Outcome = try {
        withContext(Dispatchers.Default) {
            importer.import(
                repository = repository,
                source = open(),
                onProgress = { updateOperation(token, it, keepCancellableOnSuccess = true) },
                cancelRequested = { cancellationRequested(token) },
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ChurFailure) {
        MediaImporter.Outcome.Refused(failure.status)
    } catch (_: Exception) {
        MediaImporter.Outcome.Refused(ChurStatus.INTERNAL_FAILURE)
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

    /** Every object `CATALOG_SCHEMA_V1.md` §16.2 keeps in the quarantine scope, whichever scan put it there. */
    private suspend fun quarantinedCount(): Long = withContext(Dispatchers.Default) {
        repository.page(ObjectQuery(QueryScope.QUARANTINE, limit = 1)).totalCount
    }

    /**
     * The timeline rows a scan left Incomplete or Unsupported, `DESIGN.md` §20.1.
     *
     * Import time never changes, so under that sort no row moves between two
     * pages and the restart after a `catalog_generation` change that
     * `CATALOG_SCHEMA_V1.md` §16.2 requires is not needed: a concurrent write
     * only adds or removes rows, which a count of the present state should
     * see. 500 is the largest page `CATALOG_SCHEMA_V1.md` §16.2 allows.
     */
    private suspend fun unverifiableCount(): Long {
        var query = ObjectQuery(sort = QuerySort.IMPORT_DESC, limit = 500)
        var count = 0L
        while (true) {
            val page = withContext(Dispatchers.Default) { repository.page(query) }
            count += page.objects.count { projection ->
                when (PresentedState.of(projection)) {
                    PresentedState.INCOMPLETE, PresentedState.UNSUPPORTED -> true
                    else -> false
                }
            }
            query = query.copy(cursor = page.nextCursor ?: return count)
        }
    }

    /**
     * The result line of a whole-vault check, in the voice of `DESIGN.md` §27.
     *
     * Each problem is named by its `DESIGN.md` §20.1 state, the name the
     * library shows on the same item.
     */
    private fun verificationSummary(checked: Long, corrupt: Boolean, quarantined: Long, unverifiable: Long): String {
        val problems = buildList {
            if (corrupt) add("at least 1 corrupt and no longer shown in the library")
            if (quarantined > 0) add("$quarantined quarantined (see Browse > Quarantine)")
            if (unverifiable > 0) add("$unverifiable incomplete or in an unsupported format (marked in the library)")
        }
        val checkedLine = "Checked $checked ${if (checked == 1L) "item" else "items"}."
        return if (problems.isEmpty()) {
            "$checkedLine No problems found."
        } else {
            "$checkedLine Some items could not be verified: ${problems.joinToString("; ")}."
        }
    }

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

    /**
     * Reads the current query again, as far as the grid had paged it.
     *
     * A reload that read only the first page cut the grid back to it. Past
     * that page the grid lost its place, and the viewer lost the item on
     * screen from its pages, so a swipe did nothing (`DESIGN.md` §13.1).
     */
    private suspend fun reload() {
        if (repository.state.value is VaultState.Unlocked) {
            val query = currentQuery
            val revision = queryRevision
            val result = if (query.scope == QueryScope.SEARCH && query.terms.isNullOrBlank()) {
                ObjectPage(emptyList(), 0, 0, null)
            } else pageThrough(query, _page.value.objects.size)
            if (revision == queryRevision) _page.value = result
        }
    }

    /**
     * The first page of [query], and the pages after it until [rows] rows are
     * loaded or the scope ends, `CATALOG_SCHEMA_V1.md` §16.2.
     *
     * A page read at another `catalog_generation` restarts the scope, as §16.2
     * requires, because a row whose sort key changed can be skipped or come
     * twice. That happens once; a second change keeps the rows read at one
     * generation, and the grid pages on from there.
     */
    private suspend fun pageThrough(query: ObjectQuery, rows: Int): ObjectPage =
        withContext(Dispatchers.Default) {
            var result = repository.page(query)
            var restarted = false
            while (result.objects.size < rows) {
                val cursor = result.nextCursor ?: break
                val next = repository.page(query.copy(cursor = cursor))
                if (next.catalogGeneration == result.catalogGeneration) {
                    result = next.copy(objects = result.objects + next.objects)
                } else if (!restarted) {
                    restarted = true
                    result = repository.page(query)
                } else {
                    break
                }
            }
            result
        }

    private suspend fun refreshAlbums() {
        _albums.value = withContext(Dispatchers.Default) { repository.albums() }
    }

    /**
     * Ends a session a new credential replaces, §8 of `PLAINTEXT_LIFECYCLE.md`.
     *
     * What this controller derived from the session goes with it: its pending
     * work, and step 7's projections and decoded images, which would otherwise
     * show one identity's albums, tags, slots or sharing inside the next until
     * each reloads - the cross-identity surface `DECOY_VAULT.md` §10 forbids.
     *
     * It locks the session here rather than leaving that to the repository's
     * next open, for two reasons. The lock waits behind the old session's
     * calls already in flight, so their results land before the clear and
     * not after it. And a device unlock the user then cancels leaves the
     * vault locked on both sides, not open in Rust with nothing on screen.
     *
     * Every entry point calls it before it reads the epoch, and before
     * [beginHostActivity], whose count the clear resets.
     */
    private suspend fun endOpenSession() {
        if (repository.state.value !is VaultState.Unlocked) return
        lockEpoch += 1
        withContext(Dispatchers.Default) { repository.lock(LockReason.USER) }
        clearPrivateProjections()
    }

    private suspend fun clearPrivateProjections() {
        _activeOperation.value = null
        exports.cancelPending()
        currentQuery = ObjectQuery()
        queryRevision++
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
        // Losing an unacknowledged phrase to a lock is still intended, but it
        // no longer strands a slot: the repository's lock discards the slot
        // staged on the session, or abandons the creation waiting for the
        // phrase, so nothing the user did not confirm stays committed.
        // `RECOVERY.md` §2 shows the phrase exactly once, and §8 there is how
        // a user gets another one.
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
            setRoute(if (_appLockEnabled.value) AppRoute.AppUnlock else AppRoute.PublicShell)
            return
        }
        if (target == AppRoute.AppUnlock || target == AppRoute.AppRecover) {
            exports.cancelPending()
            withContext(Dispatchers.Default) { repository.lock(LockReason.USER) }
            privacy.setEnabled(false)
            setRoute(AppRoute.PublicShell)
        } else {
            enterVault()
        }
    }

    private suspend fun enterVault() {
        privacy.setEnabled(true)
        setRoute(AppRoute.Vault)
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
     * boundary carries only a status, so the message is [userCopy] of that
     * status: the feature layer's copy of "Layer mapping", never the name of
     * the code. The sync transport carries its stable status in a
     * [SyncTransportFailure] rather than a [ChurFailure], and it gets
     * [syncCopy]: a refused bootstrap secret or an unreachable server at sync
     * setup is a status the user can act on, not an internal failure, and a
     * refusal by the server is not a failed unlock.
     *
     * The last catch is the backstop. Every action a surface can invoke goes
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
     *
     * The work carries the [routeVisit] it started in, so what it reports
     * reaches the screen it was started from or nothing: see [post].
     */
    private fun guarded(
        clearMessage: Boolean = true,
        copy: (ChurStatus) -> String = ::userCopy,
        body: suspend () -> Unit,
    ) {
        scope.launch(RouteVisit(routeVisit)) {
            try {
                if (clearMessage) say(null)
                body()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: ChurFailure) {
                say(copy(failure.status), security = failure.status in SECURITY_STATUSES)
            } catch (failure: SyncTransportFailure) {
                say(syncCopy(failure.status), security = failure.status in SECURITY_STATUSES)
            } catch (_: Exception) {
                say(userCopy(ChurStatus.INTERNAL_FAILURE))
            }
        }
    }

    /**
     * Runs an unlock or a recovery as [guarded] does, with [unlocking] set.
     *
     * The flag is set on Main before the work starts, as [requestPhrase]
     * sets its own, so a second tap in the same frame is ignored rather than
     * starting a second key derivation. It clears before [guarded] reports a
     * refusal, so the form shows the refusal once it is no longer busy.
     */
    private fun attempt(copy: (ChurStatus) -> String = ::userCopy, body: suspend () -> Unit) {
        if (_unlocking.value) return
        _unlocking.value = true
        guarded(copy = copy) {
            try {
                body()
            } finally {
                _unlocking.value = false
            }
        }
    }

    /**
     * Moves to [next] and drops what the last screen was showing.
     *
     * Every route write goes through here, so an outcome or a refusal never
     * outlives the screen it was for. A second vault set up from Settings
     * used to open its creation form with the vault's last outcome painted
     * in error red, and an unlock screen read any leftover as a failed
     * attempt.
     */
    private fun setRoute(next: AppRoute) {
        routeVisit += 1
        _notice.value = null
        _formError.value = null
        _route.value = next
    }

    /**
     * Shows [text] on the current screen if it is still the one [visit] was
     * taken on, and drops it otherwise.
     *
     * An action that ends after the user moved on, or after a lock, reports
     * to a screen it was not started from; an export that fails after the
     * lock would otherwise show its outcome on the unlock form as a refused
     * credential. A credential form gets [formError]; every other route gets
     * a [notice], and `null` clears whichever the route shows.
     */
    private fun post(
        text: String?,
        visit: Long = routeVisit,
        security: Boolean = false,
        action: Pair<String, () -> Unit>? = null,
    ) {
        if (visit != routeVisit) return
        if (_route.value in FORM_ROUTES) {
            _formError.value = text
        } else {
            _notice.value = text?.let { Notice(++noticeCount, it, security, action) }
        }
    }

    /** [post], from work that [guarded] started, for the visit it started in. */
    private suspend fun say(
        text: String?,
        security: Boolean = false,
        action: Pair<String, () -> Unit>? = null,
    ) {
        post(text, currentCoroutineContext()[RouteVisit]?.number ?: routeVisit, security, action)
    }

    /** The [routeVisit] a piece of guarded work started in. */
    private class RouteVisit(val number: Long) : AbstractCoroutineContextElement(RouteVisit) {
        companion object Key : CoroutineContext.Key<RouteVisit>
    }

    private companion object {
        /** The routes whose screen is a credential form, which reads [formError]. */
        val FORM_ROUTES: Set<AppRoute> = setOf(
            AppRoute.CreateVault,
            AppRoute.RestoreBackup,
            AppRoute.Unlock,
            AppRoute.AppUnlock,
            AppRoute.Recover,
            AppRoute.AppRecover,
        )

        /**
         * The failures `DESIGN.md` §26 keeps on screen until dismissed.
         *
         * They are the security states of `ERROR_MODEL.md`: the sync verdicts
         * that stop sync until the fork state clears, and the integrity
         * verdicts whose copy tells the user to restore from a backup.
         */
        val SECURITY_STATUSES: Set<ChurStatus> = setOf(
            ChurStatus.SYNC_CHAIN_FORK,
            ChurStatus.SYNC_HEAD_ROLLBACK,
            ChurStatus.VAULT_CORRUPT,
            ChurStatus.CATALOG_CORRUPT,
            ChurStatus.OBJECT_CORRUPT,
        )

        /**
         * The refusals that end a pick in [importAll].
         *
         * `ERROR_MODEL.md` puts their cause in the device or the vault, not in
         * the picked item: protected data the locked device keeps closed, a
         * volume that is full, detached or unwritable, and a vault or a
         * library that failed its integrity check. Every later item would
         * open an import into the same state, and write more to a vault
         * already reported damaged.
         *
         * `IO_FAILURE` is not one of them: a failed read of the picked item
         * itself also maps to it, `ANDROID.md` §27 and `IOS.md` §29, as when
         * a cloud-backed item loses its network. `ANDROID.md` §14.4 has import
         * handle such transient network errors, so that item fails alone and
         * the rest of the pick goes on.
         */
        val PICK_STOPPING_STATUSES: Set<ChurStatus> = setOf(
            ChurStatus.PROTECTED_DATA_UNAVAILABLE,
            ChurStatus.VAULT_CORRUPT,
            ChurStatus.CATALOG_CORRUPT,
            ChurStatus.STORAGE_UNAVAILABLE,
        )

        /** Fast enough to feel immediate, slow enough not to spin a core. */
        const val POLL_INTERVAL_MS = 50L

        /** A count of library items for a notice: "1 item", "3 items". */
        fun items(count: Int): String = if (count == 1) "1 item" else "$count items"

        /**
         * The one line an import ends with, `MEDIA_PIPELINE.md` §13.
         *
         * A pick of one item keeps the line it always had. A larger pick
         * counts what its items ended as, "11 imported, 1 could not be
         * opened", and names none of them: `DESIGN.md` §26 keeps file names
         * out of a snackbar. The items a cancel kept from starting count as
         * cancelled, and the ones a [PICK_STOPPING_STATUSES] refusal kept
         * from starting count as skipped.
         *
         * §13 tells storage full, permission denied, an unsupported codec and
         * corruption apart, and the next step differs for each, so a refused
         * item adds the [userCopy] line a pick of one shows. A pick gives one
         * reason: an integrity verdict first, so a damaged vault is not hidden
         * behind a lesser refusal, then the refusal that ended the pick, then
         * the first one.
         */
        fun importSummary(outcomes: List<MediaImporter.Outcome>, requested: Int, intoAlbum: Boolean): String {
            val single = outcomes.singleOrNull()
            if (requested == 1 && single != null) return when (single) {
                is MediaImporter.Outcome.Imported -> when {
                    single.previewsSkipped && intoAlbum -> "Imported original into album; remaining previews cancelled."
                    single.previewsSkipped -> "Imported original; remaining previews cancelled."
                    intoAlbum -> "Imported into album."
                    else -> "Imported into vault."
                }
                is MediaImporter.Outcome.TooLarge -> single.reason
                MediaImporter.Outcome.Unreadable -> "That file could not be opened."
                is MediaImporter.Outcome.Refused -> userCopy(single.status)
            }
            val imported = outcomes.filterIsInstance<MediaImporter.Outcome.Imported>()
            val unreadable = outcomes.count { it == MediaImporter.Outcome.Unreadable }
            val tooLarge = outcomes.count { it is MediaImporter.Outcome.TooLarge }
            val refused = outcomes.filterIsInstance<MediaImporter.Outcome.Refused>()
            val failed = refused.filter { it.status != ChurStatus.CANCELLED }
            // A stopping refusal ends the loop, so it is always the last one.
            val stopped = failed.lastOrNull()?.status in PICK_STOPPING_STATUSES
            val unopened = requested - outcomes.size
            val skipped = if (stopped) unopened else 0
            val cancelled = refused.size - failed.size + unopened - skipped
            val counts = listOfNotNull(
                "${imported.size} imported",
                "$unreadable could not be opened".takeIf { unreadable > 0 },
                "$tooLarge too large to import".takeIf { tooLarge > 0 },
                "${failed.size} could not be imported".takeIf { failed.isNotEmpty() },
                "$cancelled cancelled".takeIf { cancelled > 0 },
                "$skipped skipped".takeIf { skipped > 0 },
            ).joinToString(", ")
            val previews = if (imported.any { it.previewsSkipped }) "; remaining previews cancelled" else ""
            val reason = failed.firstOrNull { it.status in SECURITY_STATUSES }
                ?: if (stopped) failed.last() else failed.firstOrNull()
            return "$counts$previews." + reason?.let { " " + userCopy(it.status) }.orEmpty()
        }

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

/**
 * One outcome message, the snackbar of `DESIGN.md` §26.
 *
 * [id] tells two equal texts apart, so the same outcome twice is shown twice.
 * A [security] notice stays until the user dismisses it or takes its
 * [action]; any other one times out.
 */
data class Notice(
    val id: Long,
    val text: String,
    val security: Boolean = false,
    /** A label and what it does, such as an undo. */
    val action: Pair<String, () -> Unit>? = null,
)
