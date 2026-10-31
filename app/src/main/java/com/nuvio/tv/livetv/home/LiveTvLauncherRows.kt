package com.nuvio.tv.livetv.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.navigation.NavController
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.tv.livetv.sports.GameState
import com.nuvio.tv.livetv.sports.SportsCatalog
import com.nuvio.tv.livetv.sports.SportsFeeds
import com.nuvio.tv.livetv.sports.SportsEntryPoint
import com.nuvio.tv.livetv.sports.SportsLaunch
import com.nuvio.tv.livetv.sports.openLiveTvFullscreen
import com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint
import com.nuvio.tv.ui.navigation.Screen
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rows on the launcher's home screen (Google TV, Projectivy, Monet…): Live now in Sports,
 * Favorites and Recently watched channels. Each is switched on in Settings → Live TV; pressing
 * an item opens that channel (or game) in Live TV.
 */
object LiveTvLauncherRows {
    const val ROW_SPORTS = "sports"
    const val ROW_FAVORITES = "favorites"
    const val ROW_RECENT = "recent"

    private const val PREFS = "live_tv_launcher_rows"
    private const val MAX_ITEMS = 24
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)

    fun parse(raw: String): Set<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun join(set: Set<String>): String = set.sorted().joinToString(",")

    private fun title(row: String) = when (row) {
        ROW_SPORTS -> "Live Now in Sports"
        ROW_FAVORITES -> "Favorite Channels"
        else -> "Recently Watched Channels"
    }

    /** Brings the rows up to date (or removes rows that were switched off). Safe to call often. */
    fun refresh(context: Context, afterMs: Long = 0L) {
        val app = context.applicationContext
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (afterMs > 0) kotlinx.coroutines.delay(afterMs)
                runCatching { doRefresh(app) }.onFailure { LiveTvLoadReport.add("Launcher rows: failed: ${it.javaClass.simpleName} ${it.message}") }
            } finally {
                busy.set(false)
            }
        }
    }

    @Volatile private var noTvProviderReported = false

    private suspend fun doRefresh(context: Context) {
        // Phones and tablets (and some boxes) have no home-screen channel service: nothing to do
        // there, said once instead of an error per row every time.
        val hasTvProvider = runCatching {
            context.packageManager.resolveContentProvider("android.media.tv", 0) != null
        }.getOrDefault(false)
        if (!hasTvProvider) {
            if (!noTvProviderReported) {
                noTvProviderReported = true
                LiveTvLoadReport.add("Launcher rows: this device has no home-screen channels (not Android TV / Google TV); skipped")
            }
            return
        }
        val entry = EntryPointAccessors.fromApplication(context, LiveTvStartupEntryPoint::class.java)
        val prefs = entry.liveTvPreferences()
        val settings = prefs.settings.first()
        // The three rows are always on offer: the launcher's own settings decide which to show.
        val wanted = listOf(ROW_SPORTS, ROW_FAVORITES, ROW_RECENT)
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val helper = PreviewChannelHelper(context)
        LauncherImages.tidy(context)
        LiveTvLoadReport.add("Launcher rows: updating")

        val repo = entry.liveTvRepository()
        val byKey: Map<String, LiveChannel> = repo.channels.value.associateBy { it.key }
        if (byKey.isEmpty()) {
            // Channels not loaded yet: make sure the rows exist, but leave what they show alone.
            LiveTvLoadReport.add("Launcher rows: no channels loaded yet, rows made without refreshing")
            for (row in wanted) publish(context, helper, store, row, emptyList(), onlyMake = true)
            return
        }
        val user = prefs.userState.first()
        val programs = repo.programs.value
        val now = System.currentTimeMillis()
        val shift = settings.epgOffsetMinutes * 60_000L

        fun nowOn(ch: LiveChannel): String? =
            programs[ch.key]?.firstOrNull { it.isLive(now - shift) }?.title

        fun visible(ch: LiveChannel) = ch.key !in user.hiddenChannels && ch.groupId !in user.hiddenGroups

        fun channelItem(ch: LiveChannel): Item = Item(
            title = ch.name,
            text = nowOn(ch),
            image = ch.logo,
            label = ch.name.replace(Regex("^[A-Za-z]{2,4}\\s*[:|]\\s*"), "").take(3),
            link = "nuvio://livetv/channel/" + Uri.encode(ch.key),
            id = "c:" + ch.key,
            live = true
        )

        for (row in wanted) {
            val items: List<Item> = when (row) {
                ROW_FAVORITES -> user.favorites.mapNotNull { byKey[it] }.filter(::visible).take(MAX_ITEMS).map(::channelItem)
                ROW_RECENT -> user.recent.mapNotNull { byKey[it] }.filter(::visible).take(MAX_ITEMS).map(::channelItem)
                ROW_SPORTS -> {
                    val sports = EntryPointAccessors.fromApplication(context, SportsEntryPoint::class.java).sportsRepository()
                    val matches = sports.matches.value
                    sports.known.value
                        .filter { it.state == GameState.LIVE && matches[it.id]?.isNotEmpty() == true }
                        .take(MAX_ITEMS)
                        .map { ev ->
                            val score = if (sports.settings.value.hideScores) null
                            else listOfNotNull(ev.away?.score, ev.home?.score).takeIf { it.size == 2 }?.joinToString(" - ")
                            Item(
                                title = ev.title,
                                text = listOfNotNull(score, ev.status).joinToString("  ·  ").ifBlank { "Live now" },
                                // Games: a team's logo. Races, snooker and the like have no teams: the competition's logo.
                                image = ev.home?.team?.logo ?: ev.away?.team?.logo ?: SportsFeeds.leagueLogos[ev.leagueId],
                                label = ev.home?.team?.abbr?.ifBlank { null } ?: ev.home?.team?.name
                                    ?: SportsCatalog.league(ev.leagueId)?.short ?: ev.title,
                                link = "nuvio://livetv/game/" + Uri.encode(ev.id),
                                id = "g:" + ev.id,
                                live = true
                            )
                        }
                }
                else -> emptyList()
            }
            publish(context, helper, store, row, items)
        }
    }

    private class Item(val title: String, val text: String?, val image: String?, val label: String, val link: String, val id: String, val live: Boolean)

    private fun publish(context: Context, helper: PreviewChannelHelper, store: android.content.SharedPreferences, row: String, items: List<Item>, onlyMake: Boolean = false) {
        // This row's channel, found by its name (never trust a saved number alone: asking the
        // system by number failed and made a new copy of the row every time). Extra copies go.
        val named = channelIdsNamed(context, title(row)).sorted()
        var channelId = store.getLong("channel_$row", -1L).takeIf { it in named } ?: named.firstOrNull() ?: -1L
        named.filter { it != channelId }.forEach { runCatching { helper.deletePreviewChannel(it) } }
        if (channelId >= 0) store.edit().putLong("channel_$row", channelId).apply()
        if (named.size > 1) LiveTvLoadReport.add("Launcher rows: removed ${named.size - 1} extra copies of the $row row")
        if (channelId < 0) {
            val channel = PreviewChannel.Builder()
                .setDisplayName(title(row))
                .setAppLinkIntentUri(Uri.parse("nuvio://livetv"))
                .setLogo(appLogo(context))
                .build()
            channelId = runCatching { helper.publishChannel(channel) }
                .onFailure { LiveTvLoadReport.add("Launcher rows: couldn't make the $row row: ${it.javaClass.simpleName} ${it.message}") }
                .getOrDefault(-1L)
            if (channelId < 0) return
            LiveTvLoadReport.add("Launcher rows: made the $row row (id $channelId)")
            store.edit().putLong("channel_$row", channelId).apply()
            // Some launchers show a new row at once; others ask for it in their own row settings.
            runCatching { TvContractCompat.requestChannelBrowsable(context, channelId) }
        }
        if (onlyMake) return
        // Out with the old items, in with the current ones.
        runCatching { context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null) }
        var added = 0
        var firstError: String? = null
        for (item in items) {
            runCatching {
                val b = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                    .setTitle(item.title)
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                    .setIntentUri(Uri.parse(item.link))
                    .setInternalProviderId(item.id)
                    .setLive(item.live)
                item.text?.let { b.setDescription(it) }
                LauncherImages.uriFor(context, item.id, item.image, item.label)?.let { b.setPosterArtUri(it) }
                helper.publishPreviewProgram(b.build())
                added++
            }.onFailure { if (firstError == null) firstError = "${it.javaClass.simpleName} ${it.message}" }
        }
        LiveTvLoadReport.add("Launcher rows: $row now has $added of ${items.size} items" + (firstError?.let { " (first problem: $it)" } ?: ""))
    }

    /** Ids of this app's launcher channels with this name. */
    private fun channelIdsNamed(context: Context, name: String): List<Long> = runCatching {
        val out = ArrayList<Long>()
        context.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(TvContractCompat.Channels._ID, TvContractCompat.Channels.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) out += c.getLong(0)
        }
        out
    }.getOrDefault(emptyList())

    private fun appLogo(context: Context): Bitmap {
        val d = context.packageManager.getApplicationIcon(context.packageName)
        val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, 160, 160)
        d.draw(c)
        return bmp
    }

    /**
     * Opens what a launcher item points at: nuvio://livetv (the guide), nuvio://livetv/channel/<key>
     * (plays that channel full screen) or nuvio://livetv/game/<event id> (the best channel for
     * that game, or its page in Sports if no channel is known yet).
     */
    suspend fun open(context: Context, navController: NavController, url: String) {
        val app = context.applicationContext
        val uri = Uri.parse(url)
        val kind = uri.pathSegments.getOrNull(0)
        val arg = uri.pathSegments.getOrNull(1)
        val entry = EntryPointAccessors.fromApplication(app, LiveTvStartupEntryPoint::class.java)
        val sportsEntry = EntryPointAccessors.fromApplication(app, SportsEntryPoint::class.java)
        if (kind == null || arg == null) {
            navController.navigate(Screen.LiveTv.route) { launchSingleTop = true }
            return
        }
        val repo = entry.liveTvRepository()
        repo.ensureLoaded(updateCheckDelayMs = 20_000)
        // Cold start from the launcher: give the channel list a moment to load.
        withTimeoutOrNull(15_000) { repo.channels.first { it.isNotEmpty() } }
        when (kind) {
            "channel" -> {
                val ch = repo.channels.value.firstOrNull { it.key == arg }
                if (ch == null) {
                    navController.navigate(Screen.LiveTv.route) { launchSingleTop = true }
                } else {
                    sportsEntry.sportsPlayback().play(ch)
                    runCatching { entry.liveTvPreferences().recordWatched(ch.key) }
                    openLiveTvFullscreen(navController)
                }
            }
            "game" -> {
                val sports = sportsEntry.sportsRepository()
                sports.start()
                val m = withTimeoutOrNull(15_000) { sports.matches.first { it[arg]?.isNotEmpty() == true } }?.get(arg)?.firstOrNull()
                if (m != null) {
                    sportsEntry.sportsPlayback().play(m.channel)
                    runCatching { entry.liveTvPreferences().recordWatched(m.channel.key) }
                    openLiveTvFullscreen(navController)
                } else {
                    SportsLaunch.openEvent(arg)
                    navController.navigate(Screen.Sports.route) { launchSingleTop = true }
                }
            }
            else -> navController.navigate(Screen.LiveTv.route) { launchSingleTop = true }
        }
    }
}
