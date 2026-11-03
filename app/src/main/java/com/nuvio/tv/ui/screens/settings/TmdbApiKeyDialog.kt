package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun TmdbApiKeyDialog(state: TmdbSettingsUiState, viewModel: TmdbSettingsViewModel, onDismiss: () -> Unit) {
    // Do not put entered keys into rememberSaveable, the public UI state, or navigation arguments.
    var value by remember(state.profileId) { mutableStateOf("") }
    NuvioDialog(onDismiss = { viewModel.cancelApiKeySave(); onDismiss() },
        title = stringResource(R.string.tmdb_local_api_key_title),
        subtitle = stringResource(R.string.tmdb_local_api_key_hint), width = 640.dp) {
        OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            enabled = !state.savingApiKey, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            label = { Text(stringResource(R.string.tmdb_local_api_key_title), color = NuvioTheme.colors.TextSecondary) },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = NuvioTheme.colors.TextPrimary,
                unfocusedTextColor = NuvioTheme.colors.TextPrimary, cursorColor = NuvioTheme.colors.FocusRing,
                focusedBorderColor = NuvioTheme.colors.FocusRing))
        state.apiKeyError?.let { error ->
            Text(stringResource(when (error) {
                TmdbApiKeyError.INVALID -> R.string.tmdb_local_api_key_invalid
                TmdbApiKeyError.NETWORK -> R.string.tmdb_local_api_key_network
                TmdbApiKeyError.RATE_LIMIT -> R.string.tmdb_local_api_key_rate_limit
            }), color = NuvioTheme.colors.TextSecondary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { viewModel.cancelApiKeySave(); onDismiss() }) { Text(stringResource(R.string.action_cancel)) }
            if (state.hasApiKey) Button(onClick = { viewModel.clearApiKey(state.profileId, onDismiss) }, enabled = !state.savingApiKey) {
                Text(stringResource(R.string.action_clear))
            }
            Button(onClick = { viewModel.saveApiKey(value, state.profileId, onDismiss) }, enabled = value.isNotBlank() && !state.savingApiKey) {
                Text(stringResource(if (state.savingApiKey) R.string.action_saving else R.string.action_save))
            }
        }
    }
}
