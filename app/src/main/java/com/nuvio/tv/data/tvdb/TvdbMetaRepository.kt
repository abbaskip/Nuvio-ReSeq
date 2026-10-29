package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.repository.MetaRepositoryImpl
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import com.nuvio.tv.domain.model.EpisodeOrderStatus
import javax.inject.Inject
import javax.inject.Singleton

/** Overlay AFTER existing provider selection/caching, leaving its rules untouched. */
@Singleton
class TvdbMetaRepository @Inject constructor(
    private val delegate: MetaRepositoryImpl,
    private val resolver: dagger.Lazy<TvdbEpisodeResolver>,
    private val preferences: TvdbOrderStore,
    private val profiles: ProfileManager
) : MetaRepository by delegate {
    private val effectiveCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Meta>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Meta>?) = size > 64
        }
    )
    private fun key(profileId: Int, type: String, id: String) = "$profileId|${type.lowercase()}|$id"
    private fun Flow<NetworkResult<Meta>>.effective(type: String, id: String): Flow<NetworkResult<Meta>> = map { result ->
        if (result is NetworkResult.Success) {
            val profileId = profiles.activeProfileId.value
            val original = result.data
            var selected: TvdbOrderPreference? = null
            val meta = try {
                selected = if (original.apiType in listOf("series", "tv"))
                    preferences.selected(profileId, listOfNotNull(original.id, original.imdbId)) else null
                if (selected == null) original else resolver.get().apply(profileId, original)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                selected?.let { original.copy(episodeOrder = EpisodeOrderStatus(it.seriesId, it.orderType, null,
                    "TVDB unavailable. Using metadata provider episodes. You can restore Provider Default.")) } ?: original
            }
            for (alias in listOf(id, original.id)) {
                val cacheKey = key(profileId, type, alias)
                if (selected == null) effectiveCache.remove(cacheKey) else effectiveCache[cacheKey] = meta
            }
            if (profiles.activeProfileId.value != profileId) NetworkResult.Loading else NetworkResult.Success(meta)
        } else result
    }
    override fun getMeta(addonBaseUrl: String, type: String, id: String) = delegate.getMeta(addonBaseUrl, type, id).effective(type, id)
    override fun getMetaFromAllAddons(type: String, id: String, sourceAddonBaseUrl: String?) =
        delegate.getMetaFromAllAddons(type, id, sourceAddonBaseUrl).effective(type, id)
    override fun getProviderMeta(type: String, id: String) = delegate.getMetaFromAllAddons(type, id)
    override fun getMetaFromPrimaryAddon(type: String, id: String) = delegate.getMetaFromPrimaryAddon(type, id).effective(type, id)
    override fun getCachedMeta(type: String, id: String): Meta? {
        if (type !in listOf("series", "tv")) return delegate.getCachedMeta(type, id)
        val profileId = profiles.activeProfileId.value
        if (!preferences.hasSnapshot(profileId)) return null
        val selected = preferences.cachedSelection(profileId, id) ?: return delegate.getCachedMeta(type, id)
        return effectiveCache[key(profileId, type, id)]?.takeIf {
            it.episodeOrder?.seriesId == selected.seriesId && it.episodeOrder.selectedType == selected.orderType &&
                it.episodeOrder.effectiveType != null
        }
    }
    override suspend fun getPlaybackMeta(profileId: Int, type: String, id: String,
        context: com.nuvio.tv.domain.model.TvdbPlaybackContext): Meta? {
        if (profiles.activeProfileId.value != profileId || preferences.knownSeries(profileId, listOf(id)) != context.seriesId) return null
        val raw = delegate.getCachedMeta(type, id) ?: (delegate.getMetaFromAllAddons(type, id)
            .first { it !is NetworkResult.Loading } as? NetworkResult.Success)?.data ?: return null
        return resolver.get().forOrder(profileId, raw, TvdbOrderPreference(context.seriesId, context.orderType))
            .takeIf { profiles.activeProfileId.value == profileId }
    }
    override fun clearCache() { effectiveCache.clear(); delegate.clearCache() }
}
