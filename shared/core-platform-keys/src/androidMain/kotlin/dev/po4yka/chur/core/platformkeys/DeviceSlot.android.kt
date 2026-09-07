package dev.po4yka.chur.core.platformkeys

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.po4yka.chur.core.model.ChurStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Android Keystore slot prototype, `docs/security/KEY_SLOTS.md` section 4.
 *
 * The wrapping key is a non-exportable AES-256-GCM key the Keystore generates
 * and never releases. Rust supplies the canonical AAD and receives the wrapped
 * bytes; it is the only side that encodes a record.
 *
 * `KEY_SLOT_BODIES_V1.md` section 5 gives this family `wrap_suite_id` 0x0002,
 * because its AEAD runs here rather than in Rust.
 */
public actual class DeviceSlot public actual constructor(identifier: ByteArray) {
    private val alias: String = platformAlias(identifier)

    /**
     * Generates the Keystore key.
     *
     * The policy decides the authentication requirement. In `CONVENIENT` mode
     * biometry or the device credential opens the key, so the device unlock
     * code is a vault credential. In `STRICT` mode only a currently enrolled
     * biometric does, and the key is invalidated when the biometric set
     * changes.
     *
     * StrongBox is requested first and the generation falls back to TEE when
     * the device has none, because `KEY_SLOTS.md` section 4 makes StrongBox
     * optional with an explicit fallback rather than a silent one.
     */
    public actual fun provision(policy: DeviceSlotPolicy) {
        if (isProvisioned()) {
            throw DeviceSlotException(
                ChurStatus.CONFLICT,
                "a Keystore key already exists for this slot identity",
            )
        }
        try {
            generate(policy, strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(policy, strongBox = false)
        }
    }

    private fun generate(policy: DeviceSlotPolicy, strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            // The cipher chooses the nonce, so one key never reuses one.
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val authenticators = when (policy) {
                DeviceSlotPolicy.CONVENIENT ->
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                DeviceSlotPolicy.STRICT -> KeyProperties.AUTH_BIOMETRIC_STRONG
            }
            // Timeout zero means every use is authorized separately.
            builder.setUserAuthenticationParameters(0, authenticators)
        } else {
            // API 29 has no authenticator-type parameter. A negative duration
            // means per-use biometric authorization; a positive one is the only
            // way to admit the device credential. The setter is deprecated in
            // favour of the API 30 call above, which minSdk 29 cannot use.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(
                when (policy) {
                    DeviceSlotPolicy.CONVENIENT -> LEGACY_CREDENTIAL_WINDOW_SECONDS
                    DeviceSlotPolicy.STRICT -> -1
                },
            )
        }
        if (policy == DeviceSlotPolicy.STRICT) {
            builder.setInvalidatedByBiometricEnrollment(true)
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(builder.build())
            generator.generateKey()
        } catch (cause: StrongBoxUnavailableException) {
            throw cause
        } catch (cause: Exception) {
            throw DeviceSlotException(
                ChurStatus.PLATFORM_KEY_UNAVAILABLE,
                "the Keystore refused to generate a slot key",
                cause,
            )
        }
    }

    public actual fun isProvisioned(): Boolean = keyStore().containsAlias(alias)

    public actual fun destroy() {
        val store = keyStore()
        if (store.containsAlias(alias)) {
            try {
                store.deleteEntry(alias)
            } catch (cause: Exception) {
                throw DeviceSlotException(
                    ChurStatus.PLATFORM_KEY_UNAVAILABLE,
                    "the Keystore refused to delete a slot key",
                    cause,
                )
            }
        }
    }

    /**
     * Wraps a root secret under the Keystore key.
     *
     * The user authorizes the operation first. [provision] builds the key with
     * `setUserAuthenticationRequired`, so the Keystore refuses the AEAD until a
     * `BiometricPrompt` has authorized it; without that step every call here
     * failed with `KEY_USER_NOT_AUTHENTICATED`, which is what made the whole
     * slot family unreachable on a device.
     *
     * @param root the 32-byte `VaultRootSecret`.
     * @param slotAad the canonical AAD Rust produced for this slot generation.
     * @return the GCM nonce the cipher chose and the 48 wrapped bytes.
     */
    public suspend fun wrap(
        activity: FragmentActivity,
        policy: DeviceSlotPolicy,
        prompt: DeviceSlotPrompt,
        root: ByteArray,
        slotAad: ByteArray,
    ): KeystoreWrapped {
        require(root.size == ROOT_BYTES) { "a root secret is 32 bytes" }
        val cipher = authorized(activity, policy, prompt) {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        }
        try {
            cipher.updateAAD(slotAad)
            val wrapped = cipher.doFinal(root)
            return KeystoreWrapped(gcmNonce = cipher.iv.copyOf(), wrappedRootSecret = wrapped)
        } catch (cause: Exception) {
            throw classify(cause, "the Keystore refused to wrap a root secret")
        }
    }

    /**
     * Unwraps a root secret, after the same authorization [wrap] requires.
     *
     * @throws DeviceSlotException with [ChurStatus.AUTHENTICATION_FAILED] when
     * the tag does not verify, which is the same external result a wrong
     * password produces.
     */
    public suspend fun unwrap(
        activity: FragmentActivity,
        policy: DeviceSlotPolicy,
        prompt: DeviceSlotPrompt,
        wrapped: KeystoreWrapped,
        slotAad: ByteArray,
    ): ByteArray {
        val cipher = authorized(activity, policy, prompt) {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, wrapped.gcmNonce))
            }
        }
        try {
            cipher.updateAAD(slotAad)
            return cipher.doFinal(wrapped.wrappedRootSecret)
        } catch (cause: Exception) {
            throw classify(cause, "the Keystore refused to unwrap a root secret")
        }
    }

    /**
     * Runs the prompt and returns a cipher the Keystore will accept.
     *
     * Which half runs depends on how [generate] built the key, and the two are
     * not interchangeable. A key with `setUserAuthenticationParameters(0, …)`
     * authorizes one operation, so the cipher must travel into the prompt as a
     * `CryptoObject` and come back out of it. The API 29 `CONVENIENT` key is
     * the other kind: a validity window is the only way that level admits the
     * device credential, and a window key cannot carry a `CryptoObject` at all,
     * so the prompt runs first and the cipher is built inside the window it
     * opened.
     */
    private suspend fun authorized(
        activity: FragmentActivity,
        policy: DeviceSlotPolicy,
        prompt: DeviceSlotPrompt,
        init: () -> Cipher,
    ): Cipher {
        if (!authorizesPerUse(policy)) {
            authenticate(activity, policy, prompt, crypto = null)
            return initializing(init)
        }
        val crypto = BiometricPrompt.CryptoObject(initializing(init))
        return authenticate(activity, policy, prompt, crypto)?.cipher
            ?: throw DeviceSlotException(
                ChurStatus.PLATFORM_KEY_UNAVAILABLE,
                "the device authorization returned no cipher",
            )
    }

    /** Whether [generate] gave this policy a key that authorizes one use. */
    private fun authorizesPerUse(policy: DeviceSlotPolicy): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || policy == DeviceSlotPolicy.STRICT

    private fun initializing(init: () -> Cipher): Cipher = try {
        init()
    } catch (cause: Exception) {
        throw classify(cause, "the Keystore refused to start a slot operation")
    }

    /** Shows the prompt and suspends until the platform decides. */
    private suspend fun authenticate(
        activity: FragmentActivity,
        policy: DeviceSlotPolicy,
        prompt: DeviceSlotPrompt,
        crypto: BiometricPrompt.CryptoObject?,
    ): BiometricPrompt.CryptoObject? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val info = promptInfo(policy, prompt)
            val dialog = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        if (continuation.isActive) continuation.resume(result.cryptoObject)
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        // The message is the platform's and may name the user's
                        // enrolled factor, so it is dropped: `ERROR_MODEL.md`
                        // keeps a private value out of a failure, and the code
                        // carries everything a caller may branch on.
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                DeviceSlotException(
                                    statusOf(code),
                                    "the device authorization did not complete",
                                ),
                            )
                        }
                    }

                    // One mismatch does not end the prompt: the platform keeps
                    // it up and the user tries again, and only an error above
                    // is terminal.
                    override fun onAuthenticationFailed() = Unit
                },
            )
            continuation.invokeOnCancellation { dialog.cancelAuthentication() }
            if (crypto == null) dialog.authenticate(info) else dialog.authenticate(info, crypto)
        }
    }

    private fun promptInfo(
        policy: DeviceSlotPolicy,
        prompt: DeviceSlotPrompt,
    ): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(prompt.title)
            .setSubtitle(prompt.subtitle)
        when (policy) {
            DeviceSlotPolicy.CONVENIENT -> builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            DeviceSlotPolicy.STRICT -> {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                // A prompt that admits no device credential must carry its own
                // way out, and the platform rejects one that sets neither.
                builder.setNegativeButtonText(prompt.cancel)
            }
        }
        return builder.build()
    }

    /** The stable code behind a `BiometricPrompt` error. */
    private fun statusOf(code: Int): ChurStatus = when (code) {
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_CANCELED,
        -> ChurStatus.CANCELLED
        // The factor is spent for now, which is a refusal rather than an
        // absence: the slot still exists and a later attempt can open it.
        BiometricPrompt.ERROR_LOCKOUT,
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
        -> ChurStatus.AUTHENTICATION_FAILED
        else -> ChurStatus.PLATFORM_KEY_UNAVAILABLE
    }

    private fun key(): javax.crypto.SecretKey {
        val entry = keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry
            ?: throw DeviceSlotException(
                ChurStatus.PLATFORM_KEY_UNAVAILABLE,
                "no Keystore key exists for this slot identity",
            )
        return entry.secretKey
    }

    private fun keyStore(): KeyStore =
        try {
            KeyStore.getInstance(PROVIDER).apply { load(null) }
        } catch (cause: Exception) {
            throw DeviceSlotException(
                ChurStatus.PLATFORM_KEY_UNAVAILABLE,
                "the Android Keystore is not available",
                cause,
            )
        }

    private fun classify(cause: Exception, detail: String): DeviceSlotException =
        when (cause) {
            is DeviceSlotException -> cause
            // The user removed or replaced the factor. Recovery is a portable
            // slot, never a silent vault deletion.
            is KeyPermanentlyInvalidatedException ->
                DeviceSlotException(ChurStatus.PLATFORM_KEY_INVALIDATED, detail, cause)
            // The prompt has not run, or its authorization expired.
            is UserNotAuthenticatedException ->
                DeviceSlotException(ChurStatus.PLATFORM_KEY_UNAVAILABLE, detail, cause)
            // A wrong key, a changed AAD, and damaged ciphertext are one result.
            is AEADBadTagException ->
                DeviceSlotException(ChurStatus.AUTHENTICATION_FAILED, detail, cause)
            else -> DeviceSlotException(ChurStatus.PLATFORM_KEY_UNAVAILABLE, detail, cause)
        }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val ROOT_BYTES = 32

        // API 29 admits the device credential only through a positive window.
        // It is short on purpose: a longer one would keep the key usable after
        // the user walked away.
        const val LEGACY_CREDENTIAL_WINDOW_SECONDS = 10
    }
}

/**
 * The words the device authorization shows.
 *
 * The host supplies them because the host is what has resources: a string
 * frozen in this module could not be translated and would put the vault's own
 * vocabulary in a module that `docs/ARCHITECTURE.md` §9 keeps free of it.
 */
public class DeviceSlotPrompt(
    /** The prompt's title. */
    public val title: String,
    /** One line under it, saying what the authorization opens. */
    public val subtitle: String,
    /**
     * The way out.
     *
     * Shown only under [DeviceSlotPolicy.STRICT]: that policy admits no device
     * credential, and a prompt offering neither is one the platform rejects.
     */
    public val cancel: String,
)

/**
 * What an Android slot body carries, `KEY_SLOT_BODIES_V1.md` section 5.
 *
 * This type does not encode that record. Rust does.
 */
public class KeystoreWrapped(
    /** The 96-bit GCM nonce the cipher chose. */
    public val gcmNonce: ByteArray,
    /** The 32-byte root plus its 16-byte tag. */
    public val wrappedRootSecret: ByteArray,
) {
    /** Prints no bytes: a wrapped root is not a diagnostic value. */
    override fun toString(): String = "KeystoreWrapped(<redacted>)"
}
