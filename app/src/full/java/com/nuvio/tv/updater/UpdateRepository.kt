package com.nuvio.tv.updater

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.updater.model.AppUpdate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class NoEligibleUpdateException :
    IllegalStateException("No compatible Nuvio ReSeq APK release found")

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

            val response = gitHubReleaseApi.getLatestRelease(owner = owner, repo = repo)
            if (!response.isSuccessful) error("GitHub API error: ${response.code()}")
            val releases = listOf(response.body() ?: error("Empty GitHub release response"))
            val releaseWithAsset = ReleaseSelector
                .eligibleReleases(releases)
                .firstNotNullOfOrNull { release ->
                    AbiSelector.chooseBestApkAsset(release.assets.filter { ReleaseSelector.isOwnApk(it, owner, repo) })?.let { asset ->
                        release to asset
                    }
                }
                ?: throw NoEligibleUpdateException()
            val (dto, asset) = releaseWithAsset

            val tag = dto.tagName?.takeIf { it.isNotBlank() }
                ?: dto.name?.takeIf { it.isNotBlank() }
                ?: error("Release has no tag/name")

            AppUpdate(
                tag = tag,
                title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = dto.body.orEmpty(),
                releaseUrl = dto.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = asset.size
            )
        }
    }
}
