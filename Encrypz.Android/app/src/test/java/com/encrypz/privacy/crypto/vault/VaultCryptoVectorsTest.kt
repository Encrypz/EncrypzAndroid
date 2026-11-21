package com.encrypz.privacy.crypto.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

class VaultCryptoVectorsTest {

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").trim()
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun scrypt_matchesRfc7914Vector1_emptyPasswordAndSalt() {
        val dk = ScryptKdf.deriveKey(
            password = ByteArray(0),
            salt = ByteArray(0),
            n = 16,
            r = 1,
            p = 1,
            dkLen = 64
        )
        assertEquals(
            "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442" +
                "fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
            bytesToHex(dk)
        )
    }

    @Test
    fun scrypt_matchesRfc7914Vector2_passwordAndNaCl() {
        val dk = ScryptKdf.deriveKey(
            password = "password".toCharArray(),
            salt = "NaCl".toByteArray(Charsets.UTF_8),
            n = 1024,
            r = 8,
            p = 16,
            dkLen = 64
        )
        assertEquals(
            "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b373162" +
                "2eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            bytesToHex(dk)
        )
    }

    @Test
    fun aesKeyWrap_matchesRfc3394Section46_256BitKekAndKeyData() {
        val kek = hexToBytes("000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F")
        val keyData = hexToBytes("00112233445566778899AABBCCDDEEFF000102030405060708090A0B0C0D0E0F")
        val expected = hexToBytes("28C9F404C4B810F4CBCCB35CFB87F8263F5786E2D80ED326CBC7F0E71A99F43BFB988B9B7A02DD21")

        val wrapped = AesKeyWrap.wrap(kek, keyData)
        assertArrayEquals(expected, wrapped)
        assertArrayEquals(keyData, AesKeyWrap.unwrap(kek, wrapped))

        val tampered = wrapped.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            AesKeyWrap.unwrap(kek, tampered)
        }
    }

    @Test
    fun aesCmac_matchesRfc4493TestVectors() {
        val key = hexToBytes("2b7e151628aed2a6abf7158809cf4f3c")
        assertEquals("bb1d6929e95937287fa37d129b756746", bytesToHex(AesSiv.aesCmac(key, ByteArray(0))))
        assertEquals(
            "070a16b46b4d4144f79bdd9dd04a287c",
            bytesToHex(AesSiv.aesCmac(key, hexToBytes("6bc1bee22e409f96e93d7e117393172a")))
        )
    }

    @Test
    fun aesSiv_matchesRfc5297AppendixA1_deterministic() {
        val macKey = hexToBytes("fffefdfcfbfaf9f8f7f6f5f4f3f2f1f0")
        val encKey = hexToBytes("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        val ad = hexToBytes("101112131415161718191a1b1c1d1e1f2021222324252627")
        val plaintext = hexToBytes("112233445566778899aabbccddee")
        val expected = hexToBytes("85632d07c6e8f37f950acd320a2ecc9340c02b9690c4dc04daef7f6afe5c")

        val actual = AesSiv.encrypt(encKey, macKey, plaintext, ad)
        assertArrayEquals(expected, actual)
        assertArrayEquals(plaintext, AesSiv.decrypt(encKey, macKey, actual, ad))

        // Different associated data must fail authentication.
        assertThrows(AEADBadTagException::class.java) {
            AesSiv.decrypt(encKey, macKey, actual, hexToBytes("101112"))
        }
    }

    @Test
    fun aesSiv_matchesRfc5297AppendixA2_multipleAssociatedData() {
        val macKey = hexToBytes("7f7e7d7c7b7a79787776757473727170")
        val encKey = hexToBytes("404142434445464748494a4b4c4d4e4f")
        val ad1 = hexToBytes("00112233445566778899aabbccddeeffdeaddadadeaddadaffeeddccbbaa99887766554433221100")
        val ad2 = hexToBytes("102030405060708090a0")
        val nonce = hexToBytes("09f911029d74e35bd84156c5635688c0")
        val plaintext = hexToBytes("7468697320697320736f6d6520706c61696e7465787420746f20656e6372797074207573696e67205349562d414553")
        val expected = hexToBytes(
            "7bdb6e3b432667eb06f4d14bff2fbd0f" +
                "cb900f2fddbe404326601965c889bf17dba77ceb094fa663b7a3f748ba8af829ea64ad544a272e9c485b62a3fd5c0d"
        )

        val actual = AesSiv.encrypt(encKey, macKey, plaintext, ad1, ad2, nonce)
        assertArrayEquals(expected, actual)
        assertArrayEquals(plaintext, AesSiv.decrypt(encKey, macKey, actual, ad1, ad2, nonce))
    }

    @Test
    fun encodings_roundTripBase64Base64UrlAndBase32() {
        for (len in 0..37) {
            val sample = ByteArray(len) { ((it * 37 + 13) and 0xFF).toByte() }
            assertArrayEquals(sample, VaultEncoding.base64Decode(VaultEncoding.base64Encode(sample, pad = true)))
            assertArrayEquals(sample, VaultEncoding.base64Decode(VaultEncoding.base64Encode(sample, pad = false)))
            assertArrayEquals(sample, VaultEncoding.base64UrlDecode(VaultEncoding.base64UrlEncode(sample, pad = true)))
            assertArrayEquals(sample, VaultEncoding.base64UrlDecode(VaultEncoding.base64UrlEncode(sample, pad = false)))
            assertArrayEquals(sample, VaultEncoding.base32Decode(VaultEncoding.base32Encode(sample, pad = false)))
        }
    }
}
