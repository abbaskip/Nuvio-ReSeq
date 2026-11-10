package com.nuvio.tv.data.tvdb

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.asStateFlow

/** Same encrypted persistence and profile lifecycle used by native integrations. */
@Singleton
class TvdbCredentialStore @Inject constructor(@ApplicationContext context: Context) : ProfileScopedCredentialStore {
    private val protected = ProtectedProfilePreferences(context, "tvdb_credentials", "com.nuvio.tv.tvdb.credentials.v1")
    // Android Keystore can become unreadable after a device restore. Only explicit local
    // removals should ever delete a Drive credential.
    private val removals = context.getSharedPreferences("tvdb_drive_removals", Context.MODE_PRIVATE)
    private val revisions = ConcurrentHashMap<Int, Long>()
    @Volatile private var epoch = 0L
    private val lock = Any()

    private val updates = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val changes = updates.asStateFlow()

    fun read(profileId: Int): String? = synchronized(lock) { protected.read(profileId) }
    fun revision(profileId: Int): Long = epoch + (revisions[profileId] ?: 0L)

    fun explicitlyRemoved(profileId: Int): Boolean = removals.getBoolean(profileId.toString(), false)

    fun save(profileId: Int, apiKey: String?) = synchronized(lock) {
        val value = apiKey?.trim()?.takeIf(String::isNotBlank)
        if (protected.read(profileId) != value) write(profileId, value, userRemoval = value == null)
    }

    fun replaceIfUnchanged(profileId: Int, expected: String?, value: String?) = synchronized(lock) {
        if (protected.read(profileId) == expected) write(profileId, value, userRemoval = false)
    }

    private fun write(profileId: Int, value: String?, userRemoval: Boolean) {
        val edit = removals.edit()
        if (userRemoval) edit.putBoolean(profileId.toString(), true) else edit.remove(profileId.toString())
        check(edit.commit()) { "Unable to update TVDB credential state" }
        protected.write(profileId, value)
        revisions[profileId] = (revisions[profileId] ?: 0L) + 1
        updates.value += 1
    }

    override fun removeProfile(profileId: Int) = synchronized(lock) { write(profileId, null, userRemoval = false) }
    override fun clearAllProfiles() = synchronized(lock) {
        protected.clear()
        check(removals.edit().clear().commit()) { "Unable to clear TVDB credential state" }
        epoch += 1_000_000L
        updates.value += 1
    }
}
