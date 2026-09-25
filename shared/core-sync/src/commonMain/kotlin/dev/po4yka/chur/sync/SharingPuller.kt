package dev.po4yka.chur.sync

import dev.po4yka.chur.ffi.ChurVault

/** Downloads complete opaque share packages and gives them to the native verifier. */
public class SharingPuller internal constructor(
    private val client: SyncClient,
    private val accept: suspend (ByteArray) -> Boolean,
) {
    /** Uses the unlocked native session that owns the recipient catalog. */
    public constructor(client: SyncClient, session: Long) : this(
        client,
        { packageBytes ->
            ChurVault.acceptSharePackage(session, packageBytes)
            true
        },
    )

    /** Accepts addressed packages in server order, stopping if the vault locks. */
    public suspend fun pullOnce(vaultId: ByteArray): Int {
        val packages = client.sharingPackages(vaultId)
        var accepted = 0
        for (packageBytes in packages) {
            if (!accept(packageBytes)) break
            accepted++
        }
        return accepted
    }
}
