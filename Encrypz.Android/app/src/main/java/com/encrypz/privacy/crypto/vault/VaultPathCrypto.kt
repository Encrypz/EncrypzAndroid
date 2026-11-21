package com.encrypz.privacy.crypto.vault

import java.security.MessageDigest

/**
 * Encrypz Vault v2 directory obfuscation (`vault/XX/YYYY...`) using SHA-256 Base32
 * and domain-separated RFC 5297 AES-SIV `.epz` / `.epzl` filenames.
 */
object VaultPathCrypto {
    const val ROOT_DIR_ID = ""
    const val DATA_ROOT_DIR = "vault"
    const val VAULT_FILE_EXT = ".epz"
    const val VAULT_LONG_EXT = ".epzl"
    const val FOLDER_POINTER_FILE = "folder.epz"
    const val LONG_NAME_META_FILE = "meta.epzl"
    const val LONG_FILE_BLOB_FILE = "blob.epz"

    private val DIR_DOMAIN = "encrypz:dir:v2".toByteArray(Charsets.UTF_8)
    private val NAME_DOMAIN = "encrypz:name:v2".toByteArray(Charsets.UTF_8)

    fun hashDirectoryId(dirId: String, masterkey: VaultMasterkey.MasterkeySet): String {
        val encKey = masterkey.copyEncKey()
        val macKey = masterkey.copyMacKey()
        return try {
            val encryptedDirId = AesSiv.encrypt(
                encKey,
                macKey,
                dirId.toByteArray(Charsets.UTF_8),
                DIR_DOMAIN
            )
            val sha256Prefix20 = MessageDigest.getInstance("SHA-256")
                .digest(encryptedDirId)
                .copyOfRange(0, 20)
            VaultEncoding.base32Encode(sha256Prefix20, pad = false)
        } finally {
            encKey.fill(0)
            macKey.fill(0)
        }
    }

    fun storagePathSegments(dirId: String, masterkey: VaultMasterkey.MasterkeySet): List<String> {
        val hash = hashDirectoryId(dirId, masterkey)
        return listOf(DATA_ROOT_DIR, hash.substring(0, 2), hash.substring(2))
    }

    fun encryptName(
        parentDirId: String,
        cleartextName: String,
        masterkey: VaultMasterkey.MasterkeySet
    ): String {
        require(cleartextName.isNotBlank()) { "Filename cannot be blank" }
        val encKey = masterkey.copyEncKey()
        val macKey = masterkey.copyMacKey()
        return try {
            val cipher = AesSiv.encrypt(
                encKey,
                macKey,
                cleartextName.toByteArray(Charsets.UTF_8),
                NAME_DOMAIN,
                parentDirId.toByteArray(Charsets.UTF_8)
            )
            VaultEncoding.base64UrlEncode(cipher, pad = false) + VAULT_FILE_EXT
        } finally {
            encKey.fill(0)
            macKey.fill(0)
        }
    }

    fun decryptName(
        parentDirId: String,
        encryptedName: String,
        masterkey: VaultMasterkey.MasterkeySet
    ): String {
        require(encryptedName.endsWith(VAULT_FILE_EXT)) {
            "Expected .epz filename: $encryptedName"
        }
        val b64Url = encryptedName.removeSuffix(VAULT_FILE_EXT)
        val cipher = VaultEncoding.base64UrlDecode(b64Url)
        val encKey = masterkey.copyEncKey()
        val macKey = masterkey.copyMacKey()
        return try {
            val plain = AesSiv.decrypt(
                encKey,
                macKey,
                cipher,
                NAME_DOMAIN,
                parentDirId.toByteArray(Charsets.UTF_8)
            )
            VaultEncoding.decodeUtf8Strict(plain)
        } finally {
            encKey.fill(0)
            macKey.fill(0)
        }
    }

    /**
     * Returns `<base64url(sha256(encryptedName))>.epzl` if [encryptedName] exceeds [maxNameLength],
     * or `null` when shortening is not required.
     */
    fun shortenIfNeeded(
        encryptedName: String,
        maxNameLength: Int = VaultMasterkey.DEFAULT_MAX_NAME_LENGTH
    ): String? {
        if (encryptedName.length <= maxNameLength) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(encryptedName.toByteArray(Charsets.UTF_8))
        return VaultEncoding.base64UrlEncode(digest, pad = false) + VAULT_LONG_EXT
    }
}
