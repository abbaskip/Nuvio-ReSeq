package com.nuvio.tv.ui.screens.flickpick

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.sync.homeCollectionKey
import com.nuvio.tv.core.sync.isHomeCatalogDisabled
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.core.trakt.TraktPublicListSourceResolver
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ui.screens.home.shouldShowOnHome
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

internal data class FlickPickList(val tab: LibraryListTab, val sources: List<FlickPickSource> = emptyList()) {
    val key get() = tab.key
}

internal sealed interface FlickPickSource {
    data class Catalog(val addon: Addon, val catalog: CatalogDescriptor, val id: String = catalog.id,
        val genre: String? = null, val savedType: String? = null) : FlickPickSource
    data class Tmdb(val source: TmdbCollectionSource) : FlickPickSource
    data class Trakt(val source: TraktCollectionSource) : FlickPickSource
}

private fun CatalogDescriptor.canContainMovies() = type == ContentType.MOVIE || apiType == ServerMediaKind.MIXED.contentType

internal fun homeMovieLists(addons: List<Addon>, collections: List<com.nuvio.tv.domain.model.Collection>,
    disabled: Set<String>, titles: Map<String, String>): List<FlickPickList> = buildList {
    fun choice(key: String, title: String, sources: List<FlickPickSource>) =
        FlickPickList(LibraryListTab(key, title, LibraryListTab.Type.EXTERNAL), sources)

    addons.forEach { addon ->
        for (catalog in addon.catalogs) {
            if (!catalog.canContainMovies() || !catalog.shouldShowOnHome()) continue
            if (isHomeCatalogDisabled(disabled, addon.id, addon.baseUrl, catalog.apiType, catalog.id, catalog.name)) continue
            val key = catalogRowStableKey(addon.id, addon.baseUrl, catalog.apiType, catalog.id)
            val title = titles[homeCatalogKey(addon.id, catalog.apiType, catalog.id)].orEmpty().ifBlank { catalog.name }
            add(choice("home:$key", "$title · ${addon.displayName}", listOf(FlickPickSource.Catalog(addon, catalog))))
        }
    }
    for (collection in collections) {
        val key = homeCollectionKey(collection.id)
        if (key in disabled) continue
        for (folder in collection.folders) {
            val sources = folder.sources.mapNotNull { source ->
                when (source) {
                    is TmdbCollectionSource -> FlickPickSource.Tmdb(source).takeIf {
                        source.mediaType == TmdbCollectionMediaType.MOVIE || source.sourceType == TmdbCollectionSourceType.LIST
                    }
                    is TraktCollectionSource -> FlickPickSource.Trakt(source).takeIf { source.mediaType == TmdbCollectionMediaType.MOVIE }
                    is AddonCatalogCollectionSource -> {
                        val addon = addons.firstOrNull { it.id == source.addonId } ?: return@mapNotNull null
                        val catalog = addon.catalogs.findCollectionCatalog(source.type, source.catalogId)
                            ?: addon.catalogs.findCollectionCatalog(source.type, source.catalogId.substringBefore(','))
                            ?: return@mapNotNull null
                        FlickPickSource.Catalog(addon, catalog, source.catalogId, source.genre, source.type)
                            .takeIf { catalog.canContainMovies() }
                    }
                }
            }.distinct()
            if (sources.isNotEmpty()) add(choice("folder:${collection.id}:${folder.id}",
                "${titles[key].orEmpty().ifBlank { collection.title }} / ${folder.title}", sources))
        }
    }
}.distinctBy { it.key }

internal class FlickPickHomeLists @Inject constructor(
    addons: AddonRepository,
    servers: ServerCatalog,
    collections: CollectionsDataStore,
    layout: LayoutPreferenceDataStore,
    private val catalogs: CatalogRepository,
    private val tmdb: TmdbCollectionSourceResolver,
    private val trakt: TraktPublicListSourceResolver
) {
    val choices = combine(
        combine(addons.getInstalledAddons(), servers.addons) { installed, local -> installed.enabledAddons() + local },
        collections.collections, layout.disabledHomeCatalogKeys, layout.customCatalogTitles
    ) { available, folders, disabled, titles -> homeMovieLists(available, folders, disabled.toSet(), titles) }.distinctUntilChanged()

    private data class Cursor(val source: FlickPickSource, val page: Int = 1, val skip: Int = 0, val duplicates: Int = 0)

    // Catalogs do not all expose a total. Load on demand and advance through a folder's
    // sources in rounds, using each provider's own cursor when a page has no eligible movie.
    fun moviePages(list: FlickPickList): Flow<List<MetaPreview>> = flow {
        val pending = ArrayDeque(list.sources.shuffled().map { Cursor(it) })
        val seen = mutableMapOf<FlickPickSource, MutableSet<String>>()
        var failure: IOException? = null
        while (pending.isNotEmpty()) {
            val cursor = pending.removeFirst()
            val response = when (val source = cursor.source) {
                is FlickPickSource.Tmdb -> tmdb.resolve(source.source, cursor.page)
                is FlickPickSource.Trakt -> trakt.resolve(source.source, cursor.page)
                is FlickPickSource.Catalog -> {
                    val extras = source.genre?.takeUnless { it.isBlank() || it.equals("None", ignoreCase = true) }
                        ?.let { mapOf("genre" to it) }.orEmpty()
                    catalogs.getCatalog(source.addon.baseUrl, source.addon.id, source.addon.displayName,
                        source.id, source.catalog.name, source.catalog.apiType, cursor.skip, source.catalog.skipStep(),
                        extras, source.catalog.supportsExtra("skip"), savedSourceType = source.savedType)
                }
            }.first { it !is NetworkResult.Loading }
            if (response is NetworkResult.Error) {
                failure = IOException(response.message)
                continue
            }
            val row = (response as NetworkResult.Success).data
            val sourceSeen = seen.getOrPut(cursor.source) { hashSetOf() }
            val newItems = row.items.filter { sourceSeen.add(it.id) }
            emit(newItems.filter { it.type == ContentType.MOVIE }.shuffled())
            val duplicates = if (newItems.isEmpty() && row.items.isNotEmpty()) cursor.duplicates + 1 else 0
            if (row.hasMore && duplicates < 3) {
                val skip = row.nextCatalogSkip()
                if (cursor.source !is FlickPickSource.Catalog || skip > cursor.skip && row.items.isNotEmpty())
                    pending.addLast(cursor.copy(page = cursor.page + 1, skip = skip, duplicates = duplicates))
            }
        }
        failure?.let { throw it }
    }
}
