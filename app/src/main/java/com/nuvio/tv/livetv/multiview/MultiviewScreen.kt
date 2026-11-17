package com.nuvio.tv.livetv.multiview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.sports.SportsBadge
import com.nuvio.tv.livetv.sports.SportsLook
import com.nuvio.tv.livetv.sports.sportsFrame
import com.nuvio.tv.livetv.ui.ChannelLogo
import com.nuvio.tv.livetv.ui.KeepScreenAwake
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.LiveTextField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext


/** Channels-only M1. Pane selections never enter a saved state or navigation argument. */
@Composable
fun MultiviewScreen(onBack: () -> Unit, viewModel: MultiviewViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val deviceLimit = remember { MultiviewDevicePolicy.read(context) }
    if (deviceLimit == 0) {
        Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(24.dp)) {
            LiveText("This device has no spare video decoder for Multiview.")
            MultiviewChip("Back to guide", onClick = onBack)
        }
        return
    }
    val profileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val openingProfile = remember { profileId }
    val latestOnBack by rememberUpdatedState(onBack)
    val owner = remember {
        MultiviewRecovery.opened(context)
        val limit = deviceLimit
        val layout = (MultiviewLayoutMemory.load(context) ?: MultiviewLayout.TWO)
            .takeIf { it.paneCount <= limit } ?: MultiviewLayout.entries.first { it.paneCount == limit }
        MultiviewLifecycle(
            releaseSingle = viewModel.playback::releaseNow,
            create = { MultiviewPlaybackSession(context, initialLayout = layout, maxPanes = limit,
                mayPlayChannel = { channel -> viewModel.activeProfileId.value == openingProfile &&
                    viewModel.allowedChannels.value.any { it == channel } },
                autoReconnect = { viewModel.autoReconnect.value },
                findBackup = { _, failed, tried -> viewModel.backupFor(failed, tried) },
                startFailure = viewModel::startFailure) },
            foreground = MultiviewPlaybackSession::onForeground,
            background = MultiviewPlaybackSession::onBackground,
            release = MultiviewPlaybackSession::release,
        )
    }
    val session = owner.session
    fun leave() { owner.close(); MultiviewRecovery.left(context); latestOnBack() }
    val state by session.state.collectAsStateWithLifecycle()
    val soundPaused = state.isForeground && state.panes.any { it.channelKey != null } && !state.hasAudioFocus
    val allowedChannels by viewModel.allowedChannels.collectAsStateWithLifecycle()
    val validChannels = allowedChannels
    val pickerGroups by viewModel.pickerGroups.collectAsStateWithLifecycle()
    val panelBackground by viewModel.panelBackground.collectAsStateWithLifecycle()
    val use24h by viewModel.use24HourClock.collectAsStateWithLifecycle()
    val autoBackup by viewModel.autoBackup.collectAsStateWithLifecycle()
    var pickerGroup by remember { mutableStateOf<String?>(null) }
    var editorOpen by remember { mutableStateOf(true) }
    var layoutOpen by remember { mutableStateOf(false) }
    var editedPane by remember { mutableIntStateOf(0) }
    var zoomedPane by remember { mutableStateOf<Int?>(null) }
    val fullPane = zoomedPane?.takeIf { it < state.layout.paneCount }
    val paneFocus = remember { List(4) { FocusRequester() } }
    val editFocus = remember { FocusRequester() }
    val clock by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000L); value = System.currentTimeMillis() }
    }
    val paneKeys = state.panes.mapNotNull { it.channelKey }
    val paneShows by produceState(emptyMap<String, EpgProgram>(), paneKeys, clock / 60_000L) {
        value = viewModel.nowOn(paneKeys)
    }
    LaunchedEffect(profileId) {
        if (profileId != openingProfile) { owner.close(); MultiviewRecovery.left(context); latestOnBack() }
    }
    LaunchedEffect(allowedChannels) { session.validateAllowedChannels(allowedChannels) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(owner, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> owner.onForeground()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> owner.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) owner.onForeground()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); owner.close() }
    }
    KeepScreenAwake(state.isForeground && state.panes.any { it.channelKey != null })
    LaunchedEffect(editorOpen, layoutOpen, state.layout) {
        if (!editorOpen && !layoutOpen) paneFocus[state.focusedPane].requestFocus()
    }
    fun paneHasVideo(id: Int) = state.panes.getOrNull(id)?.let { it.channelKey != null || it.error != null } == true
    fun applyLayout(layout: MultiviewLayout) {
        zoomedPane = null
        session.setLayout(layout)
        MultiviewLayoutMemory.save(context.applicationContext, layout)
        editedPane = editedPane.coerceIn(0, layout.paneCount - 1)
    }
    // The top bar, the pane names and the pane edges all show and fade together: one timer, restarted
    // by the remote. Back hides them at once (overlaysDismissed) instead of waiting for the timer.
    var barTick by remember { mutableIntStateOf(0) }
    var barFocused by remember { mutableStateOf(false) }
    var overlaysShown by remember { mutableStateOf(true) }
    var overlaysDismissed by remember { mutableStateOf(false) }
    val overlaysStay = multiviewOverlaysStay(barFocused, pickerOpen = editorOpen || layoutOpen)
    LaunchedEffect(barTick, overlaysStay, overlaysDismissed) {
        if (overlaysDismissed && !overlaysStay) {
            overlaysShown = false
        } else {
            overlaysShown = true
            if (!overlaysStay) {
                delay(MULTIVIEW_OVERLAY_MS)
                overlaysShown = false
            }
        }
    }
    LaunchedEffect(state.focusedPane) { barTick++ }
    // An arrow that only brought the overlays back: its repeats and release are swallowed too.
    var wakeKey by remember { mutableStateOf<Key?>(null) }
    fun showFullScreen(pane: Int?) {
        zoomedPane = pane
        overlaysDismissed = false
        barTick++
        (pane ?: state.focusedPane).let { paneFocus[it.coerceIn(0, state.layout.paneCount - 1)].requestFocus() }
    }
    // The pane that has just become full screen can only take the highlight once the screen has updated.
    LaunchedEffect(fullPane) { if (!editorOpen && !layoutOpen) fullPane?.let { paneFocus[it].requestFocus() } }
    fun paneClick(paneId: Int) {
        // Selecting the video also recovers sound after audio-focus loss.
        session.focusPane(paneId)
        editedPane = paneId
        editorOpen = true
    }
    val barAlpha by animateFloatAsState(if (overlaysShown) 1f else 0f, tween(450), label = "multiviewBar")
    BackHandler {
        when (multiviewBackAction(editorOpen, overlaysShown, fullScreen = fullPane != null)) {
            MultiviewBack.CLOSE_PICKER -> editorOpen = false
            MultiviewBack.ALL_PANES -> showFullScreen(null)
            MultiviewBack.HIDE_OVERLAYS -> {
                // Leave the bar first, so the highlight is never on a button that has faded away.
                if (barFocused) paneFocus[state.focusedPane].requestFocus()
                overlaysDismissed = true
            }
            MultiviewBack.LEAVE -> leave()
        }
    }

    Box(
        Modifier.fillMaxSize().background(NuvioTheme.colors.Background).multiviewBackdrop().onPreviewKeyEvent { event ->
            val arrow = event.key == Key.DirectionLeft || event.key == Key.DirectionRight ||
                event.key == Key.DirectionUp || event.key == Key.DirectionDown
            // With the overlays hidden, the first arrow only brings them back: it must not also move
            // the highlight (and the sound) to another pane.
            // In full screen Left/Right flip to the next pane straight away: there is no highlight to lose.
            val flips = fullPane != null && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
            val wakeOnly = arrow && !flips && event.type == KeyEventType.KeyDown && !overlaysShown
            if (wakeOnly) wakeKey = event.key
            // Back is left out: it hides the overlays or leaves, and must not bring them back first.
            if (event.type == KeyEventType.KeyDown && event.key != Key.Back) {
                overlaysDismissed = false
                barTick++
            }
            if (event.key == wakeKey) {
                val swallow = wakeOnly || event.type == KeyEventType.KeyUp || event.nativeKeyEvent.repeatCount > 0
                if (event.type == KeyEventType.KeyUp) wakeKey = null
                if (swallow) return@onPreviewKeyEvent true
            }
            if (event.key == Key.Menu) {
                if (event.type == KeyEventType.KeyDown) {
                    editedPane = state.focusedPane
                    editorOpen = true
                }
                true
            } else false
        }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(if (fullPane != null) 0.dp else 6.dp)) {
            val availableWidth = maxWidth
            val availableHeight = maxHeight
            val bounds = multiviewPaneBounds(state.layout)
            state.panes.forEach { pane ->
                key(pane.id) {
                    val isFull = fullPane == pane.id
                    val rect = if (isFull) MultiviewPaneBounds(pane.id, 0f, 0f, 1f, 1f) else bounds.first { it.id == pane.id }
                    // Hold OK: full screen (and back). A short OK still does what it always did.
                    var okDown by remember { mutableStateOf(false) }
                    var okHeld by remember { mutableStateOf(false) }
                    val logo = remember(validChannels, pane.channelKey) {
                        pane.channelKey?.let { k -> validChannels.firstOrNull { it.key == k }?.logo }
                    }
                    MultiviewPane(
                        pane = pane,
                        overlaysShown = overlaysShown,
                        fullScreen = isFull,
                        // Two protected pictures cannot be stacked reliably, so the covered one is put away.
                        hideVideo = fullPane != null && !isFull && state.panes.getOrNull(fullPane)?.isProtected == true,
                        hint = when {
                            isFull -> "Back: all panes"
                            pane.channelKey != null || pane.error != null -> "Hold OK: full screen"
                            else -> null
                        },
                        audible = pane.id == state.focusedPane && state.hasAudioFocus,
                        logo = logo,
                        show = pane.channelKey?.let { paneShows[it] },
                        now = clock,
                        use24h = use24h,
                        errorHint = if (pane.error?.startsWith("Your provider allows") == true)
                            "Choose a copy from another playlist or try Backup" else null,
                        emptyHint = "OK to pick a channel",
                        modifier = Modifier.offset(availableWidth * rect.left, availableHeight * rect.top)
                            .width(availableWidth * (rect.right - rect.left))
                            .height(availableHeight * (rect.bottom - rect.top)).padding(if (isFull) 0.dp else 4.dp)
                            .zIndex(if (isFull) 1f else 0f)
                            .focusRequester(paneFocus[pane.id])
                            .focusProperties { canFocus = !editorOpen && !layoutOpen && (fullPane == null || isFull) }
                            .onFocusChanged { if (it.isFocused && !editorOpen && !layoutOpen) session.focusPane(pane.id) }
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                    val native = event.nativeKeyEvent
                                    if (event.type == KeyEventType.KeyDown) {
                                        if (native.repeatCount == 0) { okDown = true; okHeld = false }
                                        else if (okDown && !okHeld && (isFull || paneHasVideo(pane.id))) {
                                            okHeld = true
                                            showFullScreen(if (isFull) null else pane.id)
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                    if (event.type == KeyEventType.KeyUp) {
                                        val click = okDown && !okHeld
                                        okDown = false
                                        okHeld = false
                                        if (click) paneClick(pane.id)
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                if (isFull && event.key in setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)) {
                                    if (event.type == KeyEventType.KeyDown) when (event.key) {
                                        Key.DirectionUp -> editFocus.requestFocus()
                                        Key.DirectionDown -> { editedPane = pane.id; editorOpen = true }
                                        else -> {
                                            val next = multiviewNextFullPane(
                                                state.panes.filter { it.channelKey != null || it.error != null }.map { it.id },
                                                pane.id, forward = event.key == Key.DirectionRight)
                                            if (next != pane.id) showFullScreen(next)
                                        }
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                val direction = when (event.key) {
                                    Key.DirectionLeft -> MultiviewDirection.LEFT
                                    Key.DirectionRight -> MultiviewDirection.RIGHT
                                    Key.DirectionUp -> MultiviewDirection.UP
                                    Key.DirectionDown -> MultiviewDirection.DOWN
                                    else -> null
                                }
                                if (direction == null) false else {
                                    if (event.type == KeyEventType.KeyDown) {
                                        val next = multiviewNextPane(state.layout, pane.id, direction)
                                        if (next == pane.id && direction == MultiviewDirection.UP) editFocus.requestFocus()
                                        else if (next == pane.id && direction == MultiviewDirection.DOWN) {
                                            editedPane = pane.id
                                            editorOpen = true
                                        } else paneFocus[next].requestFocus()
                                    }
                                    true
                                }
                            },
                        onClick = { paneClick(pane.id) }
                    )
                }
            }
        }
        // The control bar floating over the video: the play bar's glass, Sports' buttons.
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                .graphicsLayer { alpha = barAlpha }
                .onFocusChanged { barFocused = it.hasFocus }
                .multiviewPanel(panelBackground, RoundedCornerShape(14.dp))
                .padding(start = 14.dp, end = 8.dp, top = 5.dp, bottom = 5.dp)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionDown) {
                        if (event.type == KeyEventType.KeyDown) paneFocus[state.focusedPane].requestFocus()
                        true
                    } else false
                },
            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.padding(end = 4.dp)) {
                LiveText("LIVE TV", size = 9.sp, color = NuvioTheme.colors.TextSecondary)
                LiveText("Multiview", size = 13.sp, weight = FontWeight.ExtraBold)
            }
            MultiviewLayoutButton(multiviewLayoutTitle(state.layout), state.layout) { layoutOpen = true }
            MultiviewBarDivider()
            MultiviewChip("Streams", Modifier.focusRequester(editFocus)) {
                editedPane = state.focusedPane
                editorOpen = true
            }
            MultiviewChip(if (fullPane != null) "All panes" else "Full screen",
                enabled = fullPane != null || paneHasVideo(state.focusedPane)) {
                showFullScreen(if (fullPane != null) null else state.focusedPane)
            }
            MultiviewChip("Stop playback", onClick = session::stopAll)
            MultiviewChip("Exit", onClick = ::leave)
            MultiviewBarDivider()
            LiveText(if (soundPaused) "Paused" else "Sound: P${state.focusedPane + 1}",
                size = 11.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(end = 6.dp))
        }
        if (soundPaused) {
            LiveText("Playback paused. Select a pane to retry sound.", size = 12.sp,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                    .background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
    if (editorOpen) {
        val pane = editedPane.coerceIn(0, state.layout.paneCount - 1)
        MultiviewChannelPicker(state = state, paneId = pane,
            dockRight = multiviewPaneBounds(state.layout).first { it.id == pane }.let { (it.left + it.right) / 2f <= .5f },
            optionsSource = allowedChannels, groups = pickerGroups, channelsIn = viewModel::channelsIn,
            group = pickerGroup, onGroup = { pickerGroup = it }, panelBackground = panelBackground,
            nowOn = viewModel::nowOn,
            onSelect = { option ->
                if (viewModel.activeProfileId.value == openingProfile) {
                    viewModel.allowedChannels.value.firstOrNull { it.key == option.key }?.let { session.assignChannel(pane, it) }
                }
                editorOpen = false
            },
            onClear = { session.clearPane(pane) }, onRetry = { session.retryPane(pane) },
            onBackup = if (autoBackup) ({ session.tryBackup(pane); editorOpen = false }) else null,
            onClose = { editorOpen = false })
    }
    if (layoutOpen) MultiviewLayoutPicker(current = state.layout, maxPanes = session.maxPanes,
        panelBackground = panelBackground, onPick = { applyLayout(it) }, onClose = { layoutOpen = false })
}

@Composable
private fun MultiviewBarDivider() {
    Box(Modifier.width(1.dp).height(18.dp).background(SportsLook.Divider))
}
