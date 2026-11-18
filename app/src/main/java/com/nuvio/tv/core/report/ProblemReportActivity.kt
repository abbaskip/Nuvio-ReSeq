package com.nuvio.tv.core.report

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.livetv.crash.CrashCatcher
import com.nuvio.tv.livetv.crash.ReportServer
import com.nuvio.tv.livetv.data.IptvStorageReport
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

@EntryPoint @InstallIn(SingletonComponent::class)
interface ReportServices {
    fun trackers(): com.nuvio.tv.core.tracking.TrackingProviderRegistry
    fun auth(): com.nuvio.tv.core.auth.AuthManager
    fun liveTv(): com.nuvio.tv.livetv.data.LiveTvPreferences
    fun tmdb(): com.nuvio.tv.data.local.TmdbSettingsDataStore
    fun playerSettings(): com.nuvio.tv.data.local.PlayerSettingsDataStore
    fun profiles(): com.nuvio.tv.core.profile.ProfileManager
    fun tvdb(): com.nuvio.tv.data.tvdb.TvdbCredentialStore
}

/** No upload endpoint. Only the cleaned, explicitly approved snapshot is served on the LAN. */
class ProblemReportActivity : ComponentActivity() {
    private var server: ReportServer.Handle? = null
    private var cleaned: String? = null
    private var loading = false
    private val subject get() = ReportRedactor.clean("Nuvio ReSeq report ${BuildConfig.VERSION_NAME} ${Build.MODEL}")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consent()
    }
    override fun onStart() { super.onStart(); if (cleaned != null) share() }
    override fun onStop() { server?.stop(); server = null; super.onStop() }
    override fun onDestroy() { server?.stop(); super.onDestroy() }

    private fun label(value: String, size: Float = 16f) = TextView(this).apply {
        text = value; setTextColor(Color.WHITE); textSize = size; setPadding(0, 8, 0, 8)
    }
    private fun root(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(48, 28, 48, 24); setBackgroundColor(Color.rgb(14,14,18))
        addView(label(title, 24f))
    }
    private fun buttons(root: LinearLayout, continueAction: (() -> Unit)? = null) {
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            if (continueAction != null) addView(Button(this@ProblemReportActivity).apply {
                text = "Continue"; setOnClickListener { continueAction() }; requestFocus()
            })
            addView(Button(this@ProblemReportActivity).apply {
                text = if (continueAction == null) "Close" else "Cancel"; setOnClickListener { finish() }
            })
        })
    }
    private fun consent() {
        val root = root("Send a report to the developer")
        val contents = label("This report includes:\n" +
            "• App version, device/chipset, Android, memory limits, free storage and display/HDR mode\n" +
            "• Saved crash, freeze and exit reasons and exception stacks (free-text messages withheld)\n" +
            "• Recent warning/error entries and technical events (at most 500 KB; unlabelled messages withheld)\n" +
            "• IPTV storage sizes, enabled features and connected service names only\n" +
            "• The last five technical playback sessions: engine, format, decoder, dimensions/crop, surface, picture settings, audio, errors and buffering\n" +
            (if (intent.hasExtra(CURRENT)) "• The current video's technical details, frozen when you selected Report\n" else "") +
            "\nNo account names, watch history, passwords, tokens, full URLs, email addresses or IP addresses. " +
            "Nothing is sent automatically. You can review the cleaned report before sharing.")
        root.addView(ScrollView(this).apply { isFocusable = true; addView(contents) }, LinearLayout.LayoutParams(-1, 0, 1f))
        buttons(root) { collect() }
        setContentView(root)
    }
    private fun collect() {
        if (loading) return
        loading = true
        setContentView(root("Preparing cleaned report…").also { buttons(it) })
        lifecycleScope.launch {
            cleaned = withContext(Dispatchers.IO) {
                runCatching { buildReport() }.getOrElse { "Report unavailable: ${it.javaClass.simpleName}. No data was shared." }
            }
            loading = false
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) share()
        }
    }
    private suspend fun buildReport(): String {
        val am = getSystemService(ActivityManager::class.java)
        val display = getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val services = EntryPointAccessors.fromApplication(applicationContext, ReportServices::class.java)
        val live = services.liveTv().settings.first()
        val sources = services.liveTv().playlists.first()
        val connected = services.trackers().connectedProviderIds().map { it.name }.toMutableList()
        if (services.auth().isAuthenticated) connected += "Nuvio Sync"
        val features = mutableListOf("Continue Watching", "Multiview")
        if (services.tmdb().settings.value.enabled) { features += "TMDB metadata"; connected += "TMDB" }
        // Boolean feature flags only. Never serialize settings, source names or credentials.
        if (live.homeRowEnabled) features += "Live TV Home row"
        if (live.timeshiftEnabled) features += "Live TV timeshift"
        if (live.updateGuideDuringPlayback) features += "Guide updates during playback"
        if (sources.any { it.liveEnabled }) { features += "Live TV"; connected += "IPTV" }
        if (sources.any { it.enabled && it.importVod }) features += "On Demand"
        if (services.liveTv().sportsSettingsJson.first() != null) features += "Sports Hub"
        if (!services.tvdb().read(services.profiles().activeProfileId.value).isNullOrBlank()) { features += "TVDB ReSeq"; connected += "TVDB" }
        val playerSettings = services.playerSettings().playerSettings.first()
        if (playerSettings.useLibass) features += "libass subtitles"
        if (playerSettings.tunnelingEnabled) features += "Tunneled playback"
        val current = intent.getStringExtra(CURRENT)?.let { runCatching { Gson().fromJson(it, PlaybackReport::class.java) }.getOrNull() }
        fun describe(session: PlaybackReport): PlaybackReport {
            val source = sources.firstOrNull { it.id == session.sourceId }
            val details = if (source == null) mapOf("provider type" to "Direct/addon or unavailable") else mapOf(
                "provider type" to when { source.isStalker -> "Stalker"; source.isXtream -> "Xtream"; else -> "M3U" },
                "provider host" to ReportRedactor.host(when { source.isStalker -> source.stalkerPortal; source.isXtream -> source.xtreamServer; else -> source.url }))
            return session.copy(fields = session.fields + details, sourceId = null)
        }
        return ProblemReportBuilder.build(ProblemReportInput(BuildConfig.VERSION_NAME, Build.MODEL,
            linkedMapOf("versionCode" to BuildConfig.VERSION_CODE.toString(), "hardware" to Build.HARDWARE,
                "SoC" to if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "not reported by Android",
                "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "memoryClass" to "${am.memoryClass} MB", "largeMemoryClass" to "${am.largeMemoryClass} MB",
                "maxMemory" to Runtime.getRuntime().maxMemory().toString(), "free storage" to "${filesDir.usableSpace} bytes",
                "display mode" to (mode?.let { "${it.physicalWidth}x${it.physicalHeight} @ ${it.refreshRate} Hz" } ?: "unavailable"),
                "HDR types" to (display?.hdrCapabilities?.supportedHdrTypes?.joinToString() ?: "unavailable")),
            features, connected, IptvStorageReport.scan(this).readable(), CrashCatcher.lastReport(this),
            ReportLog.read(), PlaybackReports.recent().map(::describe), current?.let(::describe)))
    }
    private fun share() {
        val report = cleaned ?: return
        if (server == null) server = ReportServer.start("Nuvio ReSeq report",
            ReportServer.Contact(SUPPORT, subject)) { report }
        val root = root("Review your report")
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val address = server?.url
        row.addView(label(if (address == null) "No local network available. Review the summary below or photograph it."
            else "Open on a phone on the same network:\n$address\nReview, then choose Email to developer or Download. This page stops when you leave."),
            LinearLayout.LayoutParams(0, -2, 1f))
        if (address != null) runCatching { QrCodeGenerator.generate(address, 220) }.getOrNull()?.let { bitmap ->
            row.addView(ImageView(this).apply { setImageBitmap(bitmap); setBackgroundColor(Color.WHITE) }, LinearLayout.LayoutParams(220,220))
        }
        root.addView(row)
        root.addView(ScrollView(this).apply { isFocusable = true; addView(label(report, 12f)) }, LinearLayout.LayoutParams(-1,0,1f))
        buttons(root)
        setContentView(root)
    }
    companion object {
        const val SUPPORT = "abbaskip_e@hotmail.com"
        private const val CURRENT = "clean-playback-snapshot"
        fun open(context: Context, owner: Any? = null) {
            val snapshot = PlaybackReports.current(owner)
            context.startActivity(Intent(context, ProblemReportActivity::class.java).apply {
                snapshot?.let { putExtra(CURRENT, Gson().toJson(it)) }
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
