package com.encrypz.privacy.crypto.vault

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypz Vault v2 masterkey (`masterkey.encrypz`), authenticated vault manifest (`vault.encrypz`),
 * and offline CRC-16/CCITT Recovery Key codec.
 */
object VaultMasterkey {
    const val VAULT_SCHEMA = "encrypz.vault.v2"
    const val KEY_SCHEMA = "encrypz.key.v2"
    const val SUPPORTED_VAULT_FORMAT = 2
    const val SUPPORTED_CIPHER_SUITE = "ENCRYPZ_AES256_SIV_GCM_V2"
    const val DEFAULT_KEY_VERSION = 2
    const val DEFAULT_SCRYPT_COST = 32768
    const val DEFAULT_SCRYPT_BLOCK_SIZE = 8
    const val DEFAULT_SCRYPT_PARALLELISM = 1
    const val DEFAULT_CHUNK_SIZE_BYTES = VaultFileCrypto.CLEARTEXT_CHUNK_SIZE
    const val DEFAULT_MAX_NAME_LENGTH = 200
    const val DEFAULT_KEY_FILE = "masterkey.encrypz"

    class MasterkeySet(encKey: ByteArray, macKey: ByteArray) : AutoCloseable {
        private val enc = encKey.copyOf()
        private val mac = macKey.copyOf()

        init {
            require(enc.size == 32 && mac.size == 32) {
                "MasterkeySet requires two 32-byte keys"
            }
        }

        fun copyEncKey(): ByteArray = enc.copyOf()
        fun copyMacKey(): ByteArray = mac.copyOf()

        fun raw64Bytes(): ByteArray {
            val combined = ByteArray(64)
            System.arraycopy(enc, 0, combined, 0, 32)
            System.arraycopy(mac, 0, combined, 32, 32)
            return combined
        }

        override fun close() {
            enc.fill(0)
            mac.fill(0)
        }
    }

    data class MasterkeyFile(
        val schema: String = KEY_SCHEMA,
        val keyVersion: Int = DEFAULT_KEY_VERSION,
        val kdf: String = "scrypt",
        val salt: ByteArray,
        val n: Int = DEFAULT_SCRYPT_COST,
        val r: Int = DEFAULT_SCRYPT_BLOCK_SIZE,
        val p: Int = DEFAULT_SCRYPT_PARALLELISM,
        val wrappedEncKey: ByteArray,
        val wrappedMacKey: ByteArray,
        val integrityMac: ByteArray
    ) {
        fun toJson(): String = VaultEncoding.buildFlatJsonObject(
            listOf(
                "schema" to schema,
                "keyVersion" to keyVersion,
                "kdf" to kdf,
                "salt" to VaultEncoding.base64Encode(salt),
                "n" to n,
                "r" to r,
                "p" to p,
                "wrappedEncKey" to VaultEncoding.base64Encode(wrappedEncKey),
                "wrappedMacKey" to VaultEncoding.base64Encode(wrappedMacKey),
                "integrityMac" to VaultEncoding.base64Encode(integrityMac)
            )
        )

        companion object {
            fun fromJson(json: String): MasterkeyFile {
                val map = VaultEncoding.parseFlatJsonObject(json)
                val schema = map["schema"] as? String
                    ?: throw IllegalArgumentException("Missing keyfile schema")
                require(schema == KEY_SCHEMA) { "Unsupported keyfile schema: $schema" }
                val keyVersion = (map["keyVersion"] as? Number)?.toInt()
                    ?: throw IllegalArgumentException("Missing keyVersion")
                val kdf = map["kdf"] as? String
                    ?: throw IllegalArgumentException("Missing kdf")
                require(kdf == "scrypt") { "Unsupported keyfile KDF: $kdf" }
                val saltB64 = map["salt"] as? String
                    ?: throw IllegalArgumentException("Missing salt")
                val n = (map["n"] as? Number)?.toInt()
                    ?: throw IllegalArgumentException("Missing scrypt cost n")
                val r = (map["r"] as? Number)?.toInt()
                    ?: throw IllegalArgumentException("Missing scrypt blockSize r")
                val p = (map["p"] as? Number)?.toInt()
                    ?: throw IllegalArgumentException("Missing scrypt parallelism p")
                val encB64 = map["wrappedEncKey"] as? String
                    ?: throw IllegalArgumentException("Missing wrappedEncKey")
                val macB64 = map["wrappedMacKey"] as? String
                    ?: throw IllegalArgumentException("Missing wrappedMacKey")
                val integrityB64 = map["integrityMac"] as? String
                    ?: throw IllegalArgumentException("Missing integrityMac")

                return MasterkeyFile(
                    schema = schema,
                    keyVersion = keyVersion,
                    kdf = kdf,
                    salt = VaultEncoding.base64Decode(saltB64),
                    n = n,
                    r = r,
                    p = p,
                    wrappedEncKey = VaultEncoding.base64Decode(encB64),
                    wrappedMacKey = VaultEncoding.base64Decode(macB64),
                    integrityMac = VaultEncoding.base64Decode(integrityB64)
                )
            }
        }
    }

