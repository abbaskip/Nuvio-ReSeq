package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.ui.screens.settings.*

@Composable
internal fun MobileAbout(vm: SupportersContributorsViewModel = hiltViewModel()) {
    val uri = LocalUriHandler.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf("About") }
    val links = attributionLinks()
    val upstream = "https://github.com/homelessbrian/NuvioPlusIPTV-Mobile"
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { LazyRow { items(listOf("About", "Contributors", "Sponsors", "Supporters", "Licenses")) { tab ->
            Action(tab, selected = section == tab) {
                section = tab
                SupportersContributorsTab.entries.firstOrNull { it.name == tab }?.let(vm::onSelectTab)
            }
        } } }
        when (section) {
            "About" -> item {
                SectionTitle("Nuvio ReSeq Mobile")
                Label("Version " + BuildConfig.VERSION_NAME)
                MobileCrashReport()
                Action("Source code and releases") { uri.openUri("https://github.com/abbaskip/Nuvio-ReSeq") }
                Action("Report an issue") { uri.openUri("https://github.com/abbaskip/Nuvio-ReSeq/issues") }
            }
            "Licenses" -> {
                item {
                    SectionTitle("Mobile interface")
                    Label("Adapted from NuvioPlusIPTV-Mobile by homelessbrian and contributors, based on NuvioMedia/NuvioMobile and the phone port by ghbarker. GNU GPL version 3.")
                    Action("Mobile interface source") { uri.openUri(upstream + "/tree/cbd3d8575217d85ff251d831b6cf72b90c1e4b4d") }
                    Action("GNU GPL version 3") { uri.openUri("https://www.gnu.org/licenses/gpl-3.0.html") }
                }
                items(links) { (name, body, url) -> Label(name, strong = true); Label(body); Action("View " + name) { uri.openUri(url) } }
            }
            "Contributors" -> {
                if (state.isContributorsLoading) item { Label("Loading contributors…") }
                state.contributorsErrorMessage?.let { item { Label(it); Action("Retry", onClick = vm::retryContributors) } }
                items(state.contributors) { c ->
                    Label(c.name, strong = true)
                    Label("TV ${c.tvContributions} · Mobile ${c.mobileContributions} · Web ${c.webContributions}")
                    c.profileUrl?.let { Action("Profile") { uri.openUri(it) } }
                }
            }
            "Supporters" -> {
                if (state.isSupportersLoading) item { Label("Loading supporters…") }
                state.supportersErrorMessage?.let { item { Label(it); Action("Retry", onClick = vm::retrySupporters) } }
                items(state.supporters) { s -> Label(s.name, strong = true); s.supporterSince?.let { Label("Supporting since " + it) } }
            }
            "Sponsors" -> {
                if (state.isSponsorsLoading) item { Label("Loading sponsors…") }
                state.sponsorsErrorMessage?.let { item { Label(it); Action("Retry", onClick = vm::retrySponsors) } }
                items(state.sponsors) { s -> Label(s.name, strong = true); s.channelUrl?.let { Action("Channel") { uri.openUri(it) } } }
            }
        }
    }
}
