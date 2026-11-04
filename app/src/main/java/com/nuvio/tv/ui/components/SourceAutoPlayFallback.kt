@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.StreamAutoPlayFallbackProgress
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun SourceAutoPlayFallback(
    progress: StreamAutoPlayFallbackProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (progress.remainingSeconds > 0) stringResource(R.string.autoplay_fallback_countdown, progress.remainingSeconds)
                    else stringResource(R.string.autoplay_fallback_waiting),
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onCancel) { Text(stringResource(R.string.autoplay_fallback_cancel)) }
        }
        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth(), color = NuvioTheme.colors.Primary)
    }
}