    data class VaultConfig(
        val vaultId: String,
        val format: Int = SUPPORTED_VAULT_FORMAT,
        val cipherSuite: String = SUPPORTED_CIPHER_SUITE,
        val chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE_BYTES,
        val maxNameLength: Int = DEFAULT_MAX_NAME_LENGTH,
        val keyFile: String = DEFAULT_KEY_FILE
    )

    data class CreatedVault(
        val masterkeySet: MasterkeySet,
        val masterkeyJson: String,
        val vaultManifestJson: String,
        val vaultConfig: VaultConfig,
        val recoveryKey: String
    )

    data class UnlockedVault(
        val masterkeySet: MasterkeySet,
        val vaultConfig: VaultConfig
    )

    fun createVault(
        password: CharArray,
        scryptCostParam: Int = DEFAULT_SCRYPT_COST,
        scryptBlockSize: Int = DEFAULT_SCRYPT_BLOCK_SIZE,
        scryptParallelism: Int = DEFAULT_SCRYPT_PARALLELISM,
        maxNameLength: Int = DEFAULT_MAX_NAME_LENGTH
    ): CreatedVault {
        val random = SecureRandom()
        val encKey = ByteArray(32).also { random.nextBytes(it) }
        val macKey = ByteArray(32).also { random.nextBytes(it) }
        val masterkey = MasterkeySet(encKey, macKey)
        encKey.fill(0)
        macKey.fill(0)

        val vaultConfig = VaultConfig(
            vaultId = UUID.randomUUID().toString(),
            format = SUPPORTED_VAULT_FORMAT,
            cipherSuite = SUPPORTED_CIPHER_SUITE,
            chunkSizeBytes = DEFAULT_CHUNK_SIZE_BYTES,
            maxNameLength = maxNameLength,
            keyFile = DEFAULT_KEY_FILE
        )
        val masterkeyJson = wrapMasterkey(
            masterkey = masterkey,
            password = password,
            scryptCostParam = scryptCostParam,
            scryptBlockSize = scryptBlockSize,
            scryptParallelism = scryptParallelism
        )
        val manifestJson = signVaultManifest(vaultConfig, masterkey)
        val recoveryKey = RecoveryKeyCodec.encode(masterkey)
        return CreatedVault(
            masterkeySet = masterkey,
            masterkeyJson = masterkeyJson,
            vaultManifestJson = manifestJson,
            vaultConfig = vaultConfig,
            recoveryKey = recoveryKey
        )
    }

    fun unlockVault(
        vaultManifestJson: String,
        masterkeyJson: String,
        password: CharArray
    ): UnlockedVault {
        val masterkey = unwrapMasterkey(masterkeyJson, password)
        return try {
            val config = verifyVaultManifest(vaultManifestJson, masterkey)
            UnlockedVault(masterkey, config)
        } catch (t: Throwable) {
            masterkey.close()
            throw t
        }
    }

