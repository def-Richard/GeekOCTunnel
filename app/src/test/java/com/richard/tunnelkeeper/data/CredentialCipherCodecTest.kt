package com.richard.tunnelkeeper.data

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialCipherCodecTest {
    @Test
    fun `current records round trip and bind to profile id`() {
        val key = generateKey()
        val clearText = "credential payload".toByteArray(StandardCharsets.UTF_8)
        val storedValue = CredentialCipherCodec.encrypt("profile-a", clearText, key)

        val decrypted = CredentialCipherCodec.decrypt("profile-a", storedValue, key)

        assertTrue(storedValue.startsWith("v2:"))
        assertFalse(decrypted.requiresMigration)
        assertArrayEquals(clearText, decrypted.bytes)
        assertThrows(GeneralSecurityException::class.java) {
            CredentialCipherCodec.decrypt("profile-b", storedValue, key)
        }
    }

    @Test
    fun `legacy records decrypt once and are marked for migration`() {
        val key = generateKey()
        val clearText = "legacy credential payload".toByteArray(StandardCharsets.UTF_8)
        val legacyValue = encryptLegacy(clearText, key)

        val decrypted = CredentialCipherCodec.decrypt("profile-a", legacyValue, key)

        assertTrue(decrypted.requiresMigration)
        assertArrayEquals(clearText, decrypted.bytes)

        val migratedValue = CredentialCipherCodec.encrypt("profile-a", decrypted.bytes, key)
        assertTrue(migratedValue.startsWith("v2:"))
        assertFalse(CredentialCipherCodec.decrypt("profile-a", migratedValue, key).requiresMigration)
    }

    @Test
    fun `tampered current record fails closed`() {
        val key = generateKey()
        val storedValue = CredentialCipherCodec.encrypt(
            "profile-a",
            "credential payload".toByteArray(StandardCharsets.UTF_8),
            key,
        )
        val parts = storedValue.split(':').toMutableList()
        val encrypted = Base64.getDecoder().decode(parts[2])
        encrypted[0] = (encrypted[0].toInt() xor 1).toByte()
        parts[2] = Base64.getEncoder().encodeToString(encrypted)

        assertThrows(GeneralSecurityException::class.java) {
            CredentialCipherCodec.decrypt("profile-a", parts.joinToString(":"), key)
        }
    }

    @Test
    fun `record encrypted by an unavailable key fails closed`() {
        val storedValue = CredentialCipherCodec.encrypt(
            "profile-a",
            "credential payload".toByteArray(StandardCharsets.UTF_8),
            generateKey(),
        )

        assertThrows(GeneralSecurityException::class.java) {
            CredentialCipherCodec.decrypt("profile-a", storedValue, generateKey())
        }
    }

    @Test
    fun `malformed and unknown records fail closed`() {
        val key = generateKey()

        listOf("", "v3:AA==:AA==", "v2:not-base64:not-base64").forEach { storedValue ->
            assertThrows(GeneralSecurityException::class.java) {
                CredentialCipherCodec.decrypt("profile-a", storedValue, key)
            }
        }
    }

    private fun generateKey() = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()

    private fun encryptLegacy(clearText: ByteArray, key: javax.crypto.SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return listOf(
            Base64.getEncoder().encodeToString(cipher.iv),
            Base64.getEncoder().encodeToString(cipher.doFinal(clearText)),
        ).joinToString(":")
    }
}
