package com.nuvio.tv.updater

import android.os.Build
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import com.nuvio.tv.updater.model.AppUpdate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class NoEligibleUpdateException :
    IllegalStateException("No compatible Nuvio ReSeq APK release found")

internal fun latestMobileRelease(releases: List<GitHubReleaseDto>, abis: List<String>): Pair<GitHubReleaseDto, GitHubAssetDto>? =
    releases.mapNotNull { release ->
        val tag = release.tagName ?: return@mapNotNull null
        if (release.draft || !tag.startsWith("mobile-v")) return@mapNotNull null
        val version = tag.removePrefix("mobile-v")
        val parsed = VersionUtils.parse(version) ?: return@mapNotNull null
        val asset = (abis + "universal").firstNotNullOfOrNull { abi ->
            val name = "Nuvio-ReSeq-Mobile-v$version-$abi.apk"
            release.assets.find { it.name == name && ReleaseSelector.isOwnApk(it, BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO) }
        } ?: return@mapNotNull null
        parsed to (release to asset)
    }.maxByOrNull { it.first }?.second

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubReleaseApi: GitHubReleaseApi,
    private val preferences: UpdatePreferences
) {
    private val checkMutex = Mutex()

    /** A null result means automatic checks are disabled. Manual checks always contact the release server. */
    suspend fun getLatestUpdate(force: Boolean = false): Result<AppUpdate>? = checkMutex.withLock {
        if (!force) {
            val frequency = preferences.checkFrequency.first()
            if (frequency == UpdateCheckFrequency.NEVER) return@withLock null
            preferences.cachedCheck(frequency)?.let { return@withLock it }
        }
        val result = fetchLatestUpdate()
        if (result.isSuccess || result.exceptionOrNull() is NoEligibleUpdateException) {
            preferences.recordCheck(result.getOrNull())
        }
        result
    }

    private suspend fun fetchLatestUpdate(): Result<AppUpdate> {
        return runCatching {
            val owner = BuildConfig.GITHUB_OWNER
            val repo = BuildConfig.GITHUB_REPO

            val releaseWithAsset = if (BuildConfig.FLAVOR == "mobile") {
                val response = gitHubReleaseApi.getReleases(owner, repo)
                if (!response.isSuccessful) error("GitHub API error: ${response.code()}")
                latestMobileRelease(response.body().orEmpty(), Build.SUPPORTED_ABIS.toList())
            } else {
                val response = gitHubReleaseApi.getLatestRelease(owner, repo)
                if (!response.isSuccessful) error("GitHub API error: ${response.code()}")
                ReleaseSelector.eligibleReleases(listOf(response.body() ?: error("Empty GitHub release response")))
                    .firstNotNullOfOrNull { release ->
                        AbiSelector.chooseBestApkAsset(release.assets.filter { ReleaseSelector.isOwnApk(it, owner, repo) })?.let { asset ->
                            release to asset
                        }
                    }
            } ?: throw NoEligibleUpdateException()
            val (dto, asset) = releaseWithAsset

            val tag = dto.tagName?.takeIf { it.isNotBlank() }
                ?: dto.name?.takeIf { it.isNotBlank() }
                ?: error("Release has no tag/name")

            AppUpdate(
                tag = tag.removePrefix("mobile-"),
                title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = dto.body.orEmpty(),
                releaseUrl = dto.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = asset.size,
                assetDigest = asset.digest
            )
        }
    }
}