    fun changePassword(
        vaultManifestJson: String,
        masterkeyJson: String,
        currentPassword: CharArray,
        newPassword: CharArray
    ): String {
        val parsed = MasterkeyFile.fromJson(masterkeyJson)
        val unlocked = unlockVault(vaultManifestJson, masterkeyJson, currentPassword)
        return unlocked.masterkeySet.use { masterkey ->
            wrapMasterkey(
                masterkey = masterkey,
                password = newPassword,
                scryptCostParam = parsed.n,
                scryptBlockSize = parsed.r,
                scryptParallelism = parsed.p,
                keyVersion = parsed.keyVersion
            )
        }
    }

    fun recoverMasterkey(
        vaultManifestJson: String,
        recoveryKey: String,
        newPassword: CharArray,
        scryptCostParam: Int = DEFAULT_SCRYPT_COST,
        scryptBlockSize: Int = DEFAULT_SCRYPT_BLOCK_SIZE,
        scryptParallelism: Int = DEFAULT_SCRYPT_PARALLELISM
    ): Triple<MasterkeySet, VaultConfig, String> {
        val masterkey = RecoveryKeyCodec.decode(recoveryKey)
        return try {
            val config = verifyVaultManifest(vaultManifestJson, masterkey)
            val newMasterkeyJson = wrapMasterkey(
                masterkey = masterkey,
                password = newPassword,
                scryptCostParam = scryptCostParam,
                scryptBlockSize = scryptBlockSize,
                scryptParallelism = scryptParallelism
            )
            Triple(masterkey, config, newMasterkeyJson)
        } catch (t: Throwable) {
            masterkey.close()
            throw t
        }
    }

