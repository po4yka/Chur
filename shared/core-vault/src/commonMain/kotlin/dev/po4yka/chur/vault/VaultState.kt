package dev.po4yka.chur.vault

import dev.po4yka.chur.core.model.ChurStatus

/**
 * What the application knows about the vault right now.
 *
 * `docs/security/PROVISIONING.md` §2 has first launch open the public shell
 * with no vault and none created until the user asks, so [NoVault] is a
 * first-class state rather than an error. `DESIGN.md` §14 makes the gate
 * between [Locked] and [Unlocked] the only way in.
 */
sealed interface VaultState {
    /** The runtime is not open yet. */
    data object Starting : VaultState

    /** The storage root holds no vault. The shell offers creation. */
    data object NoVault : VaultState

    /** A vault exists and is closed. */
    data class Locked(val lastFailure: ChurStatus? = null) : VaultState

    /** A vault is being created, `PROVISIONING.md` §3. */
    data object Creating : VaultState

    /** A session is open. */
    data class Unlocked(val generation: Long) : VaultState
}

/**
 * The lock policy of `DESIGN.md` §14 and `PLAINTEXT_LIFECYCLE.md` §8.
 *
 * Four triggers exist and the product names all four: the user asks, the idle
 * timer expires, the application leaves the foreground, and the panic gesture
 * fires. The policy holds only the two the user configures; the other two are
 * unconditional and are not settings.
 */
data class LockPolicy(
    /** Idle milliseconds before a session locks; zero locks immediately. */
    val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    /** Whether leaving the foreground locks at once rather than starting the
     *  idle timer. */
    val lockOnBackground: Boolean = true,
) {
    init {
        require(idleTimeoutMs >= 0) { "an idle timeout is not negative" }
    }

    companion object {
        /** Two minutes, which `DESIGN.md` §14 names as the default. */
        const val DEFAULT_IDLE_TIMEOUT_MS: Long = 120_000

        /** Lock as soon as the session stops being used. */
        val IMMEDIATE = LockPolicy(idleTimeoutMs = 0)
    }
}

/**
 * The decision an idle check reaches.
 *
 * It is a pure function of the policy and two times, so the timer that drives
 * it is testable without waiting.
 */
enum class LockDecision {
    /** Keep the session open. */
    KEEP,

    /** Lock now. */
    LOCK,
}

/**
 * Whether an idle session should lock.
 *
 * A zero timeout locks as soon as any time has passed, which is what
 * [LockPolicy.IMMEDIATE] means: the session survives the operation that opened
 * it and no longer.
 */
fun idleDecision(policy: LockPolicy, lastUsedMs: Long, nowMs: Long): LockDecision {
    val idle = nowMs - lastUsedMs
    return if (idle >= policy.idleTimeoutMs) LockDecision.LOCK else LockDecision.KEEP
}
