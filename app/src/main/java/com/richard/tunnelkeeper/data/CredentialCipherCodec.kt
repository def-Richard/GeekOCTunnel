package com.richard.tunnelkeeper.data

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class DecryptedCredentialPayload(
    val bytes: ByteArray,
    val requiresMigration: Boolean,
)

internal object CredentialCipherCodec {
    private const val RECORD_VERSION = "v2"
    private const val SCHEMA_VERSION = 2
    private const val AAD_NAMESPACE = "com.richard.tunnelkeeper.credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val DELIMITER = ":"
    private const val TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8

    fun encrypt(profileId: String, clearText: ByteArray, key: SecretKey): String {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad(profileId))
        val encrypted = cipher.doFinal(clearText)
        return listOf(
            RECORD_VERSION,
            Base64.getEncoder().encodeToString(cipher.iv),
            Base64.getEncoder().encodeToString(encrypted),
        ).joinToString(DELIMITER)
    }

    fun decrypt(profileId: String, storedValue: String, key: SecretKey): DecryptedCredentialPayload {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        val parts = storedValue.split(DELIMITER)
        return when {
            parts.size == 3 && parts[0] == RECORD_VERSION -> DecryptedCredentialPayload(
                bytes = decryptParts(parts[1], parts[2], key, aad(profileId)),
                requiresMigration = false,
            )
            parts.size == 2 -> DecryptedCredentialPayload(
                bytes = decryptParts(parts[0], parts[1], key, aad = null),
                requiresMigration = true,
            )
            else -> throw GeneralSecurityException("Unsupported credential record format")
        }
    }

    private fun decryptParts(
        encodedIv: String,
        encodedPayload: String,
        key: SecretKey,
        aad: ByteArray?,
    ): ByteArray {
        val iv = decode(encodedIv)
        val encrypted = decode(encodedPayload)
        if (iv.size != GCM_IV_LENGTH_BYTES || encrypted.size < GCM_TAG_LENGTH_BYTES) {
            throw GeneralSecurityException("Malformed credential record")
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(encrypted)
    }

    private fun decode(value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw GeneralSecurityException("Malformed credential record", error)
    }

    private fun aad(profileId: String): ByteArray = buildString {
        append(AAD_NAMESPACE)
        append('\u0000')
        append(SCHEMA_VERSION)
        append('\u0000')
        append(profileId)
    }.toByteArray(StandardCharsets.UTF_8)
}