    fun wrapMasterkey(
        masterkey: MasterkeySet,
        password: CharArray,
        scryptCostParam: Int = DEFAULT_SCRYPT_COST,
        scryptBlockSize: Int = DEFAULT_SCRYPT_BLOCK_SIZE,
        scryptParallelism: Int = DEFAULT_SCRYPT_PARALLELISM,
        keyVersion: Int = DEFAULT_KEY_VERSION
    ): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val kek = ScryptKdf.deriveKey(
            password = password,
            salt = salt,
            n = scryptCostParam,
            r = scryptBlockSize,
            p = scryptParallelism,
            dkLen = 32
        )
        val encKey = masterkey.copyEncKey()
        val macKey = masterkey.copyMacKey()
        return try {
            val wrappedEnc = AesKeyWrap.wrap(kek, encKey)
            val wrappedMac = AesKeyWrap.wrap(kek, macKey)
            val saltB64 = VaultEncoding.base64Encode(salt)
            val integrityMac = computeKeyFileMac(
                macKey = macKey,
                keyVersion = keyVersion,
                n = scryptCostParam,
                r = scryptBlockSize,
                p = scryptParallelism,
                saltB64 = saltB64
            )
            MasterkeyFile(
                schema = KEY_SCHEMA,
                keyVersion = keyVersion,
                kdf = "scrypt",
                salt = salt,
                n = scryptCostParam,
                r = scryptBlockSize,
                p = scryptParallelism,
                wrappedEncKey = wrappedEnc,
                wrappedMacKey = wrappedMac,
                integrityMac = integrityMac
            ).toJson()
        } finally {
            kek.fill(0)
            encKey.fill(0)
            macKey.fill(0)
        }
    }

    fun unwrapMasterkey(masterkeyJson: String, password: CharArray): MasterkeySet {
        val file = MasterkeyFile.fromJson(masterkeyJson)
        val kek = ScryptKdf.deriveKey(
            password = password,
            salt = file.salt,
            n = file.n,
            r = file.r,
            p = file.p,
            dkLen = 32
        )
        var encKey: ByteArray? = null
        var macKey: ByteArray? = null
        return try {
            encKey = AesKeyWrap.unwrap(kek, file.wrappedEncKey)
            macKey = AesKeyWrap.unwrap(kek, file.wrappedMacKey)
            val expectedMac = computeKeyFileMac(
                macKey = macKey,
                keyVersion = file.keyVersion,
                n = file.n,
                r = file.r,
                p = file.p,
                saltB64 = VaultEncoding.base64Encode(file.salt)
            )
            if (!MessageDigest.isEqual(expectedMac, file.integrityMac)) {
                throw AEADBadTagException("Masterkey integrity MAC verification failed")
            }
            MasterkeySet(encKey, macKey)
        } finally {
            kek.fill(0)
            encKey?.fill(0)
            macKey?.fill(0)
        }
    }

    fun signVaultManifest(config: VaultConfig, masterkey: MasterkeySet): String {
        val macInput = canonicalManifestSigningInput(config)
        val macBytes = hmacSha256(masterkey, macInput.toByteArray(Charsets.UTF_8))
        val macPart = VaultEncoding.base64UrlEncode(macBytes, pad = false)
        return VaultEncoding.buildFlatJsonObject(
            listOf(
                "schema" to VAULT_SCHEMA,
                "vaultId" to config.vaultId,
                "vaultVersion" to config.format,
                "cipherSuite" to config.cipherSuite,
                "chunkSizeBytes" to config.chunkSizeBytes,
                "maxNameLength" to config.maxNameLength,
                "keyFile" to config.keyFile,
                "manifestMac" to macPart
            )
        )
    }

    fun verifyVaultManifest(manifestJson: String, masterkey: MasterkeySet): VaultConfig {
        val map = VaultEncoding.parseFlatJsonObject(manifestJson)
        val schema = map["schema"] as? String
            ?: throw IllegalArgumentException("Missing schema in vault.encrypz")
        require(schema == VAULT_SCHEMA) { "Unsupported vault schema: $schema" }

        val vaultId = map["vaultId"] as? String
            ?: throw IllegalArgumentException("Missing vaultId in vault.encrypz")
        val format = (map["vaultVersion"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Missing vaultVersion in vault.encrypz")
        val cipherSuite = map["cipherSuite"] as? String
            ?: throw IllegalArgumentException("Missing cipherSuite in vault.encrypz")
        val chunkSizeBytes = (map["chunkSizeBytes"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Missing chunkSizeBytes in vault.encrypz")
        val maxNameLength = (map["maxNameLength"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Missing maxNameLength in vault.encrypz")
        val keyFile = map["keyFile"] as? String
            ?: throw IllegalArgumentException("Missing keyFile in vault.encrypz")
        val actualMacB64 = map["manifestMac"] as? String
            ?: throw IllegalArgumentException("Missing manifestMac in vault.encrypz")

        val config = VaultConfig(
            vaultId = vaultId,
            format = format,
            cipherSuite = cipherSuite,
            chunkSizeBytes = chunkSizeBytes,
            maxNameLength = maxNameLength,
            keyFile = keyFile
        )
        val expectedMac = hmacSha256(masterkey, canonicalManifestSigningInput(config).toByteArray(Charsets.UTF_8))
        val actualMac = VaultEncoding.base64UrlDecode(actualMacB64)
        if (!MessageDigest.isEqual(expectedMac, actualMac)) {
            throw AEADBadTagException("vault.encrypz manifest MAC verification failed")
        }

        require(format == SUPPORTED_VAULT_FORMAT) { "Unsupported Encrypz vault format: $format" }
        require(cipherSuite == SUPPORTED_CIPHER_SUITE) { "Unsupported cipher suite: $cipherSuite" }
        require(chunkSizeBytes == DEFAULT_CHUNK_SIZE_BYTES) { "Unsupported chunk size: $chunkSizeBytes" }
        require(maxNameLength > 0) { "Invalid maxNameLength: $maxNameLength" }

        return config
    }

    /** Parses vault metadata without verifying the signature (used for vault discovery). */
    fun parseVaultConfigUnverified(manifestJson: String): VaultConfig? = runCatching {
        val map = VaultEncoding.parseFlatJsonObject(manifestJson)
        require((map["schema"] as? String) == VAULT_SCHEMA)
        VaultConfig(
            vaultId = (map["vaultId"] as? String) ?: "",
            format = (map["vaultVersion"] as? Number)?.toInt() ?: 0,
            cipherSuite = (map["cipherSuite"] as? String) ?: "",
            chunkSizeBytes = (map["chunkSizeBytes"] as? Number)?.toInt() ?: DEFAULT_CHUNK_SIZE_BYTES,
            maxNameLength = (map["maxNameLength"] as? Number)?.toInt() ?: DEFAULT_MAX_NAME_LENGTH,
            keyFile = (map["keyFile"] as? String) ?: DEFAULT_KEY_FILE
        )
    }.getOrNull()

    private fun canonicalManifestSigningInput(config: VaultConfig): String =
        "encrypz:vault-manifest:v2|${config.vaultId}|${config.format}|${config.cipherSuite}|${config.chunkSizeBytes}|${config.maxNameLength}|${config.keyFile}"

    private fun computeKeyFileMac(
        macKey: ByteArray,
        keyVersion: Int,
        n: Int,
        r: Int,
        p: Int,
        saltB64: String
    ): ByteArray {
        val canonical = "encrypz:keyfile:v2|$keyVersion|$n|$r|$p|$saltB64".toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        return mac.doFinal(canonical)
    }

    private fun hmacSha256(masterkey: MasterkeySet, data: ByteArray): ByteArray {
        val raw64 = masterkey.raw64Bytes()
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(raw64, "HmacSHA256"))
            mac.doFinal(data)
        } finally {
            raw64.fill(0)
        }
    }

    /**
     * 64-byte masterkey + 2-byte CRC-16/CCITT checksum encoded in grouped uppercase Base32.
     */
    object RecoveryKeyCodec {
        fun encode(masterkey: MasterkeySet): String {
            val raw64 = masterkey.raw64Bytes()
            val payload = ByteArray(66)
            return try {
                System.arraycopy(raw64, 0, payload, 0, 64)
                val crc = crc16Ccitt(raw64)
                payload[64] = (crc ushr 8).toByte()
                payload[65] = crc.toByte()
                val base32 = VaultEncoding.base32Encode(payload, pad = false)
                base32.chunked(4).joinToString("-")
            } finally {
                raw64.fill(0)
                payload.fill(0)
            }
        }

        fun decode(recoveryKey: String): MasterkeySet {
            val cleaned = recoveryKey.uppercase().replace("-", "").replace(Regex("\\s+"), "")
            val decoded = try {
                VaultEncoding.base32Decode(cleaned)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid recovery key format.", e)
            }
            return try {
                require(decoded.size == 66) { "Invalid recovery key length." }
                val raw64 = decoded.copyOfRange(0, 64)
                val expectedCrc = ((decoded[64].toInt() and 0xFF) shl 8) or (decoded[65].toInt() and 0xFF)
                val actualCrc = crc16Ccitt(raw64)
                require(expectedCrc == actualCrc) { "Recovery key checksum failed — verify every character." }
                MasterkeySet(raw64.copyOfRange(0, 32), raw64.copyOfRange(32, 64))
            } finally {
                decoded.fill(0)
            }
        }

        private fun crc16Ccitt(data: ByteArray): Int {
            var crc = 0xFFFF
            for (b in data) {
                crc = crc xor ((b.toInt() and 0xFF) shl 8)
                for (bit in 0 until 8) {
                    crc = if ((crc and 0x8000) != 0) {
                        ((crc shl 1) xor 0x1021) and 0xFFFF
                    } else {
                        (crc shl 1) and 0xFFFF
                    }
                }
            }
            return crc
        }
    }
}
