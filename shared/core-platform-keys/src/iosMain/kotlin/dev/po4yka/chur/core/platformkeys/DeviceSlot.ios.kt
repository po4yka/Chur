package dev.po4yka.chur.core.platformkeys

import dev.po4yka.chur.core.model.ChurStatus
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlBiometryCurrentSet
import platform.Security.kSecAccessControlUserPresence
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.Security.kSecRandomDefault
import platform.posix.memcpy

/**
 * The Apple Keychain slot prototype, `docs/security/KEY_SLOTS.md` section 5.
 *
 * The Keychain holds a random `DeviceUnlockSecret` as a `ThisDeviceOnly` item.
 * Rust derives `AppleDeviceKEK` from it under `chur/v1/slot/apple-device-kek`
 * and performs the AEAD, which is what keeps the family test-vectorable at the
 * Rust envelope layer rather than inside a platform service.
 *
 * The alternative model that section leaves open, wrapped root bytes held
 * directly as the Keychain secret, would take `keychain_profile_id` 0x0002 and
 * its own ADR. This is 0x0001.
 */
@OptIn(ExperimentalForeignApi::class)
public actual class DeviceSlot public actual constructor(identifier: ByteArray) {
    private val account: String = platformAlias(identifier)

    /**
     * Creates the device-held secret.
     *
     * `CONVENIENT` uses `userPresence`, which biometry or the device passcode
     * satisfies, so the passcode opens this slot. `STRICT` uses
     * `biometryCurrentSet`, which excludes the passcode and invalidates the
     * item when the biometric set changes.
     *
     * The item is `WhenUnlockedThisDeviceOnly`, so it never enters an encrypted
     * backup or another device, which is why `KEY_SLOTS.md` section 10 marks
     * this family not portable.
     */
    public actual fun provision(policy: DeviceSlotPolicy) {
        if (isProvisioned()) {
            throw DeviceSlotException(
                ChurStatus.CONFLICT,
                "a Keychain item already exists for this slot identity",
            )
        }
        val secret = randomBytes(SECRET_BYTES)
        val flags = when (policy) {
            DeviceSlotPolicy.CONVENIENT -> kSecAccessControlUserPresence
            DeviceSlotPolicy.STRICT -> kSecAccessControlBiometryCurrentSet
        }
        memScoped {
            val error = alloc<CFTypeRefVar>()
            val access = SecAccessControlCreateWithFlags(
                kCFAllocatorDefault,
                kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                flags,
                error.ptr.reinterpret(),
            ) ?: throw DeviceSlotException(
                ChurStatus.PLATFORM_KEY_UNAVAILABLE,
                "the device offers no factor for the requested policy",
            )

            val query = newQuery()
            CFDictionarySetValue(query, kSecAttrAccessControl, access)
            val data = CFBridgingRetain(secret.toNSData())
            CFDictionarySetValue(query, kSecValueData, data)
            val status = SecItemAdd(query, null)
            CFRelease(data)
            CFRelease(access)
            CFRelease(query)
            secret.fill(0)
            if (status != errSecSuccess) {
                throw DeviceSlotException(
                    ChurStatus.PLATFORM_KEY_UNAVAILABLE,
                    "the Keychain refused to store a slot secret",
                )
            }
        }
    }

    public actual fun isProvisioned(): Boolean {
        val query = newQuery()
        val status = memScoped {
            val result = alloc<CFTypeRefVar>()
            val code = SecItemCopyMatching(query, result.ptr)
            result.value?.let { CFRelease(it) }
            code
        }
        CFRelease(query)
        return status == errSecSuccess
    }

    public actual fun destroy() {
        val query = newQuery()
        val status = SecItemDelete(query)
        CFRelease(query)
        if (status != errSecSuccess && status != errSecItemNotFound) {
            throw DeviceSlotException(
                ChurStatus.PLATFORM_KEY_UNAVAILABLE,
                "the Keychain refused to delete a slot secret",
            )
        }
    }

    /**
     * Releases the `DeviceUnlockSecret` after the access control is satisfied.
     *
     * The caller passes the bytes straight to Rust and clears them; nothing on
     * the Kotlin side retains them, and no view model, saved state, or log ever
     * sees them.
     *
     * @throws DeviceSlotException with [ChurStatus.PLATFORM_KEY_INVALIDATED]
     * when the item no longer opens, and [ChurStatus.PLATFORM_KEY_UNAVAILABLE]
     * when the factor is absent or the user did not authorize.
     */
    public fun releaseSecret(): ByteArray {
        val query = newQuery()
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val bytes = memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            when (status) {
                errSecSuccess -> {
                    val data = CFBridgingRelease(result.value) as? NSData
                        ?: throw DeviceSlotException(
                            ChurStatus.PLATFORM_KEY_UNAVAILABLE,
                            "the Keychain returned no slot secret",
                        )
                    data.toByteArray()
                }
                errSecItemNotFound -> throw DeviceSlotException(
                    ChurStatus.PLATFORM_KEY_INVALIDATED,
                    "the Keychain no longer holds this slot secret",
                )
                else -> throw DeviceSlotException(
                    ChurStatus.PLATFORM_KEY_UNAVAILABLE,
                    "the Keychain refused to release a slot secret",
                )
            }
        }
        CFRelease(query)
        if (bytes.size != SECRET_BYTES) {
            bytes.fill(0)
            throw DeviceSlotException(
                ChurStatus.PLATFORM_KEY_INVALIDATED,
                "the stored slot secret is not 32 bytes",
            )
        }
        return bytes
    }

    private fun newQuery(): CFMutableDictionaryRef {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
            ?: throw DeviceSlotException(
                ChurStatus.INTERNAL_FAILURE,
                "could not allocate a Keychain query",
            )
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFBridgingRetain(SERVICE))
        CFDictionarySetValue(query, kSecAttrAccount, CFBridgingRetain(account))
        return query
    }

    private fun randomBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        val status = out.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, count.toULong(), pinned.addressOf(0))
        }
        if (status != errSecSuccess) {
            // CRYPTOGRAPHY.md section 9: an RNG failure aborts the operation.
            // There is no fallback generator.
            throw DeviceSlotException(
                ChurStatus.INTERNAL_FAILURE,
                "the operating-system CSPRNG failed and there is no fallback",
            )
        }
        return out
    }

    private companion object {
        const val SERVICE = "dev.po4yka.chur"
        const val SECRET_BYTES = 32
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) {
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length)
        }
    }
    return out
}
