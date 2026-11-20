package com.nuvio.tv.livetv.calendar

import android.content.Context
import android.os.CancellationSignal
import com.nuvio.tv.core.storage.DatabaseVersions
import com.nuvio.tv.livetv.data.BackgroundWork
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.multiview.liveTvChannelVisibility
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.sports.SportsFollows
import com.nuvio.tv.livetv.sports.SportsPageSnapshot
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

internal class CalendarLocalListings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: Lazy<LiveTvPreferences>,
    private val channels: Lazy<LiveTvChannelStore>,
    private val guide: Lazy<EpgDatabase>
) {
    suspend fun sports(window: CalendarWindow, now: Long, zone: ZoneId): List<CalendarItem> {
        val follows = SportsFollows.fromJson(prefs.get().sportsMineJson.first())
        if (follows.teams.isEmpty()) return emptyList()
        // Do not instantiate or start SportsRepository from this screen.
        val file = File(context.cacheDir, "sports/page-snapshot.json")
        val page = SportsPageSnapshot.read(file, now) ?: return emptyList()
        return page.events.asSequence().filter { event -> event.sides.any { follows.followsTeam(it.team) } }
            .mapNotNull { event ->
                val day = event.startMs.calendarDay(zone)
                if (day !in window) null else CalendarItem("sport/" + event.id, "sports", event.title.take(240),
                    day, CalendarKind.SPORT, time = event.startMs.takeIf { event.hasTime }, contentId = event.id)
            }.toList()
    }

    /** Only resolve the channels behind actual hits. Recheck saved hits against current profile locks. */
    fun allowedNames(keys: Set<String>): Flow<Map<String, String>> {
        val preferences = prefs.get()
        return combine(channels.get().displayChannels, preferences.userState, preferences.settings, ParentalControls.unlocked) {
            all, user, settings, _ ->
            val hits = all.filter { it.key in keys }
            liveTvChannelVisibility(hits, user, settings).visible.associate { it.key to it.name }
        }.distinctUntilChanged()
    }

    suspend fun guide(page: CalendarPage, titles: List<CalendarTitle>, now: Long, zone: ZoneId): CalendarPage {
        if (!context.getDatabasePath(DatabaseVersions.guide.fileName).isFile) return page
        val db = guide.get()
        val revision = db.contentRevision() ?: return page
        val day = now.calendarDay(zone)
        val reusable = page.guideRevision == revision && page.guideDay == day
        val checked = if (reusable) page.guideTitles.toMutableSet() else mutableSetOf()
        val pending = titles.filter { it.key !in checked && it.title.length >= 3 }.take(CalendarPolicy.BATCH)
        val retained = if (reusable) page.items else page.items.filterNot { it.kind == CalendarKind.GUIDE }
        if (pending.isEmpty()) return page.copy(items = retained, guideRevision = revision, guideDay = day, guideTitles = checked.toList())
        val shift = prefs.get().settings.first().epgOffsetMinutes * 60_000L
        val start = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.ofEpochDay(day + 8).atStartOfDay(zone).toInstant().toEpochMilli()
        val additions = mutableListOf<CalendarItem>()
        for (title in pending) {
            currentCoroutineContext().ensureActive()
            val hits = coroutineScope {
                val signal = CancellationSignal()
                val read = async(BackgroundWork.reads) {
                    db.findByWords(start - shift, end - shift, now - shift, listOf(title.title),
                        inDescription = false, limit = 96, cancellation = signal)
                }
                try { read.await() } finally { signal.cancel() }
            }
            val wanted = title.title.trim()
            for ((key, listing) in hits) {
                val name = listing.title.trim()
                if (!name.equals(wanted, true) && !name.startsWith(wanted + ": ", true)) continue
                val time = listing.startMs + shift
                additions += CalendarItem("guide/" + title.key + "/" + key + "/" + time, title.key, title.title,
                    time.calendarDay(zone), CalendarKind.GUIDE, time = time, channelKey = key, contentId = title.id)
            }
            checked += title.key
        }
        // An import may commit while the queries are running. Retry that revision on the next visit.
        if (db.contentRevision() != revision) return page
        return page.copy(items = retained + additions, guideRevision = revision, guideDay = day, guideTitles = checked.toList())
    }
}
