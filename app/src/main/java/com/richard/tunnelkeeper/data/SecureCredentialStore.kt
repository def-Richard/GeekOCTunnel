package com.richard.tunnelkeeper.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.richard.tunnelkeeper.model.VpnCredentials
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecureCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyProfilePreferences = context.getSharedPreferences(
        LEGACY_PROFILE_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun save(profileId: String, credentials: VpnCredentials) {
        if (credentials.username.isBlank() || credentials.password.isEmpty()) {
            remove(profileId)
            return
        }

        val clearText = org.json.JSONObject()
            .put("schemaVersion", PAYLOAD_SCHEMA_VERSION)
            .put("username", credentials.username)
            .put("password", credentials.password)
            .put("sslGroup", credentials.sslGroup)
            .toString()
        val clearBytes = clearText.toByteArray(StandardCharsets.UTF_8)
        val storedValue = try {
            CredentialCipherCodec.encrypt(profileId, clearBytes, getOrCreateKey())
        } finally {
            clearBytes.fill(0)
        }
        check(preferences.edit().putString(profileId, storedValue).commit())
    }

    fun read(profileId: String): VpnCredentials? {
        val storedValue = preferences.getString(profileId, null) ?: return null
        return try {
            val decrypted = CredentialCipherCodec.decrypt(profileId, storedValue, getOrCreateKey())
            val credentials = try {
                decodeCredentials(profileId, decrypted)
            } finally {
                decrypted.bytes.fill(0)
            } ?: return null
            if (credentials.username.isBlank() || credentials.password.isEmpty()) return null

            if (decrypted.requiresMigration) {
                save(profileId, credentials)
            }
            credentials
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeCredentials(
        profileId: String,
        decrypted: DecryptedCredentialPayload,
    ): VpnCredentials? {
        val clearText = String(decrypted.bytes, StandardCharsets.UTF_8)
        if (clearText.trimStart().startsWith('{')) {
            val value = org.json.JSONObject(clearText)
            if (!decrypted.requiresMigration && value.optInt("schemaVersion") != PAYLOAD_SCHEMA_VERSION) {
                return null
            }
            return VpnCredentials(
                username = value.getString("username"),
                password = value.getString("password"),
                sslGroup = value.optString("sslGroup"),
            )
        }

        if (!decrypted.requiresMigration || clearText.isEmpty()) return null
        val legacyProfile = findLegacyProfile(profileId) ?: return null
        return VpnCredentials(
            username = legacyProfile.username,
            password = clearText,
            sslGroup = legacyProfile.sslGroup,
        )
    }

    private fun findLegacyProfile(profileId: String): LegacyProfile? = runCatching {
        val storedProfiles = legacyProfilePreferences.getString(LEGACY_PROFILES_KEY, null) ?: return null
        val profiles = org.json.JSONArray(storedProfiles)
        for (index in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(index)
            if (profile.optString("id") != profileId) continue
            val username = profile.optString("username").trim()
            if (username.isEmpty()) return null
            return LegacyProfile(username = username, sslGroup = profile.optString("sslGroup"))
        }
        null
    }.getOrNull()

    fun remove(profileId: String) {
        check(preferences.edit().remove(profileId).commit())
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "encrypted_credentials"
        const val LEGACY_PROFILE_PREFERENCES_NAME = "connection_profiles"
        const val LEGACY_PROFILES_KEY = "profiles"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.richard.tunnelkeeper.credentials.v1"
        const val KEY_SIZE_BITS = 256
        const val PAYLOAD_SCHEMA_VERSION = 2
    }
}

private data class LegacyProfile(
    val username: String,
    val sslGroup: String,
)
