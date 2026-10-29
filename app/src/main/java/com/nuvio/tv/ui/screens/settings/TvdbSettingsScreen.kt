@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.NuvioDialog

@Composable
fun TvdbSettingsContent(
    viewModel: TvdbSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(title = "TheTVDB", subtitle = "Use your own API key for native episode ordering. Settings apply to this profile.")
        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "key") {
                SettingsActionRow(
                    title = if (state.configured) "Replace API key" else "Enter API key",
                    subtitle = "Stored locally using Android Keystore encryption",
                    value = if (state.configured) "Configured" else "Not configured",
                    enabled = !state.busy, onClick = { editing = true },
                    modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier
                )
                }
                item(key = "test") {
                SettingsActionRow(title = "Test connection", subtitle = state.message ?: "Verify authentication with TVDB",
                    enabled = state.configured && !state.busy, onClick = viewModel::test)
                }
                item(key = "remove") {
                SettingsActionRow(title = "Remove API key", subtitle = "Keeps your saved episode-order selections",
                    enabled = state.configured && !state.busy, onClick = { viewModel.save(null) })
                }
                if (state.busy) item(key = "busy") { Text("Connecting…") }
                state.message?.let { message -> item(key = "status") { Text(message) } }
                item(key = "attribution") {
                SettingsActionRow(title = "Metadata provided by TheTVDB", subtitle = "TheTVDB.com — API access and attribution",
                    onClick = { uriHandler.openUri("https://thetvdb.com/api-information") })
                }
            }
            SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }
    if (editing) {
        // Do not put secrets in saved-instance state or prefill the existing key.
        var value by remember { mutableStateOf("") }
        val inputFocus = remember { FocusRequester() }
        NuvioDialog(onDismiss = { editing = false }, title = "TVDB API key", subtitle = "Enter a personal TVDB v4 API key", width = 700.dp) {
            BasicTextField(value = value, onValueChange = { value = it }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(inputFocus))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { value = ""; editing = false }) { Text("Cancel") }
                Button(enabled = value.isNotBlank(), onClick = { viewModel.save(value); value = ""; editing = false }) { Text("Save") }
            }
            LaunchedEffect(Unit) { inputFocus.requestFocus() }
        }
    }
}
