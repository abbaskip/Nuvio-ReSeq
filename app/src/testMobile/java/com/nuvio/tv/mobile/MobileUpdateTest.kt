package com.nuvio.tv.mobile

import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import com.nuvio.tv.updater.latestMobileRelease
import org.junit.Assert.*
import org.junit.Test

class MobileUpdateTest {
    private fun release(version: String, draft: Boolean = false): GitHubReleaseDto {
        val tag = "mobile-v$version"
        val assets = listOf("universal", "arm64-v8a").map { abi ->
            val name = "Nuvio-ReSeq-Mobile-v$version-$abi.apk"
            GitHubAssetDto(name, "https://github.com/abbaskip/Nuvio-ReSeq/releases/download/$tag/$name")
        }
        return GitHubReleaseDto(tagName = tag, draft = draft, prerelease = true, assets = assets)
    }

    @Test fun choosesPublishedMobileReleaseForDeviceAbiWithUniversalFallback() {
        val releases = listOf(release("0.1.0-beta.2"), release("0.1.0-beta.3"), release("0.1.0-beta.4", draft = true),
            release("1.0.0").copy(tagName = "v1.0.0"))
        val arm = requireNotNull(latestMobileRelease(releases, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("mobile-v0.1.0-beta.3", arm.first.tagName)
        assertTrue(arm.second.browserDownloadUrl.endsWith("-arm64-v8a.apk"))
        assertTrue(requireNotNull(latestMobileRelease(releases, listOf("x86_64"))).second.browserDownloadUrl.endsWith("-universal.apk"))
    }
}
