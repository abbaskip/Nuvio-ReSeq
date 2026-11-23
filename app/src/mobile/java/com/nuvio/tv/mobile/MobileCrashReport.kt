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
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject
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

/** Capture class names and frame locations only. The regular Android handler still performs the crash shutdown. */
internal object MobileCrashTrace {
    private var installed = false
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "mobile-crash.json"))

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }
                    .take(5).joinToString("\nCaused by ") { cause ->
                        buildString {
                            appendLine(cause.javaClass.name)
                            cause.stackTrace.take(15).forEach { frame -> appendLine("    at $frame") }
                        }.trimEnd()
                    }
                val document = JSONObject().put("time", System.currentTimeMillis()).put("pid", android.os.Process.myPid())
                    .put("version", BuildConfig.VERSION_NAME).put("trace", trace)
                val target = file(context)
                val output = target.startWrite()
                try {
                    output.write(document.toString().toByteArray(Charsets.UTF_8))
                    target.finishWrite(output)
                } catch (failure: Throwable) {
                    target.failWrite(output)
                    throw failure
                }
            } finally {
                previous.uncaughtException(thread, error)
            }
        }
        installed = true
    }

    fun read(context: Context): JSONObject? = runCatching {
        JSONObject(file(context).openRead().bufferedReader().use { it.readText() })
    }.getOrNull()
}

private fun lastMobileCrash(context: Context): String {
    val saved = MobileCrashTrace.read(context)
    val exit = if (Build.VERSION.SDK_INT >= 30) runCatching {
        context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 32)
            .firstOrNull { it.processName == context.packageName && it.reason in setOf(
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY)
            }
    }.getOrNull() else null
    // A low-memory termination has no Java exception. Never append a trace from a different launch.
    val useSaved = saved != null && (exit == null || saved.optLong("time") > exit.timestamp ||
        (exit.reason == ApplicationExitInfo.REASON_CRASH && saved.optInt("pid") == exit.pid &&
            kotlin.math.abs(exit.timestamp - saved.optLong("time")) < 60_000))
    val timestamp = if (useSaved) saved?.optLong("time") else exit?.timestamp
    val reason = when {
        useSaved -> "Application exception in ReSeq Mobile ${saved?.optString("version")}"
        exit == null -> "No crash details available on this device."
        exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (signal ${exit.status})"
        exit.reason == ApplicationExitInfo.REASON_ANR -> "Application stopped responding"
        exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed the process to free memory"
        else -> "Application exception"
    }
    return listOfNotNull("ReSeq Mobile ${BuildConfig.VERSION_NAME} (installed)",
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
        timestamp?.let { DateFormat.getDateTimeInstance().format(Date(it)) + " · " }.orEmpty() + reason,
        if (useSaved) saved?.optString("trace") else null).joinToString("\n")
}
