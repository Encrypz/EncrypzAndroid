package com.encrypz.privacy.crypto.vault

import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin RFC 5297 AES-SIV + RFC 4493 AES-CMAC implementation.
 *
 * Key convention for Encrypz vault format:
 * - [encKey] (`primaryMasterKey`): RFC 5297 `K2`, used for AES-CTR encryption/decryption.
 * - [macKey] (`hmacMasterKey`): RFC 5297 `K1`, used for S2V / AES-CMAC authentication.
 */
object AesSiv {
    private const val BLOCK_SIZE = 16
    private const val MAX_ASSOCIATED_DATA_ITEMS = 126

    fun encrypt(
        encKey: ByteArray,
        macKey: ByteArray,
        plaintext: ByteArray,
        vararg associatedData: ByteArray
    ): ByteArray {
        validateKeys(encKey, macKey, associatedData.size)
        val v = s2v(macKey, associatedData, plaintext)
        val q = maskIvForCtr(v)
        val ctr = Cipher.getInstance("AES/CTR/NoPadding")
        ctr.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(q))
        val ciphertext = ctr.doFinal(plaintext)

        val out = ByteArray(BLOCK_SIZE + ciphertext.size)
        System.arraycopy(v, 0, out, 0, BLOCK_SIZE)
        System.arraycopy(ciphertext, 0, out, BLOCK_SIZE, ciphertext.size)
        return out
    }

    fun decrypt(
        encKey: ByteArray,
        macKey: ByteArray,
        ciphertextWithIv: ByteArray,
        vararg associatedData: ByteArray
    ): ByteArray {
        validateKeys(encKey, macKey, associatedData.size)
        if (ciphertextWithIv.size < BLOCK_SIZE) {
            throw AEADBadTagException("AES-SIV ciphertext shorter than 16-byte synthetic IV")
        }
        val v = ciphertextWithIv.copyOfRange(0, BLOCK_SIZE)
        val ciphertext = ciphertextWithIv.copyOfRange(BLOCK_SIZE, ciphertextWithIv.size)
        val q = maskIvForCtr(v)

        val ctr = Cipher.getInstance("AES/CTR/NoPadding")
        ctr.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(q))
        val plaintext = ctr.doFinal(ciphertext)

        val expectedV = s2v(macKey, associatedData, plaintext)
        if (!MessageDigest.isEqual(v, expectedV)) {
            plaintext.fill(0)
            throw AEADBadTagException("AES-SIV authentication failed")
        }
        return plaintext
    }

    private fun validateKeys(encKey: ByteArray, macKey: ByteArray, adCount: Int) {
        require(encKey.size in setOf(16, 24, 32)) { "Invalid AES-SIV encryption key length: ${encKey.size}" }
        require(encKey.size == macKey.size) { "AES-SIV encKey and macKey lengths must match" }
        require(adCount <= MAX_ASSOCIATED_DATA_ITEMS) { "Too many associated data items (max $MAX_ASSOCIATED_DATA_ITEMS)" }
    }

    private fun maskIvForCtr(v: ByteArray): ByteArray {
        val q = v.copyOf()
        q[8] = (q[8].toInt() and 0x7F).toByte()
        q[12] = (q[12].toInt() and 0x7F).toByte()
        return q
    }

    private fun s2v(
        macKey: ByteArray,
        associatedData: Array<out ByteArray>,
        plaintext: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(macKey, "AES"))
        val (k1, k2) = generateCmacSubkeys(cipher)

        var d = cmacWithSubkeys(cipher, k1, k2, ByteArray(BLOCK_SIZE))
        for (ad in associatedData) {
            dblInPlace(d)
            val adMac = cmacWithSubkeys(cipher, k1, k2, ad)
            xorInPlace(d, adMac)
        }

        val t = if (plaintext.size >= BLOCK_SIZE) {
            val copy = plaintext.copyOf()
            val tailOffset = copy.size - BLOCK_SIZE
            for (i in 0 until BLOCK_SIZE) {
                copy[tailOffset + i] = (copy[tailOffset + i].toInt() xor d[i].toInt()).toByte()
            }
            copy
        } else {
            dblInPlace(d)
            val padded = ByteArray(BLOCK_SIZE)
            System.arraycopy(plaintext, 0, padded, 0, plaintext.size)
            padded[plaintext.size] = 0x80.toByte()
            xorInPlace(padded, d)
            padded
        }
        return cmacWithSubkeys(cipher, k1, k2, t)
    }

    internal fun aesCmac(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val (k1, k2) = generateCmacSubkeys(cipher)
        return cmacWithSubkeys(cipher, k1, k2, data)
    }

    private fun generateCmacSubkeys(ecbEncryptCipher: Cipher): Pair<ByteArray, ByteArray> {
        val l = ecbEncryptCipher.doFinal(ByteArray(BLOCK_SIZE))
        val k1 = l.copyOf()
        dblInPlace(k1)
        val k2 = k1.copyOf()
        dblInPlace(k2)
        return k1 to k2
    }

    private fun cmacWithSubkeys(
        ecbEncryptCipher: Cipher,
        k1: ByteArray,
        k2: ByteArray,
        data: ByteArray
    ): ByteArray {
        val completeLastBlock = data.isNotEmpty() && data.size % BLOCK_SIZE == 0
        val blockCount = if (data.isEmpty()) 1 else (data.size + BLOCK_SIZE - 1) / BLOCK_SIZE

        val lastBlock = ByteArray(BLOCK_SIZE)
        val lastStart = (blockCount - 1) * BLOCK_SIZE
        val remaining = data.size - lastStart
        if (completeLastBlock) {
            System.arraycopy(data, lastStart, lastBlock, 0, BLOCK_SIZE)
            xorInPlace(lastBlock, k1)
        } else {
            if (remaining > 0) {
                System.arraycopy(data, lastStart, lastBlock, 0, remaining)
            }
            lastBlock[remaining] = 0x80.toByte()
            xorInPlace(lastBlock, k2)
        }

        var x = ByteArray(BLOCK_SIZE)
        val y = ByteArray(BLOCK_SIZE)
        for (i in 0 until blockCount - 1) {
            val offset = i * BLOCK_SIZE
            for (j in 0 until BLOCK_SIZE) {
                y[j] = (x[j].toInt() xor data[offset + j].toInt()).toByte()
            }
            x = ecbEncryptCipher.doFinal(y)
        }
        for (j in 0 until BLOCK_SIZE) {
            y[j] = (x[j].toInt() xor lastBlock[j].toInt()).toByte()
        }
        return ecbEncryptCipher.doFinal(y)
    }

    private fun dblInPlace(block: ByteArray) {
        val msb = (block[0].toInt() and 0x80) != 0
        var carry = 0
        for (i in block.size - 1 downTo 0) {
            val b = block[i].toInt() and 0xFF
            block[i] = ((b shl 1) or carry).toByte()
            carry = b ushr 7
        }
        if (msb) {
            block[block.size - 1] = (block[block.size - 1].toInt() xor 0x87).toByte()
        }
    }

    private fun xorInPlace(target: ByteArray, other: ByteArray) {
        for (i in target.indices) {
            target[i] = (target[i].toInt() xor other[i].toInt()).toByte()
        }
    }
}
