package com.nuvio.tv.mobile

import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import com.nuvio.tv.updater.ReleaseSelector
import com.nuvio.tv.updater.VersionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class MobileRelease(val version: String, val apkUrl: String)

internal fun latestMobileRelease(releases: List<GitHubReleaseDto>, abis: List<String>): MobileRelease? =
    releases.mapNotNull { release ->
        val tag = release.tagName ?: return@mapNotNull null
        if (release.draft || !tag.startsWith("mobile-v")) return@mapNotNull null
        val version = tag.removePrefix("mobile-v")
        val parsed = VersionUtils.parse(version) ?: return@mapNotNull null
        val asset = (abis + "universal").firstNotNullOfOrNull { abi ->
            val name = "Nuvio-ReSeq-Mobile-v$version-$abi.apk"
            release.assets.find { it.name == name && ReleaseSelector.isOwnApk(it, BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO) }
        } ?: return@mapNotNull null
        parsed to MobileRelease(version, asset.browserDownloadUrl)
    }.maxByOrNull { it.first }?.second

@HiltViewModel
internal class MobileUpdateViewModel @Inject constructor(private val api: GitHubReleaseApi) : ViewModel() {
    suspend fun latest(): MobileRelease {
        val response = api.getReleases(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO)
        check(response.isSuccessful)
        return checkNotNull(latestMobileRelease(response.body().orEmpty(), Build.SUPPORTED_ABIS.toList()))
    }
}

@Composable
internal fun MobileUpdateAction(vm: MobileUpdateViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<MobileRelease?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val release = update
    if (release == null) {
        Action(if (checking) "Checking for updates…" else "Check for updates") {
            if (checking) return@Action
            checking = true
            message = null
            scope.launch {
                try {
                    val latest = vm.latest()
                    if (VersionUtils.isRemoteNewer(latest.version, BuildConfig.VERSION_NAME)) update = latest
                    else message = "ReSeq Mobile is up to date."
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    message = "Couldn’t check mobile releases. Try again."
                } finally { checking = false }
            }
        }
    } else {
        Action("Download Mobile " + release.version) {
            try { uri.openUri(release.apkUrl) }
            catch (_: IllegalArgumentException) { message = "Open the mobile release in a browser to download the APK." }
        }
    }
    message?.let { Label(it) }
}
