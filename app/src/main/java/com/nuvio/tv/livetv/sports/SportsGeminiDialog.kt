package com.nuvio.tv.livetv.sports

import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.LiveTextField
import kotlinx.coroutines.launch

@Composable
internal fun SportsGeminiKeyDialog(client: SportsGemini, onClose: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(client.configured()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SportsDialog(onDismiss = onClose, width = 760.dp) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveText("Gemini sports assistance (optional)", size = 22.sp)
            LiveText("Uses your Google API allowance for manual sourced answers and optional background guide matching. Availability and free quota vary; a billed project may incur charges. Answers may be delayed or incomplete.", size = 13.sp, maxLines = 3)
            LiveText(if (configured) "A key is saved for this profile." else "No key saved for this profile.", size = 13.sp)
            LiveTextField(key, { key = it }, "Gemini API key", password = true)
            error?.let { LiveText(it, size = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SportsPill("Save", onClick = { if (key.isNotBlank()) scope.launch {
                    runCatching { client.save(key) }.onSuccess { key = ""; configured = true; onClose() }
                        .onFailure { error = "Unable to save the key on this device." }
                } })
                SportsPill("Remove key", onClick = { scope.launch { runCatching { client.save(null) }.onSuccess { configured = false; key = "" }.onFailure { error = "Unable to remove the key on this device." } } })
                SportsPill("Done", onClick = onClose)
            }
        }
    }
}

@Composable
internal fun SportsGeminiLookupDialog(client: SportsGemini, event: SportsEvent, region: String, onClose: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var html by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    val uri = LocalUriHandler.current
    LaunchedEffect(event.id, revision) {
        busy = true; error = null; html = null
        runCatching { client.lookup(event, region) }.onSuccess { html = it }
            .onFailure { error = it.message?.take(200) ?: "Lookup failed." }
        busy = false
    }
    SportsDialog(onDismiss = onClose, width = 1000.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveText("Gemini source lookup", size = 22.sp)
            LiveText("An informational answer; it does not replace confirmed scores or choose your stream.", size = 12.sp)
            if (busy) LiveText("Checking sources…", size = 15.sp)
            error?.let { LiveText(it, size = 14.sp) }
            html?.let { content ->
                AndroidView(modifier = Modifier.fillMaxWidth().height(390.dp), factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.domStorageEnabled = false
                        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.isForMainFrame && request.hasGesture() && request.url.scheme == "https")
                                    runCatching { uri.openUri(request.url.toString()) }
                                return true
                            }
                        }
                        loadDataWithBaseURL("https://localhost.invalid/", content, "text/html", "UTF-8", null)
                    }
                }, onRelease = { it.stopLoading(); it.destroy() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SportsPill("Close", onClick = onClose)
                SportsPill("Refresh", onClick = { if (!busy) revision++ })
            }
        }
    }
}
