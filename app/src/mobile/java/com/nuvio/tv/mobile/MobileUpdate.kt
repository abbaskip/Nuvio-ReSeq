package com.nuvio.tv.mobile

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.updater.ApkInstaller
import com.nuvio.tv.updater.UpdateViewModel
import kotlin.math.roundToInt

@Composable
internal fun MobileUpdateAction(vm: UpdateViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    Action(if (state.isChecking) "Checking for updates…" else "Check for updates") {
        if (!state.isChecking && !state.isDownloading) vm.checkForUpdates(force = true, showNoUpdateFeedback = true)
    }
    state.feedbackMessage?.let { Label(it) }
}

@Composable
internal fun MobileUpdatePrompt(vm: UpdateViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val update = state.update ?: return
    if (!state.showBanner) return
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (ApkInstaller.canRequestPackageInstalls(context)) vm.installUpdateOrRequestPermission()
    }
    val version = update.tag.removePrefix("v")
    val progress = state.downloadProgress?.let { " ${(it * 100).roundToInt()}%" }.orEmpty()
    AlertDialog(
        onDismissRequest = { if (!state.isDownloading) vm.dismissBanner() },
        title = { Text("ReSeq Mobile $version") },
        text = {
            Text(when {
                state.showUnknownSourcesDialog -> "Allow ReSeq Mobile to install apps from this source, then return to finish the update."
                state.errorMessage != null -> state.errorMessage.orEmpty()
                state.isDownloading -> "Downloading update$progress"
                state.downloadedApkPath != null -> "The update is ready to install."
                else -> "A mobile update is available."
            })
        },
        confirmButton = {
            if (state.showUnknownSourcesDialog) {
                TextButton(onClick = {
                    val intent = ApkInstaller.buildUnknownSourcesSettingsIntent(context)
                    if (intent != null) {
                        intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { permission.launch(intent) }.onFailure { vm.openUnknownSourcesSettings() }
                    }
                }) { Text("Allow installation") }
            } else if (!state.isDownloading) {
                TextButton(onClick = {
                    if (state.downloadedApkPath != null) vm.installUpdateOrRequestPermission() else vm.downloadUpdate()
                }) { Text(if (state.downloadedApkPath != null) "Install update" else "Download update") }
            }
        },
        dismissButton = { if (!state.isDownloading) TextButton(onClick = vm::dismissBanner) { Text("Later") } }
    )
}
