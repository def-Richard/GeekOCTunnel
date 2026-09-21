package com.richard.tunnelkeeper.data

import android.content.Context
import com.richard.tunnelkeeper.model.ConnectionProfile
import org.json.JSONArray
import org.json.JSONObject

class ProfileRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): List<ConnectionProfile> {
        val values = preferences.getString(KEY_PROFILES, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(values)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        ConnectionProfile(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            server = item.getString("server"),
                            ignoreCertificateErrors = item.optBoolean("ignoreCertificateErrors", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun upsert(profile: ConnectionProfile) {
        val profiles = load().toMutableList()
        val currentIndex = profiles.indexOfFirst { it.id == profile.id }
        if (currentIndex >= 0) {
            profiles[currentIndex] = profile
        } else {
            profiles.add(profile)
        }
        save(profiles)
    }

    fun loadSelectedProfileId(profiles: List<ConnectionProfile>): String? =
        ProfileSelection.resolve(
            profiles = profiles,
            persistedProfileId = preferences.getString(KEY_SELECTED_PROFILE_ID, null),
        )

    fun select(profileId: String) {
        check(preferences.edit().putString(KEY_SELECTED_PROFILE_ID, profileId).commit())
    }

    fun saveQuickConnectProfiles(profileIds: List<String>) {
        check(preferences.edit().putStringSet(KEY_QUICK_CONNECT_IDS, profileIds.toSet()).commit())
    }

    fun loadQuickConnectProfiles(): List<ConnectionProfile> {
        val profiles = load()
        val ids = preferences.getStringSet(KEY_QUICK_CONNECT_IDS, emptySet()).orEmpty()
        return profiles.filter { it.id in ids }.ifEmpty {
            profiles.filter { it.id == loadSelectedProfileId(profiles) }
        }
    }

    fun remove(profileId: String) {
        save(load().filterNot { it.id == profileId })
    }

    private fun save(profiles: List<ConnectionProfile>) {
        val value = JSONArray()
        profiles.forEach { profile ->
            value.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("server", profile.server)
                    .put("ignoreCertificateErrors", profile.ignoreCertificateErrors),
            )
        }
        check(preferences.edit().putString(KEY_PROFILES, value.toString()).commit())
    }

    private companion object {
        const val PREFERENCES_NAME = "connection_profiles"
        const val KEY_PROFILES = "profiles"
        const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
        const val KEY_QUICK_CONNECT_IDS = "quick_connect_profile_ids"
    }
}
