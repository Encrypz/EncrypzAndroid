package com.example.encrypz.crypto

import javax.crypto.SecretKey

/** Versioned, authenticated filename encryption using a fresh GCM nonce for every upload. */
object VaultFileNames {
    private const val PREFIX = "encrypz-name-v1-"
    private const val SUFFIX = ".enc"
    private const val DOMAIN = "ENCRYPZ_FILENAME_V1:"

    fun encrypt(name: String, key: SecretKey): String {
        require(name.isNotBlank())
        val ciphertext = VaultCrypto.encryptPayload((DOMAIN + name).encodeToByteArray(), key)
        return PREFIX + VaultCrypto.toHex(ciphertext) + SUFFIX
    }

    // Legacy names remain readable. Recognized encrypted names must authenticate successfully.
    fun decryptOrLegacy(name: String, key: SecretKey): String {
        if (!name.startsWith(PREFIX)) return name
        require(name.endsWith(SUFFIX)) { "Invalid encrypted filename" }
        val ciphertext = VaultCrypto.fromHex(name.removePrefix(PREFIX).removeSuffix(SUFFIX))
        val plaintext = VaultCrypto.decryptPayload(ciphertext, key).decodeToString(throwOnInvalidSequence = true)
        require(plaintext.startsWith(DOMAIN)) { "Invalid filename payload" }
        return plaintext.removePrefix(DOMAIN).also { require(it.isNotBlank()) }
    }
}
