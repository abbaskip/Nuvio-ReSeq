package com.nuvio.tv.mobile

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.report.ReportRedactor
import com.nuvio.tv.livetv.crash.CrashCatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun MobileCrashReport() {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsLink("Last crash report") { open = true }
    if (!open) return
    val context = LocalContext.current
    val report by produceState<String?>(null) { value = withContext(Dispatchers.IO) { lastMobileCrash(context) } }
    TouchSheet({ open = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowseHeading("Last crash report") { open = false }
            Label(report ?: "Reading the device report…", small = true)
            report?.let { text -> Action("Copy report") {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ReSeq crash", text))
            } }
        }
    }
}

private fun lastMobileCrash(context: Context): String {
    val exit = if (Build.VERSION.SDK_INT >= 30) runCatching {
        context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 32)
            .firstNotNullOfOrNull { item ->
                val reason = when (item.reason) {
                    ApplicationExitInfo.REASON_CRASH -> "Application exception"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (signal ${item.status})"
                    ApplicationExitInfo.REASON_ANR -> "Application stopped responding"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed the process to free memory"
                    else -> null
                }
                reason?.takeIf { item.processName == context.packageName }?.let {
                    DateFormat.getDateTimeInstance().format(Date(item.timestamp)) + " · " + it
                }
            }
    }.getOrNull() else null
    // Android exposes this application's own crash buffer. Keep only exception types and stack locations.
    val trace = runCatching {
        val log = ProcessBuilder("logcat", "-b", "crash", "-d", "-t", "300", "-v", "raw",
            "--uid=" + android.os.Process.myUid()).redirectErrorStream(true).start()
        try {
            val raw = log.inputStream.bufferedReader().use { it.readText() }.substringAfterLast("FATAL EXCEPTION:")
            ReportRedactor.crash(raw).takeUnless { it == "No saved exception stack or exit reason." }
        } finally { log.destroy() }
    }.getOrNull() ?: CrashCatcher.lastReport(context)?.let(ReportRedactor::crash)
    return listOfNotNull("ReSeq Mobile ${BuildConfig.VERSION_NAME} (installed)",
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
        exit, trace, if (exit == null && trace == null) "No crash details available on this device." else null).joinToString("\n")
}
