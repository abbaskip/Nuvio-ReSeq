package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.sync.SetProfilePinResult
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.screens.profile.ProfileSelectionViewModel

@Composable
internal fun MobileProfileEditor(profile: UserProfile, locked: Boolean, vm: ProfileSelectionViewModel, close: () -> Unit) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var color by remember(profile.id) { mutableStateOf(profile.avatarColorHex) }
    var addons by remember(profile.id) { mutableStateOf(profile.usesPrimaryAddons) }
    var plugins by remember(profile.id) { mutableStateOf(profile.usesPrimaryPlugins) }
    var current by remember(profile.id) { mutableStateOf("") }
    var newPin by remember(profile.id) { mutableStateOf("") }
    var unlocked by remember(profile.id) { mutableStateOf(false) }
    var remove by remember(profile.id) { mutableStateOf(false) }
    var message by remember(profile.id) { mutableStateOf("") }
    TouchSheet(close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(12.dp)) {
            BrowseHeading(profile.name, close)
            if (locked && !unlocked) {
                MobileSecretField("Current profile PIN", current) { current = it.filter(Char::isDigit).take(4) }
                Action("Verify PIN") { if (current.length == 4) vm.verifyProfilePin(profile.id, current) {
                    if (it.getOrNull()?.unlocked == true) { unlocked = true; message = "" } else message = "Could not verify the PIN."
                } }
            } else {
                MobileTextInput("Profile name", name) { name = it }
                MobileTextInput("Avatar colour (#RRGGBB)", color) { color = it }
                if (!profile.isPrimary) {
                    TogglePreference("Use primary profile add-ons", addons) { addons = it }
                    TogglePreference("Use primary profile extensions", plugins) { plugins = it }
                }
                Action("Save profile") {
                    if (name.isBlank() || !color.matches(Regex("#[0-9a-fA-F]{6}"))) message = "Enter a name and a six-digit colour."
                    else { vm.updateProfile(profile.copy(name = name.trim(), avatarColorHex = color, usesPrimaryAddons = addons, usesPrimaryPlugins = plugins)); close() }
                }
                MobileSecretField("New four-digit PIN", newPin) { newPin = it.filter(Char::isDigit).take(4) }
                Action("Save PIN") { if (newPin.length == 4) vm.setProfilePin(profile.id, newPin, current.ifBlank { null }) { result ->
                    when (result) {
                        is SetProfilePinResult.Success -> close()
                        is SetProfilePinResult.CurrentPinRequired -> { unlocked = false; message = "Enter the current PIN." }
                        is SetProfilePinResult.Failure -> message = "Could not save the PIN."
                    }
                } }
                if (locked) Action("Remove PIN") { vm.clearProfilePin(profile.id, current) { if (it) close() else message = "Could not remove the PIN." } }
                if (!profile.isPrimary) {
                    Action("Delete profile") { remove = true }
                    if (remove) {
                        Label("Delete " + profile.name + " and its saved settings?")
                        Row { Action("Cancel") { remove = false }; Action("Confirm deletion") { vm.deleteProfile(profile.id); close() } }
                    }
                }
            }
            if (message.isNotEmpty()) Label(message)
        }
    }
}
