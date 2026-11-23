package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.ondemand.*
import com.nuvio.tv.livetv.parental.ParentalControls

@Composable
internal fun MobileVodCategories(vm: OnDemandViewModel, kind: VodKind, close: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    var verified by remember(settings.parentalPin) { mutableStateOf(false) }
    if (ParentalControls.enabled(settings) && !verified) {
        MobilePinPrompt("Manage IPTV categories", close) { value -> ParentalControls.checkPin(value, settings).also { verified = it } }
        return
    }
    val categories by produceState<List<OnDemandSection.Category>?>(null, vm, kind) { value = vm.allCategories() }
    var query by remember { mutableStateOf("") }
    TouchSheet(close) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(12.dp)) {
            item { BrowseHeading("Manage IPTV categories", close); MobileTextInput("Find category", query) { query = it } }
            if (categories == null) item { LinearProgressIndicator() }
            items(categories.orEmpty().filter { it.category.name.contains(query, true) }, key = { it.key }) { category ->
                SectionTitle(listOfNotNull(category.playlistName, category.category.name).joinToString(" · "))
                TogglePreference("Visible", category.key !in user.vodHiddenCategories) { show ->
                    vm.saveVisibility(listOf(category), if (show) emptySet() else setOf(category.key))
                }
                if (verified) TogglePreference("Require parental PIN", ("vod:" + category.category.uid) in user.lockedGroups) {
                    vm.setLocked(category, it)
                }
            }
        }
    }
}
