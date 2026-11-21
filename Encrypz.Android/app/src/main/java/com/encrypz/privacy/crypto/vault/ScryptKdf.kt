package com.encrypz.privacy.crypto.vault

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin RFC 7914 scrypt key derivation function (using PBKDF2-HMAC-SHA256 & Salsa20/8).
 */
object ScryptKdf {
    private const val SHA256_BLOCK_SIZE = 64

    fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        n: Int,
        r: Int,
        p: Int,
        dkLen: Int
    ): ByteArray {
        val passwordBytes = VaultEncoding.encodeUtf8Chars(password)
        return try {
            deriveKey(passwordBytes, salt, n, r, p, dkLen)
        } finally {
            passwordBytes.fill(0)
        }
    }

    fun deriveKey(
        password: ByteArray,
        salt: ByteArray,
        n: Int,
        r: Int,
        p: Int,
        dkLen: Int
    ): ByteArray {
        require(n > 1 && (n and (n - 1)) == 0) { "scrypt N must be a power of 2 greater than 1" }
        require(r > 0 && p > 0 && dkLen > 0) { "scrypt r, p, and dkLen must be positive" }
        require(r.toLong() * p.toLong() < (1L shl 30)) { "scrypt r * p exceeds maximum" }
        require(n.toLong() * r.toLong() <= Int.MAX_VALUE / 32) { "scrypt N * r exceeds array capacity" }

        val blockBytes = 128 * r
        val blockInts = 32 * r
        val b = pbkdf2HmacSha256(password, salt, 1, p * blockBytes)
        val v = IntArray(n * blockInts)
        val xy = IntArray(blockInts * 2)
        return try {
            for (i in 0 until p) {
                scryptROMix(b, i * blockBytes, r, n, v, xy)
            }
            pbkdf2HmacSha256(password, b, 1, dkLen)
        } finally {
            b.fill(0)
            v.fill(0)
            xy.fill(0)
        }
    }

    internal fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int
    ): ByteArray {
        require(iterations > 0 && dkLen > 0)
        val mac = Mac.getInstance("HmacSHA256")
        // SecretKeySpec rejects an empty byte array; RFC 2104 pads short keys with zeros.
        val keyBytes = if (password.isEmpty()) ByteArray(SHA256_BLOCK_SIZE) else password
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))

        val hLen = 32
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(dkLen)
        val blockIdxBuf = ByteArray(4)
        var outPos = 0

        for (block in 1..blocks) {
            blockIdxBuf[0] = (block ushr 24).toByte()
            blockIdxBuf[1] = (block ushr 16).toByte()
            blockIdxBuf[2] = (block ushr 8).toByte()
            blockIdxBuf[3] = block.toByte()

            mac.update(salt)
            var u = mac.doFinal(blockIdxBuf)
            val t = u.copyOf()

            for (iter in 2..iterations) {
                u = mac.doFinal(u)
                for (k in 0 until hLen) {
                    t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
            }

            val toCopy = minOf(hLen, dkLen - outPos)
            System.arraycopy(t, 0, out, outPos, toCopy)
            outPos += toCopy
            t.fill(0)
            u.fill(0)
        }
        return out
    }

    private fun scryptROMix(
        b: ByteArray,
        bByteOffset: Int,
        r: Int,
        n: Int,
        v: IntArray,
        xy: IntArray
    ) {
        val blockInts = 32 * r
        val xOffset = 0
        val yOffset = blockInts

        // Load B[i] into X as little-endian 32-bit words.
        for (i in 0 until blockInts) {
            val base = bByteOffset + i * 4
            xy[i] = (b[base].toInt() and 0xFF) or
                ((b[base + 1].toInt() and 0xFF) shl 8) or
                ((b[base + 2].toInt() and 0xFF) shl 16) or
                ((b[base + 3].toInt() and 0xFF) shl 24)
        }

        for (i in 0 until n) {
            System.arraycopy(xy, xOffset, v, i * blockInts, blockInts)
            scryptBlockMix(xy, xOffset, xy, yOffset, r)
            System.arraycopy(xy, yOffset, xy, xOffset, blockInts)
        }

        for (i in 0 until n) {
            val j = integerify(xy, xOffset, r) and (n - 1)
            val vBase = j * blockInts
            for (k in 0 until blockInts) {
                xy[xOffset + k] = xy[xOffset + k] xor v[vBase + k]
            }
            scryptBlockMix(xy, xOffset, xy, yOffset, r)
            System.arraycopy(xy, yOffset, xy, xOffset, blockInts)
        }

        // Write X back to B[i] in little-endian order.
        for (i in 0 until blockInts) {
            val w = xy[xOffset + i]
            val base = bByteOffset + i * 4
            b[base] = (w and 0xFF).toByte()
            b[base + 1] = ((w ushr 8) and 0xFF).toByte()
            b[base + 2] = ((w ushr 16) and 0xFF).toByte()
            b[base + 3] = ((w ushr 24) and 0xFF).toByte()
        }
    }

    private fun integerify(x: IntArray, xOffset: Int, r: Int): Int {
        // B_{2r-1} starts at word index (2r - 1) * 16; N < 2^31 so low 32 bits suffice.
        return x[xOffset + (2 * r - 1) * 16]
    }

    private fun scryptBlockMix(
        b: IntArray,
        bOffset: Int,
        y: IntArray,
        yOffset: Int,
        r: Int
    ) {
        val lastChunk = bOffset + (2 * r - 1) * 16
        val scratch = IntArray(16)
        System.arraycopy(b, lastChunk, scratch, 0, 16)

        for (i in 0 until 2 * r) {
            val chunkBase = bOffset + i * 16
            for (k in 0 until 16) {
                scratch[k] = scratch[k] xor b[chunkBase + k]
            }
            salsa20_8Core(scratch)
            val destBase = if ((i and 1) == 0) {
                yOffset + (i ushr 1) * 16
            } else {
                yOffset + (r + (i ushr 1)) * 16
            }
            System.arraycopy(scratch, 0, y, destBase, 16)
        }
    }

    private fun salsa20_8Core(x: IntArray) {
        val z = x.copyOf()
        for (round in 0 until 4) {
            // Column rounds
            z[4] = z[4] xor rotl(z[0] + z[12], 7)
            z[8] = z[8] xor rotl(z[4] + z[0], 9)
            z[12] = z[12] xor rotl(z[8] + z[4], 13)
            z[0] = z[0] xor rotl(z[12] + z[8], 18)

            z[9] = z[9] xor rotl(z[5] + z[1], 7)
            z[13] = z[13] xor rotl(z[9] + z[5], 9)
            z[1] = z[1] xor rotl(z[13] + z[9], 13)
            z[5] = z[5] xor rotl(z[1] + z[13], 18)

            z[14] = z[14] xor rotl(z[10] + z[6], 7)
            z[2] = z[2] xor rotl(z[14] + z[10], 9)
            z[6] = z[6] xor rotl(z[2] + z[14], 13)
            z[10] = z[10] xor rotl(z[6] + z[2], 18)

            z[3] = z[3] xor rotl(z[15] + z[11], 7)
            z[7] = z[7] xor rotl(z[3] + z[15], 9)
            z[11] = z[11] xor rotl(z[7] + z[3], 13)
            z[15] = z[15] xor rotl(z[11] + z[7], 18)

            // Row rounds
            z[1] = z[1] xor rotl(z[0] + z[3], 7)
            z[2] = z[2] xor rotl(z[1] + z[0], 9)
            z[3] = z[3] xor rotl(z[2] + z[1], 13)
            z[0] = z[0] xor rotl(z[3] + z[2], 18)

            z[6] = z[6] xor rotl(z[5] + z[4], 7)
            z[7] = z[7] xor rotl(z[6] + z[5], 9)
            z[4] = z[4] xor rotl(z[7] + z[6], 13)
            z[5] = z[5] xor rotl(z[4] + z[7], 18)

            z[11] = z[11] xor rotl(z[10] + z[9], 7)
            z[8] = z[8] xor rotl(z[11] + z[10], 9)
            z[9] = z[9] xor rotl(z[8] + z[11], 13)
            z[10] = z[10] xor rotl(z[9] + z[8], 18)

            z[12] = z[12] xor rotl(z[15] + z[14], 7)
            z[13] = z[13] xor rotl(z[12] + z[15], 9)
            z[14] = z[14] xor rotl(z[13] + z[12], 13)
            z[15] = z[15] xor rotl(z[14] + z[13], 18)
        }
        for (i in 0 until 16) {
            x[i] += z[i]
        }
    }

    private fun rotl(value: Int, shift: Int): Int =
        (value shl shift) or (value ushr (32 - shift))
}
