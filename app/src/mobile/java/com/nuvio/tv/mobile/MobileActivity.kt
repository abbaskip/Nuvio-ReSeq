package com.nuvio.tv.mobile

import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.core.storage.DatabaseVersions
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.ThemeSelection
import com.nuvio.tv.livetv.ui.LiveTvViewModel
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.components.DatabaseCompatibilityNotice
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MobileActivity : ComponentActivity() {
    @Inject lateinit var themes: ThemeDataStore
    @Inject lateinit var profileLocks: com.nuvio.tv.data.local.ProfileLockStateDataStore
    @Inject lateinit var profileManager: com.nuvio.tv.core.profile.ProfileManager
    @Inject lateinit var sync: StartupSyncService
    @Inject lateinit var externalPlayer: com.nuvio.tv.core.player.ExternalPlaybackTracker
    @Inject lateinit var projection: com.nuvio.tv.core.tracking.TrackingProgressRefreshCoordinator
    private val externalLauncher = registerForActivityResult(MobileExternalPlayerContract()) { result ->
        if (ready) {
            externalPlayer.onActivityResult(result)
            lifecycleScope.launch { projection.refreshProjection() }
        }
    }
    @Inject lateinit var sports: dagger.Lazy<com.nuvio.tv.livetv.sports.SportsRepository>
    private var ready = false
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DatabaseVersions.blockingIssue(this)?.let {
            DatabaseCompatibilityNotice.show(this, it)
            return
        }
        ready = true
        lifecycleScope.launch {
            var hadPlayer = false
            com.nuvio.tv.core.player.PlayerSessionTracker.isSessionOpen.collectLatest { open ->
                if (open) hadPlayer = true
                else if (hadPlayer) {
                    hadPlayer = false
                    kotlinx.coroutines.delay(750)
                    projection.refreshConnected(TrackingRefreshIntent.INVALIDATED)
                }
            }
        }
        externalPlayer.activityLauncher = externalLauncher
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        PluginRuntimeHooks.onActivityCreate(this)
        setContent {
            val selection by themes.themeSelection.collectAsStateWithLifecycle(ThemeSelection())
            val amoled by themes.amoledMode.collectAsStateWithLifecycle(false)
            val surfaces by themes.amoledSurfacesMode.collectAsStateWithLifecycle(false)
            NuvioTheme(appTheme = selection.theme ?: AppTheme.WHITE, customThemeColors = selection.customColors,
                amoledMode = amoled, amoledSurfacesMode = surfaces) {
                MaterialTheme(colorScheme = mobileColorScheme(NuvioTheme.colors)) {
                    val active by profileManager.activeProfileId.collectAsStateWithLifecycle()
                    val locks by profileLocks.pinEnabled.collectAsStateWithLifecycle(initialValue = null)
                    var unlockedProfile by remember { mutableStateOf<Int?>(null) }
                    key(active) {
                        val locked = locks?.get(active) == true
                        when {
                            locks == null -> CircularProgressIndicator()
                            locked && unlockedProfile != active -> ProfileUnlock(active, unlocked = { unlockedProfile = active })
                            else -> CompositionLocalProvider(LocalSelectedProfile provides { id -> unlockedProfile = id },
                                LocalContentColor provides MaterialTheme.colorScheme.onSurface) { MobileShell(active, sports.get()) }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        val intent = if (firstResume) TrackingRefreshIntent.INVALIDATED else TrackingRefreshIntent.AUTOMATIC
        firstResume = false
        lifecycleScope.launch { projection.refreshConnected(intent) }
    }

    override fun onStart() {
        super.onStart()
        if (!ready) return
        externalPlayer.raiseAutoNextOverlayOnReturn()
        sports.get().setForeground(true)
        sync.requestForegroundSync()
        sync.startPeriodicSurfacePulls()
    }

    override fun onStop() {
        if (ready) {
            externalPlayer.onExternalPlayerCoveredApp()
            sports.get().setForeground(false)
            sync.stopPeriodicSurfacePulls()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (ready && externalPlayer.activityLauncher === externalLauncher) externalPlayer.activityLauncher = null
        if (ready) PluginRuntimeHooks.onActivityDestroy()
        super.onDestroy()
    }
}

internal enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    SEARCH("Search", Icons.Default.Search), LIBRARY("Library", Icons.Default.Bookmarks),
    GUIDE("Live TV", Icons.Default.LiveTv), SPORTS("Sports", Icons.Default.Sports),
    CALENDAR("Calendar", Icons.Default.CalendarMonth), SOURCES("Settings", Icons.Default.Settings)
}

@Composable
private fun MobileShell(profile: Int, sports: com.nuvio.tv.livetv.sports.SportsRepository, live: LiveTvViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var inVodPlayer by remember { mutableStateOf(false) }
    var liveSetup by rememberSaveable { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    var playing by remember { mutableStateOf(false) }
    var multiview by rememberSaveable { mutableStateOf(false) }
    val exitPlayer = { live.playback.stop(); playing = false }
    BackHandler(playing || multiview || tab != Tab.HOME) {
        if (multiview) multiview = false else if (playing) exitPlayer() else tab = Tab.HOME
    }
    val c = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
        val tablet = minOf(maxWidth, maxHeight) >= 600.dp
        val tabWidth = if (tablet) 512.dp else maxWidth
        val select: (Tab) -> Unit = { tab = it }
        val content: @Composable () -> Unit = {
            holder.SaveableStateProvider(tab.name) {
                when (tab) {
                    Tab.HOME, Tab.SEARCH, Tab.LIBRARY -> MobileCatalog(tab, { inVodPlayer = it })
                    Tab.GUIDE, Tab.SPORTS, Tab.CALENDAR -> Column {
                        if (!inVodPlayer) Row(Modifier.fillMaxWidth()) {
                            listOf(Tab.GUIDE, Tab.SPORTS, Tab.CALENDAR).forEach { section ->
                                Action(if (section == Tab.GUIDE) "Guide" else section.label,
                                    Modifier.weight(1f), tab == section) { tab = section }
                            }
                            Action("Multiview", Modifier.weight(1f)) { multiview = true }
                        }
                        when (tab) {
                            Tab.GUIDE -> MobileGuide(live, onTune = { live.preview(it); playing = true }, onReplay = { playing = true },
                                onSetup = { liveSetup = true; tab = Tab.SOURCES })
                            Tab.SPORTS -> MobileSports(onWatch = { live.preview(it); playing = true })
                            else -> MobileCatalog(tab, { inVodPlayer = it }, onWatch = { live.preview(it); playing = true })
                        }
                    }
                    Tab.SOURCES -> MobileSettings(liveSetup, { liveSetup = false })
                }
            }
        }
        if (multiview) MobileMultiview({ multiview = false })
        else if (playing) MobilePlayer(live, exitPlayer)
        else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val tabs: @Composable () -> Unit = {
                Row(Modifier.widthIn(max = tabWidth).fillMaxWidth().background(c.surface)) {
                    listOf(Tab.HOME, Tab.SEARCH, Tab.LIBRARY, Tab.GUIDE, Tab.SOURCES).forEach {
                        NavItem(it, if (tab in listOf(Tab.SPORTS, Tab.CALENDAR)) Tab.GUIDE else tab, Modifier.weight(1f), select)
                    }
                }
            }
            if (tablet && !inVodPlayer) tabs()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CompositionLocalProvider(LocalMobileReplay provides { replay ->
                    val channel = live.channelByKey(replay.channelKey)
                    (channel != null && live.playCatchup(channel, replay.program)).also { if (it) playing = true }
                }) { content() }
            }
            if (!tablet && !inVodPlayer) tabs()
        }
        MobileReminders(profile, sports) { channel ->
            tab = Tab.GUIDE
            multiview = false
            live.preview(channel)
            playing = true
        }
    }
}

@Composable
private fun NavItem(item: Tab, selected: Tab, modifier: Modifier, select: (Tab) -> Unit) {
    val active = selected == item
    val c = MaterialTheme.colorScheme
    Column(modifier.heightIn(min = 56.dp).selectable(active, role = Role.Tab) { select(item) },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(item.icon, null, Modifier.size(21.dp), tint = if (active) c.primary else c.onSurfaceVariant)
        Text(item.label, Modifier.padding(top = 2.dp), color = if (active) c.onSurface else c.onSurfaceVariant,
            fontFamily = condensed, fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = 0.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

internal val condensed = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))

@Composable
internal fun Label(text: String, modifier: Modifier = Modifier, small: Boolean = false, strong: Boolean = false,
    lines: Int = Int.MAX_VALUE) {
    Text(text, modifier, fontFamily = condensed, color = MaterialTheme.colorScheme.onSurface,
        fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, maxLines = lines,
        overflow = TextOverflow.Ellipsis, fontSize = if (small) 12.sp else 15.sp,
        lineHeight = if (small) 16.sp else 20.sp, letterSpacing = 0.sp)
}

@Composable
internal fun SectionTitle(text: String) {
    if (LocalSettingsMenu.current) {
        SettingsHeading(text)
        return
    }
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Spacer(Modifier.size(3.dp, 14.dp).background(colors.primary))
        Text(text, Modifier.weight(1f), fontFamily = condensed, color = colors.onSurface, fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp, lineHeight = 21.sp, letterSpacing = 0.sp)
    }
}

@Composable
internal fun Action(text: String, modifier: Modifier = Modifier, selected: Boolean? = null, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val action = if (selected == null) Modifier.clickable(role = Role.Button, onClick = onClick)
        else Modifier.selectable(selected, role = Role.Tab, onClick = onClick)
    val settings = LocalSettingsMenu.current
    val face = if (settings) {
        Modifier.background(if (selected == true) c.surfaceVariant else c.surface)
            .then(if (selected == true) Modifier.border(1.dp, c.primary) else Modifier)
    } else Modifier.mediaPanel(selected == true)
    Box(modifier.then(face).then(action)
        .heightIn(min = 48.dp).widthIn(min = 48.dp).padding(horizontal = 10.dp),
        contentAlignment = if (settings) Alignment.CenterStart else Alignment.Center) { Label(text, strong = selected == true) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TouchSheet(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, shape = RectangleShape, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), content = content)
}
