package com.nuvio.tv.mobile

import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
    @Inject lateinit var sync: StartupSyncService
    @Inject lateinit var sports: dagger.Lazy<com.nuvio.tv.livetv.sports.SportsRepository>
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DatabaseVersions.blockingIssue(this)?.let {
            DatabaseCompatibilityNotice.show(this, it)
            return
        }
        ready = true
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        PluginRuntimeHooks.onActivityCreate(this)
        setContent {
            val selection by themes.themeSelection.collectAsStateWithLifecycle(ThemeSelection())
            val amoled by themes.amoledMode.collectAsStateWithLifecycle(false)
            val surfaces by themes.amoledSurfacesMode.collectAsStateWithLifecycle(false)
            NuvioTheme(appTheme = selection.theme ?: AppTheme.WHITE, customThemeColors = selection.customColors,
                amoledMode = amoled, amoledSurfacesMode = surfaces) {
                val c = NuvioTheme.colors
                MaterialTheme(colorScheme = darkColorScheme(primary = c.Secondary, onPrimary = c.OnSecondary,
                    background = c.TintedBackground, surface = c.TintedPanel, surfaceVariant = c.TintedControl,
                    onSurface = c.TextPrimary, onSurfaceVariant = c.TextSecondary, onBackground = c.TextPrimary)) {
                    MobileShell()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!ready) return
        sports.get().setForeground(true)
        sync.requestForegroundSync()
        sync.startPeriodicSurfacePulls()
    }

    override fun onStop() {
        if (ready) {
            sports.get().setForeground(false)
            sync.stopPeriodicSurfacePulls()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (ready) PluginRuntimeHooks.onActivityDestroy()
        super.onDestroy()
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    GUIDE("Guide", Icons.Default.LiveTv), DISCOVER("Discover", Icons.Default.Explore), SPORTS("Sports", Icons.Default.Sports),
    CALENDAR("Calendar", Icons.Default.CalendarMonth), SOURCES("Setup", Icons.Default.Settings)
}

@Composable
private fun MobileShell(live: LiveTvViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.GUIDE) }
    val holder = rememberSaveableStateHolder()
    var playing by remember { mutableStateOf(false) }
    val exitPlayer = { live.playback.stop(); playing = false }
    BackHandler(playing || tab != Tab.GUIDE) {
        if (playing) exitPlayer() else tab = Tab.GUIDE
    }
    val c = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()) {
        val tablet = minOf(maxWidth, maxHeight) >= 600.dp
        val tabWidth = if (tablet) 440.dp else maxWidth
        val select: (Tab) -> Unit = { tab = it }
        val content: @Composable () -> Unit = {
            holder.SaveableStateProvider(tab.name) {
                when (tab) {
                    Tab.GUIDE -> MobileGuide(live, onTune = { live.preview(it); playing = true },
                        onSetup = { tab = Tab.SOURCES })
                    Tab.SPORTS -> MobileSports(onWatch = { live.preview(it); playing = true })
                    Tab.CALENDAR -> MobileCalendar(onWatch = { live.preview(it); playing = true })
                    Tab.DISCOVER -> MobileDiscover()
                    Tab.SOURCES -> MobileSetup()
                }
            }
        }
        if (playing) MobilePlayer(live, exitPlayer)
        else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val tabs: @Composable () -> Unit = {
                Row(Modifier.widthIn(max = tabWidth).fillMaxWidth().background(c.surface)) {
                    Tab.entries.forEach { NavItem(it, tab, Modifier.weight(1f), select) }
                }
            }
            if (tablet) tabs()
            Box(Modifier.fillMaxWidth().weight(1f)) { content() }
            if (!tablet) tabs()
        }
    }
}

@Composable
private fun NavItem(item: Tab, selected: Tab, modifier: Modifier, select: (Tab) -> Unit) {
    val active = selected == item
    val c = MaterialTheme.colorScheme
    Column(modifier.heightIn(min = 56.dp).selectable(active, role = Role.Tab) { select(item) },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(item.icon, null, tint = if (active) c.primary else c.onSurfaceVariant)
        Label(item.label, small = true, strong = active)
    }
}

private val condensed = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))

@Composable
internal fun Label(text: String, modifier: Modifier = Modifier, small: Boolean = false, strong: Boolean = false,
    lines: Int = Int.MAX_VALUE) {
    Text(text, modifier, fontFamily = condensed, color = MaterialTheme.colorScheme.onSurface,
        fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, maxLines = lines,
        overflow = TextOverflow.Ellipsis, fontSize = if (small) 14.sp else 17.sp,
        lineHeight = if (small) 18.sp else 22.sp)
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontFamily = condensed,
        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 24.sp)
}

@Composable
internal fun Action(text: String, modifier: Modifier = Modifier, selected: Boolean? = null, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val action = if (selected == null) Modifier.clickable(role = Role.Button, onClick = onClick)
        else Modifier.selectable(selected, role = Role.Tab, onClick = onClick)
    Box(modifier.background(if (selected == true) c.surfaceVariant else c.surface).then(action)
        .heightIn(min = 48.dp).widthIn(min = 48.dp).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center) { Label(text, strong = selected == true) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TouchSheet(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, shape = RectangleShape, containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), content = content)
}
