package com.nuvio.tv.core.tmdb

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Runtime-only credentials; deliberately separate from serializable/synced settings. */
@Singleton
class TmdbCredentialStore internal constructor(private val protected: ProtectedProfilePreferences) : ProfileScopedCredentialStore {
    @Inject constructor(@ApplicationContext context: Context) : this(
        ProtectedProfilePreferences(context, "tmdb_credentials", "com.nuvio.tv.tmdb.credentials.v1")
    )
    private val lock = Any()
    private val revisions = mutableMapOf<Int, Long>()
    private var epoch = 0L
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()

    fun access(profileId: Int): TmdbCredentialAccess = synchronized(lock) {
        TmdbCredentialAccess(profileId, revisions[profileId] ?: epoch, protected.read(profileId).orEmpty())
    }

    fun revision(profileId: Int): Long = synchronized(lock) { revisions[profileId] ?: epoch }

    fun save(profileId: Int, value: String?) = synchronized(lock) {
        protected.write(profileId, value?.trim()?.takeIf(String::isNotBlank))
        revisions[profileId] = _changes.value + 1
        _changes.value += 1
    }

    override fun removeProfile(profileId: Int) { save(profileId, null) }
    override fun clearAllProfiles() = synchronized(lock) {
        protected.clear()
        revisions.clear()
        epoch = _changes.value + 1
        _changes.value = epoch
    }
}

class TmdbCredentialAccess(val profileId: Int, val revision: Long, val apiKey: String) {
    override fun toString(): String = "TmdbCredentialAccess(profileId=$profileId, revision=$revision, configured=${apiKey.isNotBlank()})"
}
