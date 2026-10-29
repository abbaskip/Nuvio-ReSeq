package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSelectorTest {
    @Test
    fun `ordinary beta releases are eligible and sorted numerically`() {
        val releases = listOf(release("v0.1.0-beta.9"), release("v0.1.0-beta.10"), release("v0.1.0"))
        assertEquals(
            listOf("v0.1.0", "v0.1.0-beta.10", "v0.1.0-beta.9"),
            ReleaseSelector.eligibleReleases(releases).map { it.tagName }
        )
        assertTrue(VersionUtils.isRemoteNewer("v0.1.0-beta.10", "0.1.0-beta.9"))
        assertFalse(VersionUtils.isRemoteNewer("v0.1.0-beta.1", "0.1.0-beta.1"))
    }

    @Test
    fun `drafts GitHub prereleases and invalid versions are excluded`() {
        val releases = listOf(
            release("v0.2.0", draft = true),
            release("v0.3.0-beta.1", prerelease = true),
            release("nightly"),
            release("v0.1.0-beta.1")
        )
        assertEquals(listOf("v0.1.0-beta.1"), ReleaseSelector.eligibleReleases(releases).map { it.tagName })
    }

    @Test
    fun `only ReSeq APKs hosted by our repository are accepted`() {
        val assetName = "Nuvio-ReSeq-v0.1.0-beta.1-universal.apk"
        val url = "https://github.com/abbaskip/Nuvio-ReSeq/releases/download/v0.1.0-beta.1/$assetName"
        fun allowed(url: String, name: String = assetName) =
            ReleaseSelector.isOwnApk(GitHubAssetDto(name, url), "abbaskip", "Nuvio-ReSeq")
        assertTrue(allowed(url))
        assertFalse(allowed(url.replace("abbaskip/Nuvio-ReSeq", "NuvioMedia/NuvioTV")))
        assertFalse(allowed(url.replace("github.com", "github.com.example.org")))
        assertFalse(allowed(url.replace("https:", "http:")))
        assertFalse(allowed(url.replace("Nuvio-ReSeq/releases", "Nuvio-ReSeq-other/releases")))
        assertFalse(allowed(url, "app-full-universal-release.apk"))
        assertFalse(allowed("not a URL"))
    }

    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false) =
        GitHubReleaseDto(tagName = tag, name = tag, draft = draft, prerelease = prerelease)
}
