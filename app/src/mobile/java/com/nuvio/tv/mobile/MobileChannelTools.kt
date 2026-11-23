package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.ui.LiveTvViewModel

@Composable
internal fun MobilePinPrompt(title: String, close: () -> Unit, tryUnlock: (String) -> Boolean) {
    var value by remember(title) { mutableStateOf("") }
    var rejected by remember(title) { mutableStateOf(false) }
    TouchSheet(close) {
        Column(Modifier.fillMaxWidth().imePadding().padding(12.dp)) {
            SectionTitle(title)
            OutlinedTextField(value, { value = it; rejected = false }, singleLine = true, shape = RectangleShape,
                label = { Text("Parental PIN") }, visualTransformation = PasswordVisualTransformation(), isError = rejected)
            if (rejected) Label("Incorrect PIN.")
            Action("Unlock") { rejected = !tryUnlock(value); if (rejected) value = "" }
        }
    }
}

@Composable
internal fun MobileChannelEditor(channel: LiveChannel, vm: LiveTvViewModel, close: () -> Unit) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val user by vm.userState.collectAsStateWithLifecycle()
    var name by remember(channel.key) { mutableStateOf(channel.name) }
    var number by remember(channel.key) { mutableStateOf(channel.number.toString()) }
    var group by remember { mutableStateOf("") }
    var copies by remember { mutableStateOf(false) }
    TouchSheet(close) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { SectionTitle(channel.name)
                MobileTextInput("Channel name", name) { name = it }
                MobileTextInput("Channel number", number) { if (it.all(Char::isDigit)) number = it }
                Action("Save channel") { vm.renameChannel(channel, name.trim().ifEmpty { null }); vm.setChannelNumber(channel, number.toIntOrNull()); close() }
                Action(if (channel.key in user.favorites) "Remove from favourites" else "Add to favourites") { vm.toggleFavorite(channel) }
                Action("Hide channel") { vm.hideChannel(channel); close() }
                val position = ui.channels.indexOfFirst { it.key == channel.key }
                if (position >= 0 && vm.canReorder(ui.selectedGroupId)) {
                    fun move(step: Int) {
                        val order = ui.channels.map { it.key }.toMutableList()
                        order.add(position + step, order.removeAt(position))
                        vm.saveOrder(ui.selectedGroupId, order)
                    }
                    Row {
                        if (position > 0) Action("Move up") { move(-1) }
                        if (position < ui.channels.lastIndex) Action("Move down") { move(1) }
                    }
                }
                Action("Copy to a group") { copies = !copies }
                if (vm.isCopyIn(ui.selectedGroupId, channel)) Action("Remove copy from this group") { vm.removeCopy(ui.selectedGroupId, channel); close() }
            }
            if (copies) items(vm.copyTargets(channel).filterNot { it.locked }) { to ->
                Action("Copy to " + to.title) { vm.copyToGroup(to.id, channel); close() }
            }
            item {
                SectionTitle("Custom groups")
            }
            items(ui.customGroups) { g ->
                Action(g.title) { vm.addToGroup(g.id, channel); close() }
                if (g.id == ui.selectedGroupId) Action("Remove from " + g.title) { vm.removeFromGroup(g.id, channel); close() }
            }
            item { MobileTextInput("New group name", group) { group = it }
                Action("Create group with channel") { if (group.isNotBlank()) { vm.createGroupWith(group.trim(), channel); close() } }
            }
        }
    }
}
