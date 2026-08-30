package dev.po4yka.chur.sync

import dev.po4yka.chur.ffi.ChurVault

/** Downloads complete opaque share packages and gives them to the native verifier. */
public class SharingPuller internal constructor(
    private val client: SyncClient,
    private val accept: (ByteArray) -> Unit,
) {
    /** Uses the unlocked native session that owns the recipient catalog. */
    public constructor(client: SyncClient, session: Long) : this(
        client,
        { packageBytes -> ChurVault.acceptSharePackage(session, packageBytes) },
    )

    /** Accepts every currently addressed package in server order. */
    public suspend fun pullOnce(vaultId: ByteArray): Int {
        val packages = client.sharingPackages(vaultId)
        packages.forEach(accept)
        return packages.size
    }
}
