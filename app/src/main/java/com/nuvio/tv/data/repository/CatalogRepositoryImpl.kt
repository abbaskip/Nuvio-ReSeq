package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.mediaserver.messageRes
import com.nuvio.tv.data.mediaserver.serverFailure
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.catalogTypeKey
import com.nuvio.tv.domain.model.catalogTypesMatch
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val serverCatalog: ServerCatalog
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
        private val typePathErrors = setOf(400, 404, 422)
    }

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean,
        posterScreen: com.nuvio.tv.core.poster.CustomPosterScreen,
        savedSourceType: String?
    ): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)

        if (ServerCatalog.isServerAddon(addonBaseUrl)) {
            val row = try {
                serverCatalog.catalog(addonBaseUrl, addonId, addonName, catalogId, catalogName, type, skip, extraArgs)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                emit(NetworkResult.Error(context.getString(error.serverFailure().messageRes())))
                return@flow
            }
            emit(NetworkResult.Success(row))
            return@flow
        }

        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId type=$type catalogId=$catalogId skip=$skip"
        )

        val page = safeApiCall(context) {
            suspend fun requestPage(requestType: String) =
                api.getCatalog(buildCatalogUrl(addonBaseUrl, requestType, catalogId, skip, extraArgs))

            var response = requestPage(type)
            if (response.code() !in typePathErrors) return@safeApiCall response
            // Older folders used the saved spelling; older standalone rows used the standard lowercase type.
            val savedType = savedSourceType?.trim()?.takeIf { catalogTypesMatch(it, type) }
            for (alternate in listOfNotNull(savedType, catalogTypeKey(type)).distinct()) {
                if (alternate == type) continue
                response.errorBody()?.close()
                response = requestPage(alternate)
                if (response.code() !in typePathErrors) break
            }
            response
        }
        when (val result = page) {
            is NetworkResult.Success -> {
                val rawItemCount = result.data.metas.size
                val posterPattern = layoutPreferenceDataStore.customPosterUrlPattern
                    .let { flow -> flow.first() }
                val enabledScreens = layoutPreferenceDataStore.customPosterEnabledScreens.first()

                val items = result.data.metas
                    .mapNotNull { it?.toDomainOrNull(type, addonBaseUrl) }
                    .distinctBy { it.id }
                    .withCustomPosterUrls(
                        com.nuvio.tv.core.poster.patternForScreen(posterPattern, posterScreen, enabledScreens)
                    )
                Log.d(
                    TAG,
                    "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${items.size}"
                )

                val catalogRow = CatalogRow(
                    addonId = addonId,
                    addonName = addonName,
                    addonBaseUrl = addonBaseUrl,
                    catalogId = catalogId,
                    catalogName = catalogName,
                    type = ContentType.fromString(type),
                    rawType = type,
                    items = items,
                    isLoading = false,
                    hasMore = supportsSkip && rawItemCount > 0,
                    currentPage = if (skipStep > 0) skip / skipStep else 0,
                    supportsSkip = supportsSkip,
                    skipStep = skipStep,
                    nextSkip = if (supportsSkip && rawItemCount > 0) skip + rawItemCount else skip,
                    extraArgs = extraArgs
                )
                emit(NetworkResult.Success(catalogRow))
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message}"
                )
                emit(result)
            }
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val trimmedBase = baseUrl.trimEnd('/')
        val queryStart = trimmedBase.indexOf('?')
        val basePath = if (queryStart >= 0) trimmedBase.substring(0, queryStart).trimEnd('/') else trimmedBase
        val baseQuery = if (queryStart >= 0) trimmedBase.substring(queryStart) else ""

        val catalogPath = if (extraArgs.isEmpty()) {
            if (skip > 0) {
                "$basePath/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$basePath/catalog/$type/$catalogId.json"
            }
        } else {
            val allArgs = LinkedHashMap<String, String>()
            allArgs.putAll(extraArgs)

            if (!allArgs.containsKey("skip") && skip > 0) {
                allArgs["skip"] = skip.toString()
            }

            val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
                "${encodeArg(key)}=${encodeArg(value)}"
            }

            "$basePath/catalog/$type/$catalogId/$encodedArgs.json"
        }

        return catalogPath + baseQuery
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
