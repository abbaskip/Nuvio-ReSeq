package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import java.net.URI

internal object ReleaseSelector {
    // Beta version labels belong to our single channel; GitHub's draft/prerelease flags do not.
    fun eligibleReleases(releases: List<GitHubReleaseDto>): List<GitHubReleaseDto> = releases
        .filterNot { it.draft || it.prerelease }
        .mapNotNull { release ->
            val version = VersionUtils.parse(release.tagName) ?: VersionUtils.parse(release.name)
            version?.let { release to it }
        }
        .sortedByDescending { it.second }
        .map { it.first }

    fun isOwnApk(asset: GitHubAssetDto, owner: String, repo: String): Boolean {
        if (!asset.name.startsWith("Nuvio-ReSeq-") || !asset.name.endsWith(".apk")) return false
        val uri = runCatching { URI(asset.browserDownloadUrl) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
            uri.userInfo == null && uri.port == -1 &&
            uri.path.startsWith("/$owner/$repo/releases/download/", ignoreCase = true) &&
            uri.path.substringAfterLast('/') == asset.name
    }
}
