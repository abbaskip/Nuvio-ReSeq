package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.ui.LiveTvSettingsViewModel
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MobileGuideGroups(vm: LiveTvViewModel, close: () -> Unit) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val user by vm.userState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val all by produceState(emptyList<ChannelGroup>(), ui.groups, user, settings) {
        value = withContext(Dispatchers.Default) { vm.groupsWithHidden() }
    }
    var groupId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var title by remember(groupId) { mutableStateOf("") }
    var create by remember { mutableStateOf("") }
    var allowed by remember(settings.parentalPin) { mutableStateOf(false) }
    var deleting by remember(groupId) { mutableStateOf(false) }
    val group = all.firstOrNull { it.id == groupId }
    val leave = { if (groupId == null) close() else groupId = null }
    if (ParentalControls.enabled(settings) && !allowed) {
        MobilePinPrompt("Manage groups", close) { pin -> vm.checkPin(pin).also { allowed = it } }
        return
    }
    TouchSheet(leave) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(12.dp)) {
            item { BrowseHeading(group?.title ?: "Manage groups", leave) }
            if (group == null) {
                item {
                    MobileTextInput("Find group", query) { query = it }
                    MobileTextInput("New group", create) { create = it }
                    Action("Create group") { if (create.isNotBlank()) { vm.createGroupWith(create.trim(), null); create = "" } }
                }
                items(all.filter { it.title.contains(query, true) }, key = { it.id }) { g ->
                    Action(g.title + if (g.id in user.hiddenGroups) " · Hidden" else "", Modifier.fillMaxWidth()) { groupId = g.id; title = g.title }
                }
            } else item {
                MobileTextInput("Group name", title) { title = it }
                Action("Save name") { vm.renameGroup(group.id, title.trim().ifEmpty { null }) }
                TogglePreference("Visible in guide", group.id !in user.hiddenGroups) { show ->
                    vm.saveGroupVisibility(listOf(group), if (show) emptySet() else setOf(group.id))
                }
                TogglePreference("Favourite group", group.favorite) { vm.toggleFavoriteGroup(group.id) }
                if (ParentalControls.enabled(settings)) TogglePreference("Require parental PIN", vm.isGroupLockSet(group.id)) {
                    vm.setGroupLocked(group.id, it)
                }
                val order = ui.groups.filterNot { it.special }.map { it.id }
                val index = order.indexOf(group.id)
                fun move(delta: Int) {
                    val sorted = order.toMutableList()
                    sorted.add(index + delta, sorted.removeAt(index))
                    vm.saveGroupOrder(sorted)
                }
                Row {
                    if (index > 0) Action("Earlier") { move(-1) }
                    if (index >= 0 && index < order.lastIndex) Action("Later") { move(1) }
                }
                if (group.id.startsWith(ChannelGroup.CUSTOM_PREFIX)) Action("Delete group") { deleting = true }
                if (deleting) ConfirmAction("Delete " + group.title + "?", "Delete", { deleting = false }) { vm.deleteGroup(group.id); groupId = null }
            }
        }
    }
}

@Composable
internal fun MobileParentalSettings(vm: LiveTvSettingsViewModel, close: () -> Unit) {
    val state by vm.settings.collectAsStateWithLifecycle()
    var verified by remember(state.parentalPin) { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    if (ParentalControls.enabled(state) && !verified) {
        MobilePinPrompt("Current parental PIN", close) { value -> ParentalControls.checkPin(value, state).also { verified = it } }
    } else TouchSheet(close) {
        Column(Modifier.fillMaxWidth().imePadding().padding(12.dp)) {
            BrowseHeading("Parental controls", close)
            MobileSecretField("New PIN", pin) { pin = it.filter(Char::isDigit) }
            MobileSecretField("Confirm PIN", confirmation) { confirmation = it.filter(Char::isDigit) }
            Action("Save PIN") {
                if (pin.length < 4 || pin != confirmation) error = "Enter the same PIN of at least four digits twice."
                else { val newPin = pin; vm.update { it.copy(parentalPin = newPin) }; close() }
            }
            if (verified) {
                TogglePreference("Lock adult groups and categories", state.lockAdultContent) { v -> vm.update { it.copy(lockAdultContent = v) } }
                Action("Remove parental PIN") { vm.update { it.copy(parentalPin = "") }; close() }
            }
            if (error.isNotEmpty()) Label(error)
        }
    }
}
