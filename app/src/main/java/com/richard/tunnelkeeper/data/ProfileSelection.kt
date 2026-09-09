package com.richard.tunnelkeeper.data

import com.richard.tunnelkeeper.model.ConnectionProfile

internal object ProfileSelection {
    fun findById(profiles: List<ConnectionProfile>, profileId: String?): ConnectionProfile? =
        profiles.firstOrNull { it.id == profileId }

    fun resolve(profiles: List<ConnectionProfile>, persistedProfileId: String?): String? =
        findById(profiles, persistedProfileId)?.id ?: profiles.firstOrNull()?.id
}
