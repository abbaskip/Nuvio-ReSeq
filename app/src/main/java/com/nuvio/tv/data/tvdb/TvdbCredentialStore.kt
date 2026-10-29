package com.nuvio.tv.data.tvdb

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Same encrypted persistence and profile lifecycle used by native integrations. */
@Singleton
class TvdbCredentialStore @Inject constructor(@ApplicationContext context: Context) : ProfileScopedCredentialStore {
    private val protected = ProtectedProfilePreferences(context, "tvdb_credentials", "com.nuvio.tv.tvdb.credentials.v1")
    private val revisions = ConcurrentHashMap<Int, Long>()
    @Volatile private var epoch = 0L
    private val lock = Any()

    fun read(profileId: Int): String? = synchronized(lock) { protected.read(profileId) }
    fun revision(profileId: Int): Long = epoch + (revisions[profileId] ?: 0L)

    fun save(profileId: Int, apiKey: String?) = synchronized(lock) {
        protected.write(profileId, apiKey?.trim()?.takeIf(String::isNotBlank))
        revisions[profileId] = (revisions[profileId] ?: 0L) + 1
    }

    override fun removeProfile(profileId: Int) { save(profileId, null) }
    override fun clearAllProfiles() = synchronized(lock) {
        protected.clear()
        epoch += 1_000_000L
    }
}
