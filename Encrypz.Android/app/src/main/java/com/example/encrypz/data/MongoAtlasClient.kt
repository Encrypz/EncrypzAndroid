package com.example.encrypz.data

/** Legacy record name retained for local data compatibility. Database access is server-only. */
object MongoAtlasClient {
    data class MongoVaultUserRecord(
        val email: String,
        val passwordSaltHex: String,
        val passwordVerifierHex: String,
        val wrappedVaultKeyHex: String,
        val pinVerifierHex: String? = null,
        val hasLoggedInOnce: Boolean = true,
        val driveFolderId: String? = null,
        val googleRefreshToken: String? = null,
        val updatedAt: String = "",
        val credentialVersion: Int = 1
    )

}
