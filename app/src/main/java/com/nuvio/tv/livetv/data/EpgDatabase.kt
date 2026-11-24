package com.nuvio.tv.livetv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.nuvio.tv.livetv.model.EpgAssignment
import com.nuvio.tv.livetv.model.EpgProgram
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The TV guide on the device, TiviMate style: every listing is stored here after a guide
 * update, and the app reads only the hours it needs (what's on screen, plus a margin), so Live
 * TV opens straight away however big the guide is, and uses far less memory.
 */
@Singleton
class EpgDatabase @Inject constructor(@ApplicationContext context: Context) :
    SQLiteOpenHelper(context, com.nuvio.tv.core.storage.DatabaseVersions.guide.fileName, null,
        com.nuvio.tv.core.storage.DatabaseVersions.guide.version) {

    private val interruptedStage = java.io.File(context.filesDir,"guide-stage-in-progress")

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if(db.isReadOnly) return
        // Some v3 files contain only the published guide. This also repairs a file whose
        // schema version was already advanced by a previous app before its onOpen failed.
        val stageCount = db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN " +
            "('stage','stage_meta','stage_channels','stage_info')", null).use { it.moveToFirst(); it.getInt(0) }
        if (stageCount != STAGING_TABLES.size) createStageTables(db)
        val hasCompletedStage = db.rawQuery("SELECT 1 FROM stage_info LIMIT 1", null).use { it.moveToFirst() }
        val partial = !hasCompletedStage && STAGING_TABLES.any { table ->
            db.rawQuery("SELECT 1 FROM $table LIMIT 1", null).use { it.moveToFirst() }
        }
        val unfinishedCopy = db.rawQuery("SELECT 1 FROM sqlite_master WHERE name='guide_next'",null)
            .use { it.moveToFirst() }
        if(interruptedStage.exists() || partial || unfinishedCopy) {
            discardWorkingGuide(db)
            interruptedStage.delete()
            SqliteCompact.compactIfWasteful(db,"interrupted guide")
        }
    }

    /** A stage_info row is committed only after the source has been staged successfully. */
    private fun discardWorkingGuide(db: SQLiteDatabase, failedSource: String? = null) {
        db.beginTransaction()
        try {
            if (failedSource != null) db.delete("stage_info", "src = ?", arrayOf(failedSource))
            for (table in listOf("stage", "stage_meta", "stage_channels"))
                db.execSQL("DELETE FROM $table WHERE src NOT IN (SELECT src FROM stage_info)")
            for(table in listOf("guide_search_next","guide_next","guide_choice","auto_next","channels_next"))
                db.execSQL("DROP TABLE IF EXISTS $table")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Volatile var retainDescriptions: Boolean = true

    init {
        // Readers don't wait for writers: the guide stays readable (scrolling, catch-up) while a
        // guide update is being stored, instead of stalling until it finishes.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // New databases can give interrupted-import pages back without a second database-sized copy.
        if(db.version==0) db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE programs ($PROGRAM_COLUMNS)")
        createChannelMap(db)
        db.execSQL("CREATE INDEX programs_ch_start ON programs(ch, start)")
        db.execSQL("CREATE INDEX programs_stop ON programs(stop)")
        db.execSQL("CREATE INDEX IF NOT EXISTS programs_start ON programs(start)")
        db.execSQL("CREATE TABLE auto (ch TEXT PRIMARY KEY, source TEXT NOT NULL, xmltv TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
        createStageTables(db)
        ensureSearchIndex(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 2: an index on start time, so "the hours around now" is read directly instead
        // of going through every future listing (which took minutes on big guides).
        if (oldVersion < 2) {
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS programs_start ON programs(start)") }
                .onFailure {
                    db.execSQL("DROP TABLE IF EXISTS programs")
                    db.execSQL("DROP TABLE IF EXISTS auto")
                    db.execSQL("DROP TABLE IF EXISTS meta")
                    onCreate(db)
                    return
                }
        }
        // Version 3: guides staged while they're read (see StageWriter).
        if (oldVersion < 3) createStageTables(db)
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE programs ADD COLUMN shared INTEGER NOT NULL DEFAULT 0")
            createChannelMap(db)
        }
        if (oldVersion < 6) {
            for (name in listOf("insert","delete","update_before","update_after"))
                db.execSQL("DROP TRIGGER IF EXISTS program_search_$name")
            db.execSQL("DROP TABLE IF EXISTS program_terms")
            db.execSQL("DROP TABLE IF EXISTS program_search")
            ensureSearchIndex(db)
        }
        if (oldVersion < 7) {
            db.execSQL("CREATE TABLE IF NOT EXISTS search_words(term TEXT PRIMARY KEY)")
            refreshSearchWords(db)
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        com.nuvio.tv.core.storage.rebuildCacheDatabase(db, com.nuvio.tv.core.storage.DatabaseVersions.guide, ::onCreate)

    /** The external-content index stores terms once per committed shared schedule row. */
    private fun ensureSearchIndex(db: SQLiteDatabase, rebuild: Boolean = true) {
        db.execSQL("CREATE TABLE IF NOT EXISTS search_words(term TEXT PRIMARY KEY)")
        val attached = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name='program_search_insert'", null)
            .use { it.moveToFirst() }
        if (attached) return
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS program_search USING fts4(title,descr,content='programs',tokenize=unicode61)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS program_terms USING fts4aux(program_search)")
        db.execSQL("CREATE TRIGGER program_search_insert AFTER INSERT ON programs BEGIN " +
            "INSERT INTO program_search(docid,title,descr) VALUES(new.rowid,new.title,new.descr); END")
        db.execSQL("CREATE TRIGGER program_search_delete BEFORE DELETE ON programs BEGIN " +
            "DELETE FROM program_search WHERE docid=old.rowid; END")
        db.execSQL("CREATE TRIGGER program_search_update_before BEFORE UPDATE ON programs BEGIN " +
            "DELETE FROM program_search WHERE docid=old.rowid; END")
        db.execSQL("CREATE TRIGGER program_search_update_after AFTER UPDATE ON programs BEGIN " +
            "INSERT INTO program_search(docid,title,descr) VALUES(new.rowid,new.title,new.descr); END")
        if (rebuild) {
            db.execSQL("INSERT INTO program_search(program_search) VALUES('rebuild')")
            refreshSearchWords(db)
        }
    }

    /** fts4aux decodes posting lists even for a term lookup. Do that once per committed guide,
     * not for every team's substring search. The table and postings publish atomically. */
    private fun refreshSearchWords(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM search_words")
        db.execSQL("INSERT INTO search_words(term) SELECT term FROM program_terms WHERE col='*'")
    }

    private fun createChannelMap(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE schedule_channels (ch TEXT PRIMARY KEY, ref TEXT NOT NULL)")
        db.execSQL("CREATE INDEX schedule_ref ON schedule_channels(ref)")
    }

    /** Replaces the whole guide (after a guide update), in one transaction. */
    fun replaceAll(
        fingerprint: String,
        programs: Map<String, List<EpgProgram>>,
        auto: Map<String, EpgAssignment>,
        /** What the guide was built from, in parts (see LiveTvRepository.guideParts). */
        parts: String? = null
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DROP INDEX IF EXISTS programs_ch_start")
            db.execSQL("DROP INDEX IF EXISTS programs_stop")
            db.execSQL("DROP INDEX IF EXISTS programs_start")
            db.execSQL("DROP INDEX IF EXISTS next_programs_ch_start")
            db.execSQL("DROP INDEX IF EXISTS next_programs_stop")
            db.execSQL("DROP INDEX IF EXISTS next_programs_start")
            db.delete("schedule_channels", null, null)
            db.delete("programs", null, null)
            db.delete("auto", null, null)
            val stmt = db.compileStatement(
                "INSERT INTO programs (ch, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?)"
            )
            programs.forEach { (ch, list) ->
                for (p in list) {
                    if (GuideGapTitles.matches(p.title)) continue
                    stmt.clearBindings()
                    stmt.bindString(1, ch)
                    stmt.bindLong(2, p.startMs)
                    stmt.bindLong(3, p.stopMs)
                    stmt.bindString(4, p.title)
                    p.description?.takeIf { retainDescriptions }?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
                    p.category?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                    p.episode?.let { stmt.bindString(7, it) } ?: stmt.bindNull(7)
                    p.icon?.let { stmt.bindString(8, it) } ?: stmt.bindNull(8)
                    p.year?.let { stmt.bindLong(9, it.toLong()) } ?: stmt.bindNull(9)
                    if (!retainDescriptions || p.people.isEmpty()) stmt.bindNull(10) else stmt.bindString(10, p.people.joinToString("\u001F"))
                    stmt.executeInsert()
                }
            }
            auto.forEach { (ch, a) ->
                db.insertWithOnConflict("auto", null, ContentValues().apply {
                    put("ch", ch); put("source", a.sourceId); put("xmltv", a.xmltvId)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS programs_ch_start ON programs(ch, start)")
            db.execSQL("CREATE INDEX IF NOT EXISTS programs_stop ON programs(stop)")
            db.execSQL("CREATE INDEX IF NOT EXISTS programs_start ON programs(start)")
            putFingerprint(db, fingerprint, parts)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Replaces just these channels' listings (Assign EPG: unassigned or given another guide
     * channel), drops their automatic matches, and stores the new fingerprint, in one go.
     */
    fun replaceChannels(
        fingerprint: String,
        channels: Set<String>,
        programs: Map<String, List<EpgProgram>>,
        /** Automatic matches found for these channels (kept for next time). */
        auto: Map<String, EpgAssignment> = emptyMap(),
        parts: String? = null
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            channels.forEach { ch ->
                db.delete("schedule_channels", "ch = ?", arrayOf(ch))
                db.delete("programs", "shared = 0 AND ch = ?", arrayOf(ch))
                db.delete("auto", "ch = ?", arrayOf(ch))
            }
            val stmt = db.compileStatement(
                "INSERT INTO programs (ch, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?)"
            )
            programs.forEach { (ch, list) ->
                for (p in list) {
                    if (GuideGapTitles.matches(p.title)) continue
                    stmt.clearBindings()
                    stmt.bindString(1, ch)
                    stmt.bindLong(2, p.startMs)
                    stmt.bindLong(3, p.stopMs)
                    stmt.bindString(4, p.title)
                    p.description?.takeIf { retainDescriptions }?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
                    p.category?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                    p.episode?.let { stmt.bindString(7, it) } ?: stmt.bindNull(7)
                    p.icon?.let { stmt.bindString(8, it) } ?: stmt.bindNull(8)
                    p.year?.let { stmt.bindLong(9, it.toLong()) } ?: stmt.bindNull(9)
                    if (!retainDescriptions || p.people.isEmpty()) stmt.bindNull(10) else stmt.bindString(10, p.people.joinToString("\u001F"))
                    stmt.executeInsert()
                }
            }
            auto.forEach { (ch, a) ->
                db.insertWithOnConflict("auto", null, ContentValues().apply {
                    put("ch", ch); put("source", a.sourceId); put("xmltv", a.xmltvId)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            putFingerprint(db, fingerprint, parts)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Changes with committed listings, including catch-up additions that keep the source fingerprint. */
    fun contentRevision(): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = 'content_revision'", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Boundaries that can change a current/paused fixture's moving guide search window. */
    fun nextSportsBoundary(now: Long): Long = readableDatabase.rawQuery(
        "SELECT MIN(t) FROM (SELECT MIN(stop) t FROM programs WHERE stop>? " +
            "UNION ALL SELECT MIN(start) FROM programs WHERE start>? " +
            "UNION ALL SELECT MIN(start)-4200000 FROM programs WHERE start>? " +
            "UNION ALL SELECT MIN(start)-129600000 FROM programs WHERE start>? " +
            "UNION ALL SELECT MIN(start)+108000000 FROM programs WHERE start>?)",
        arrayOf(now, now, now + 4_200_000L, now + 129_600_000L, now - 108_000_000L).map(Long::toString).toTypedArray()
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else Long.MAX_VALUE }

    private fun bumpContentRevision(db: SQLiteDatabase) {
        refreshSearchWords(db)
        db.insertWithOnConflict("meta", null, ContentValues().apply {
            put("k", "content_revision"); put("v", java.util.UUID.randomUUID().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Stores the fingerprint, and its parts (or clears stale parts), inside the caller's transaction. */
    private fun putFingerprint(db: SQLiteDatabase, fingerprint: String, parts: String?) {
        // An atomic table replacement drops its old triggers. Attach the replacement before publication.
        ensureSearchIndex(db)
        bumpContentRevision(db)
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", "fingerprint"); put("v", fingerprint) },
            SQLiteDatabase.CONFLICT_REPLACE)
        if (parts != null) {
            db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", "parts"); put("v", parts) },
                SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            db.delete("meta", "k = ?", arrayOf("parts"))
        }
    }

    /** What the stored guide was built from, in parts, or null (stored by an older version). */
    fun parts(): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = 'parts'", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /**
     * A playlist turned off or removed: drops its channels' listings and matches (every channel
     * key starts with its playlist's id and "|"), plus [channels], and stores the new fingerprint,
     * in one go. Nothing else in the guide changes, so no guide has to be read again.
     */
    fun dropPlaylists(
        playlistIds: Collection<String>,
        channels: Collection<String>,
        fingerprint: String,
        parts: String
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            playlistIds.forEach { id ->
                val prefix = "$id|"
                val args = arrayOf(prefix.length.toString(), prefix)
                db.delete("schedule_channels", "substr(ch, 1, ?) = ?", args)
                db.delete("programs", "shared = 0 AND substr(ch, 1, ?) = ?", args)
                db.delete("auto", "substr(ch, 1, ?) = ?", args)
            }
            channels.forEach { ch ->
                db.delete("schedule_channels", "ch = ?", arrayOf(ch))
                db.delete("programs", "shared = 0 AND ch = ?", arrayOf(ch))
                db.delete("auto", "ch = ?", arrayOf(ch))
            }
            putFingerprint(db, fingerprint, parts)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun fingerprint(): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = 'fingerprint'", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Membership only, for the unassigned-channel filter; no programme payload is retained. */
    fun channelsWithListings(fromMs:Long,toMs:Long): Set<String> =
        readableDatabase.rawQuery("SELECT DISTINCT ch FROM $GUIDE_LISTINGS WHERE start<? AND stop>?",
            arrayOf(toMs.toString(),fromMs.toString())).use { c ->
            buildSet { while(c.moveToNext()) add(c.getString(0)) }
        }

    /** Listings overlapping [fromMs, toMs), by channel, in time order. */
    /**
     * Listings in [fromMs, toMs), by channel, in time order.
     *  - [light]: just the parts the guide grid needs (title, times, episode, image), without
     *    descriptions and cast. Several times quicker to read and much smaller in memory;
     *    details are fetched for the show you're looking at ([program]).
     *  - [channels]: only these channels (the group on screen first, the rest afterwards).
     */
    fun range(
        fromMs: Long,
        toMs: Long,
        onlyChannel: String? = null,
        light: Boolean = false,
        channels: Collection<String>? = null
    ): Map<String, List<EpgProgram>> {
        val out = HashMap<String, ArrayList<EpgProgram>>()
        val schedules = HashMap<String, ArrayList<EpgProgram>>()
        val aliases = HashMap<String, String>()
        val columns = if (light) "ch, start, stop, title, ep, icon" else "ch, start, stop, title, descr, cat, ep, icon, year, people"
        // Read by start time (indexed): shows starting in the window, plus ones that started up
        // to [MAX_SHOW_MS] earlier and are still on. Looking up "everything that ends after the
        // start of the window" instead meant reading every future listing in the guide.
        val timeArgs = listOf((fromMs - MAX_SHOW_MS).toString(), toMs.toString(), fromMs.toString())
        fun read(chFilter: String, chArgs: List<String>, allChannels: Boolean = false) {
            val window = "start >= ? AND start < ? AND stop > ?"
            // Read the payload once per physical schedule on startup. The aliases are in the
            // same statement so that they always refer to the same committed generation.
            val sql = if (allChannels) {
                val extra = if (light) "NULL,NULL" else "NULL,NULL,NULL,NULL,NULL,NULL"
                "SELECT $columns,shared FROM programs WHERE $window UNION ALL " +
                    "SELECT ref,NULL,NULL,ch,$extra,2 FROM schedule_channels"
            } else "SELECT $columns FROM $GUIDE_LISTINGS WHERE $chFilter $window"
            readableDatabase.rawQuery(sql, (chArgs + timeArgs).toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val kind = if (allChannels) c.getInt(c.columnCount - 1) else 0
                    if (kind == 2) {
                        aliases[c.getString(3)] = c.getString(0)
                        continue
                    }
                    if (GuideGapTitles.matches(c.getString(3))) continue
                    val p = if (light) EpgProgram(
                        startMs = c.getLong(1),
                        stopMs = c.getLong(2),
                        title = c.getString(3),
                        episode = if (c.isNull(4)) null else XmltvParser.cleanEpisode(c.getString(4)),
                        icon = if (c.isNull(5)) null else c.getString(5)
                    ) else EpgProgram(
                        startMs = c.getLong(1),
                        stopMs = c.getLong(2),
                        title = c.getString(3),
                        description = if (c.isNull(4)) null else c.getString(4),
                        category = if (c.isNull(5)) null else c.getString(5),
                        episode = if (c.isNull(6)) null else XmltvParser.cleanEpisode(c.getString(6)),
                        icon = if (c.isNull(7)) null else c.getString(7),
                        year = if (c.isNull(8)) null else c.getInt(8),
                        people = if (c.isNull(9)) emptyList() else c.getString(9).split('\u001F')
                    )
                    val rows = if (kind == 1) schedules else out
                    rows.getOrPut(c.getString(0)) { ArrayList() } += p
                }
            }
        }
        when {
            onlyChannel != null -> read("ch = ? AND", listOf(onlyChannel))
            channels != null -> channels.distinct().chunked(400).forEach { part ->
                read("ch IN (" + part.joinToString(",") { "?" } + ") AND", part)
            }
            else -> read("", emptyList(), allChannels = true)
        }
        out.values.forEach { l -> l.sortBy { it.startMs } }
        schedules.values.forEach { it.sortBy { p -> p.startMs } }
        for ((channel, ref) in aliases) {
            val shared = schedules[ref] ?: continue
            val direct = out[channel]
            if (direct == null) out[channel] = shared else {
                direct.addAll(shared)
                direct.sortBy { it.startMs }
            }
        }
        return out
    }

    /**
     * Sports: listings starting in [fromMs, toMs) and still on at [minStopMs] whose title (or, with
     * [inDescription], description) contains any of [words], ignoring case. Channel key -> show.
     * Asked in parts so a busy day's team names never go over SQLite's limit on placeholders.
     */
    /** A bounded time-window scan for indexed in-memory vocabulary matching. */
    fun visitPrograms(fromMs: Long, toMs: Long, visit: (String, EpgProgram) -> Unit) {
        // Bound CursorWindow refills without limiting rows or changing the requested interval.
        var start = fromMs
        while (start < toMs) {
            val end = minOf(toMs, start + 15 * 60_000L)
            readableDatabase.rawQuery(
                "SELECT ch, start, stop, title, descr, cat FROM $GUIDE_LISTINGS WHERE start >= ? AND start < ?",
                arrayOf(start.toString(), end.toString())
            ).use { c ->
                while (c.moveToNext()) visit(c.getString(0), EpgProgram(
                    startMs = c.getLong(1), stopMs = c.getLong(2), title = c.getString(3),
                    description = if (c.isNull(4)) null else c.getString(4),
                    category = if (c.isNull(5)) null else c.getString(5)))
            }
            start = end
        }
    }

    /** Stream committed current listings, including full-day sports sessions, during guide imports. */
    fun visitCurrentPrograms(atMs: Long, channels: Collection<String>, visit: (String, EpgProgram) -> Unit) {
        channels.distinct().chunked(400).forEach { part ->
            val sql = "SELECT ch, start, stop, title, descr, cat FROM $GUIDE_LISTINGS WHERE ch IN (" +
                part.joinToString(",") { "?" } + ") AND start >= ? AND start <= ? AND stop > ?"
            val args = part + listOf((atMs - 30L * 60 * 60_000).toString(), atMs.toString(), atMs.toString())
            readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) visit(c.getString(0), EpgProgram(
                    startMs = c.getLong(1), stopMs = c.getLong(2), title = c.getString(3),
                    description = if (c.isNull(4)) null else c.getString(4),
                    category = if (c.isNull(5)) null else c.getString(5)))
            }
        }
    }

    /** The start-time index bounds week/month Calendar reads to dates the playlist has published. */
    fun sportsGuideRange(fromMs: Long, untilMs: Long): LongRange? {
        fun boundary(order: String): Long? = readableDatabase.rawQuery(
            "SELECT start FROM programs WHERE start>=CAST(? AS INTEGER) AND start<CAST(? AS INTEGER) ORDER BY start $order LIMIT 1",
            arrayOf(fromMs.toString(), untilMs.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        val first = boundary("ASC") ?: return null
        return first..(boundary("DESC") ?: first)
    }

    /** Sports discovery uses the classifier's complete vocabulary, with bounded cursor retention. */
    fun visitSportsPrograms(fromMs:Long,toMs:Long,minStopMs:Long,words:Collection<String>,visit:(String,EpgProgram)->Unit) =
        visitIndexedAliases(fromMs,toMs,minStopMs,words,visit)

    private fun visitIndexedAliases(fromMs:Long,toMs:Long,minStopMs:Long,words:Collection<String>,visit:(String,EpgProgram)->Unit) {
        val phrases=SportsSearchVocabulary.minimalCover(words)
        if(phrases.isEmpty()) return
        val revision=contentRevision()
        fun read(terms:List<String>) {
            val query=terms.distinct().sorted().joinToString(" OR ") { "\"${it.replace("\"","\"\"")}\"" }
            var programme=0L;var alias=0L
            // An unbounded Android SQLiteCursor re-executes its query as windows fill.
            // Seek both indexes instead: even one physical schedule with 50k aliases has
            // bounded pages. FTS consumes docid order; schedule_ref supplies alias rowid order.
            val sql="SELECT p.rowid,COALESCE(a.rowid,0),CASE WHEN p.shared=0 THEN p.ch ELSE a.ch END,"+
                "p.start,p.stop,p.title,p.descr,p.cat FROM program_search " +
                "JOIN programs p ON p.rowid=program_search.docid " +
                "LEFT JOIN schedule_channels a INDEXED BY schedule_ref ON p.shared=1 AND a.ref=p.ch " +
                "AND a.rowid>CASE WHEN p.rowid=? THEN ? ELSE 0 END " +
                "WHERE program_search MATCH ? AND program_search.docid>=? AND (p.rowid>? OR p.shared=1) " +
                "AND (p.shared=0 OR a.ch IS NOT NULL) AND p.start>=? AND p.start<? AND p.stop>? " +
                "ORDER BY program_search.docid LIMIT 4000"
            while(true) {
                var count=0
                val args=listOf(programme.toString(),alias.toString(),query,programme.toString(),programme.toString(),
                    fromMs.toString(),toMs.toString(),minStopMs.toString())
                readableDatabase.rawQuery(sql,args.toTypedArray()).use { c ->
                    while(c.moveToNext()) {
                        programme=c.getLong(0);alias=c.getLong(1);count++
                        visit(c.getString(2),EpgProgram(c.getLong(3),c.getLong(4),c.getString(5),
                            if(c.isNull(6)) null else c.getString(6),if(c.isNull(7)) null else c.getString(7)))
                    }
                }
                if(contentRevision()!=revision) throw kotlinx.coroutines.CancellationException("Guide changed during Sports discovery")
                if(count<4000) break
            }
        }
        read(phrases)
        // Taxonomy used NFKD before this index existed. Retain full-width/ligature aliases too;
        // stream the exceptional dictionary entries instead of keeping a second term catalogue.
        val tokens=phrases.flatMap(SportsSearchVocabulary::compatibilityWords).toSet()
        val extra=ArrayList<String>(64)
        readableDatabase.rawQuery("SELECT term FROM search_words WHERE term GLOB '*[^ -~]*'",null).use { c ->
            while(c.moveToNext()) {
                val term=c.getString(0)
                if(SportsSearchVocabulary.compatibilityWords(term).any { it in tokens }) extra+=term
                if(extra.size==64) { read(extra);extra.clear() }
            }
        }
        if(extra.isNotEmpty()) read(extra)
    }

    fun findByWords(
        fromMs: Long,
        toMs: Long,
        minStopMs: Long,
        words: Collection<String>,
        inDescription: Boolean = true,
        limit: Int = 4_000,
        cancellation: android.os.CancellationSignal? = null
    ): List<Pair<String, EpgProgram>> {
        val clean = words.map { it.trim() }.filter { it.length >= 3 }.distinct()
        if (clean.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, EpgProgram>>()
        val seen = HashSet<String>()
        fun like(w: String) = "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        clean.chunked(if (inDescription) 180 else 360).forEach { part ->
            if (out.size >= limit) return@forEach
            val conds = part.flatMap { w ->
                if (inDescription) listOf("title LIKE ? ESCAPE '\\'", "descr LIKE ? ESCAPE '\\'") else listOf("title LIKE ? ESCAPE '\\'")
            }.joinToString(" OR ")
            val args = listOf(fromMs.toString(), toMs.toString(), minStopMs.toString()) +
                part.flatMap { w -> if (inDescription) listOf(like(w), like(w)) else listOf(like(w)) }
            // Expand substrings over the term dictionary, then use its postings. The final LIKE
            // preserves existing phrase, punctuation, Unicode and literal wildcard semantics.
            val anchors = part.map { word -> Regex("[A-Za-z0-9]+").findAll(word).maxByOrNull { it.value.length }?.value }
            val terms = LinkedHashSet<String>()
            var completeTerms=true
            if (anchors.all { it != null }) for (anchor in anchors.filterNotNull().distinct()) {
                readableDatabase.rawQuery("SELECT term FROM search_words WHERE term LIKE ? ESCAPE '\\'",
                    arrayOf(like(anchor)), cancellation).use { c ->
                    while(c.moveToNext()) {
                        terms+=c.getString(0)
                        if(terms.size>1024) { completeTerms=false;break }
                    }
                }
                if(!completeTerms) break
            }
            // An exceptional dictionary must not create an unbounded FTS expression. The
            // bounded legacy time-window query is an exact fallback, including its row order.
            val indexed = completeTerms && anchors.all { it != null }
            if (indexed && terms.isEmpty()) return@forEach
            val query = terms.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
            // Select postings once. A correlated EXISTS re-ran the FTS query per listing.
            // Restore the legacy direct/shared, programme-time and row traversal before LIMIT.
            val listings=if(indexed) "(SELECT CASE WHEN p.shared=0 THEN p.ch ELSE a.ch END AS ch," +
                "p.start,p.stop,p.title,p.descr,p.cat,p.shared AS branch,p.rowid AS programme_row,a.rowid AS alias_row " +
                "FROM sports_hits h JOIN programs p ON p.rowid=h.docid " +
                "LEFT JOIN schedule_channels a ON p.shared=1 AND a.ref=p.ch WHERE p.shared=0 OR a.ch IS NOT NULL)" else GUIDE_LISTINGS
            val searchArgs = if (indexed) listOf(query) + args else args
            val prefix = if (indexed) "WITH sports_hits AS (SELECT docid FROM program_search WHERE program_search MATCH ?) " else ""
            val ordering=if(indexed) " ORDER BY branch,start,programme_row,alias_row" else ""
            val sql=prefix+"SELECT ch,start,stop,title,descr,cat FROM $listings WHERE start>=? AND start<? AND stop>? AND ($conds)"+
                ordering+" LIMIT ${limit-out.size}"
            readableDatabase.rawQuery(sql, searchArgs.toTypedArray(), cancellation).use { c ->
                while (c.moveToNext()) {
                    val ch = c.getString(0)
                    val start = c.getLong(1)
                    if (!seen.add("$ch|$start")) continue
                    out += ch to EpgProgram(
                        startMs = start,
                        stopMs = c.getLong(2),
                        title = c.getString(3),
                        description = if (c.isNull(4)) null else c.getString(4),
                        category = if (c.isNull(5)) null else c.getString(5)
                    )
                }
            }
        }
        return out
    }

    /** Keep 9.10's candidate eligibility and traversal; bound scoring retention separately. */
    fun visitMatchPrograms(fromMs:Long,toMs:Long,minStopMs:Long,words:Collection<String>,chronological:Boolean=false,
        visit:(String,EpgProgram)->Unit) {
        val rows=findByWords(fromMs,toMs,minStopMs,words)
        (if(chronological) rows.sortedBy { it.second.startMs } else rows).forEach { (ch,p) -> visit(ch,p) }
    }

    /** One show's full details (description, cast…), for the show you're looking at. */
    fun program(ch: String, startMs: Long): EpgProgram? =
        range(startMs, startMs + 1, onlyChannel = ch)[ch]?.firstOrNull { it.startMs == startMs }

    /** One channel's listings across [fromMs, toMs) (overlay mode's full schedule, catch-up). */
    fun channelRange(ch: String, fromMs: Long, toMs: Long): List<EpgProgram> =
        range(fromMs, toMs, ch)[ch].orEmpty()

    fun autoMatches(): Map<String, EpgAssignment> {
        val out = HashMap<String, EpgAssignment>()
        readableDatabase.rawQuery("SELECT ch, source, xmltv FROM auto", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = EpgAssignment(c.getString(1), c.getString(2))
        }
        return out
    }

    /**
     * Applies the individual intervals returned by the Xtream archive. Gaps in a sparse
     * response keep their previously published programmes.
     */
    fun mergeChannelArchive(ch: String, programs: List<EpgProgram>) {
        val listings = programs.filterNot { GuideGapTitles.matches(it.title) }
        if (listings.isEmpty()) return
        val db = writableDatabase
        db.transaction {
            // A catch-up edit belongs to just this channel. Detach its full schedule first.
            db.execSQL("INSERT INTO programs (ch,start,stop,title,descr,cat,ep,icon,year,people) " +
                "SELECT a.ch,p.start,p.stop,p.title,p.descr,p.cat,p.ep,p.icon,p.year,p.people " +
                "FROM schedule_channels a JOIN programs p ON p.ch=a.ref AND p.shared=1 WHERE a.ch=?", arrayOf(ch))
            db.delete("schedule_channels", "ch = ?", arrayOf(ch))
            db.compileStatement("DELETE FROM programs WHERE shared=0 AND ch=? AND start<? AND stop>?").use { remove ->
                remove.bindString(1, ch)
                for (p in listings) {
                    remove.bindLong(2, p.stopMs)
                    remove.bindLong(3, p.startMs)
                    remove.executeUpdateDelete()
                }
            }
            val stmt = db.compileStatement(
                "INSERT INTO programs (ch, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?)"
            )
            listings.forEach { p ->
                stmt.clearBindings()
                stmt.bindString(1, ch); stmt.bindLong(2, p.startMs); stmt.bindLong(3, p.stopMs); stmt.bindString(4, p.title)
                p.description?.takeIf { retainDescriptions }?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
                stmt.bindNull(6); stmt.bindNull(7); stmt.bindNull(8); stmt.bindNull(9); stmt.bindNull(10)
                stmt.executeInsert()
            }
            bumpContentRevision(db)
        }
    }

    /** Drops these channels' listings (channels hidden in every profile). */
    fun deleteChannels(channels: Collection<String>) {
        if (channels.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            channels.forEach { ch ->
                db.delete("schedule_channels", "ch = ?", arrayOf(ch))
                db.delete("programs", "shared = 0 AND ch = ?", arrayOf(ch))
            }
            bumpContentRevision(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ------------------------------------------------------------ staged guides
    //
    // Each guide file's listings (for the channels it could match) are written here while the
    // file is read, a channel at a time, instead of being held in memory: a big guide used to take
    // a few hundred MB while it was matched. The guide shown is then built from here in one step
    // (the best guide for each channel), and a guide file that hasn't changed isn't read again.

    private fun createStageTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS stage (
                src TEXT NOT NULL, xid TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL,
                descr TEXT, cat TEXT, ep TEXT, icon TEXT, year INTEGER, people TEXT)"""
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS stage_meta (src TEXT NOT NULL, xid TEXT NOT NULL, cnt INTEGER NOT NULL, " +
                "last_stop INTEGER NOT NULL, placeholders INTEGER NOT NULL, PRIMARY KEY (src, xid))"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS stage_channels (src TEXT NOT NULL, xid TEXT NOT NULL, names TEXT, icon TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS stage_info (src TEXT PRIMARY KEY, stamp TEXT NOT NULL, made INTEGER NOT NULL)")
        // No index on the working copy: keeping one up to date for every listing made storing a
        // big guide several times slower. The final step looks channels up the other way round.
        db.execSQL("DROP INDEX IF EXISTS stage_src_xid")
    }

    /** What's known about one guide channel's staged listings. */
    class StagedMeta(val count: Int, val lastStop: Long, val placeholders: Int)

    /** One guide file being staged: [add] each channel's shows, then [finish] (or [abort]). */
    inner class StageWriter internal constructor(private val src: String) {
        private val db = writableDatabase
        private val stmt: android.database.sqlite.SQLiteStatement
        private var rows = 0
        private val meta = HashMap<String, LongArray>() // count, lastStop, placeholders
        private var open = true
        private var transactionOpen = true

        init {
            interruptedStage.parentFile?.mkdirs()
            interruptedStage.writeText(src)
            db.beginTransaction()
            try {
            db.delete("stage", "src = ?", arrayOf(src))
            db.delete("stage_meta", "src = ?", arrayOf(src))
            db.delete("stage_channels", "src = ?", arrayOf(src))
            db.delete("stage_info", "src = ?", arrayOf(src))
            stmt = db.compileStatement(
                "INSERT INTO stage (src, xid, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?,?)"
            )
            } catch(failure: Throwable) {
                db.endTransaction(); transactionOpen=false
                runCatching { discardWorkingGuide(db, src); interruptedStage.delete() }
                throw failure
            }
        }

        /** Release the SQLite thread affinity and write lock before suspending. */
        fun pauseBatch() {
            if (transactionOpen) { db.setTransactionSuccessful(); db.endTransaction(); transactionOpen = false }
        }
        fun resumeBatch() {
            if (!transactionOpen) { db.beginTransaction(); transactionOpen = true }
        }

        fun add(xid: String, programs: List<EpgProgram>, isPlaceholder: (String) -> Boolean) {
            if (programs.isEmpty()) return
            val m = meta.getOrPut(xid) { LongArray(3) }
            programs.forEach { p ->
                // Source ranking counts the feed as supplied, even when a blank has no stored row.
                m[0]++
                if (p.stopMs > m[1]) m[1] = p.stopMs
                val gap = GuideGapTitles.matches(p.title)
                if (gap || isPlaceholder(p.title)) m[2]++
                if (gap) return@forEach
                stmt.clearBindings()
                stmt.bindString(1, src); stmt.bindString(2, xid)
                stmt.bindLong(3, p.startMs); stmt.bindLong(4, p.stopMs); stmt.bindString(5, p.title)
                p.description?.takeIf { retainDescriptions }?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                p.category?.let { stmt.bindString(7, it) } ?: stmt.bindNull(7)
                p.episode?.let { stmt.bindString(8, it) } ?: stmt.bindNull(8)
                p.icon?.let { stmt.bindString(9, it) } ?: stmt.bindNull(9)
                p.year?.let { stmt.bindLong(10, it.toLong()) } ?: stmt.bindNull(10)
                if (!retainDescriptions || p.people.isEmpty()) stmt.bindNull(11) else stmt.bindString(11, p.people.joinToString("\u001F"))
                stmt.executeInsert()
            }
            rows += programs.size
            // Written in parts, so the database's pending changes stay small on big guides.
            if (rows >= 50_000) {
                rows = 0
                db.setTransactionSuccessful(); db.endTransaction(); db.beginTransaction()
            }
        }

        /** Stores the guide's channel list (for matching it again without reading the file) and the file's stamp. */
        fun finish(stamp: String, channels: List<Triple<String, List<String>, String?>>): Map<String, StagedMeta> {
            meta.forEach { (xid, m) ->
                db.insertWithOnConflict("stage_meta", null, ContentValues().apply {
                    put("src", src); put("xid", xid); put("cnt", m[0]); put("last_stop", m[1]); put("placeholders", m[2])
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val ch = db.compileStatement("INSERT INTO stage_channels (src, xid, names, icon) VALUES (?,?,?,?)")
            channels.forEach { (id, names, icon) ->
                ch.clearBindings()
                ch.bindString(1, src); ch.bindString(2, id)
                if (names.isEmpty()) ch.bindNull(3) else ch.bindString(3, names.joinToString("\u001F"))
                icon?.let { ch.bindString(4, it) } ?: ch.bindNull(4)
                ch.executeInsert()
            }
            db.insertWithOnConflict("stage_info", null, ContentValues().apply {
                put("src", src); put("stamp", stamp); put("made", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            db.endTransaction(); transactionOpen=false
            ch.close(); stmt.close()
            interruptedStage.delete()
            open = false
            return meta.mapValues { (_, m) -> StagedMeta(m[0].toInt(), m[1], m[2].toInt()) }
        }

        suspend fun finishBatched(stamp: String, channels: List<Triple<String,List<String>,String?>>, checkpoint: suspend () -> Unit): Map<String,StagedMeta> {
            suspend fun nextBatch() { pauseBatch(); checkpoint(); resumeBatch() }
            for (batch in meta.entries.chunked(128)) {
                nextBatch()
                batch.forEach { (xid,m) -> db.insertWithOnConflict("stage_meta",null,ContentValues().apply {
                    put("src",src); put("xid",xid); put("cnt",m[0]); put("last_stop",m[1]); put("placeholders",m[2])
                },SQLiteDatabase.CONFLICT_REPLACE) }
            }
            for (batch in channels.chunked(128)) {
                nextBatch()
                db.compileStatement("INSERT INTO stage_channels(src,xid,names,icon) VALUES(?,?,?,?)").use { ch ->
                    batch.forEach { (id,names,icon) ->
                        ch.clearBindings(); ch.bindString(1,src); ch.bindString(2,id)
                        if (names.isEmpty()) ch.bindNull(3) else ch.bindString(3,names.joinToString("\u001F"))
                        if (icon == null) ch.bindNull(4) else ch.bindString(4,icon)
                        ch.executeInsert()
                    }
                }
            }
            db.insertWithOnConflict("stage_info",null,ContentValues().apply {
                put("src",src); put("stamp",stamp); put("made",System.currentTimeMillis())
            },SQLiteDatabase.CONFLICT_REPLACE)
            pauseBatch(); open = false; stmt.close(); interruptedStage.delete()
            return meta.mapValues { (_,m) -> StagedMeta(m[0].toInt(),m[1],m[2].toInt()) }
        }

        /** A failed import cannot leave committed staging batches behind. The marker retries cleanup on reopen. */
        fun abort() {
            if (!open) return
            open=false
            if(transactionOpen) { runCatching { db.endTransaction() }; transactionOpen=false }
            runCatching { stmt.close() }
            runCatching {
                discardWorkingGuide(db, src)
                interruptedStage.delete()
                SqliteCompact.compactIfWasteful(db,"interrupted guide")
            }
        }
    }

    fun startStage(src: String): StageWriter = StageWriter(src)

    /** The stamp a guide was staged with, and when, or null. */
    fun stageInfo(src: String): Pair<String, Long>? =
        readableDatabase.rawQuery("SELECT stamp, made FROM stage_info WHERE src = ?", arrayOf(src)).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
        }

    fun stagedMeta(src: String): Map<String, StagedMeta> {
        val out = HashMap<String, StagedMeta>()
        readableDatabase.rawQuery("SELECT xid, cnt, last_stop, placeholders FROM stage_meta WHERE src = ?", arrayOf(src)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = StagedMeta(c.getInt(1), c.getLong(2), c.getInt(3))
        }
        return out
    }

    /** A staged guide's channel list: id, display names, logo. */
    fun stagedChannels(src: String): List<Triple<String, List<String>, String?>> {
        val out = ArrayList<Triple<String, List<String>, String?>>()
        readableDatabase.rawQuery("SELECT xid, names, icon FROM stage_channels WHERE src = ?", arrayOf(src)).use { c ->
            while (c.moveToNext()) out += Triple(
                c.getString(0),
                if (c.isNull(1)) emptyList() else c.getString(1).split('\u001F'),
                if (c.isNull(2)) null else c.getString(2)
            )
        }
        return out
    }

    /** Several staged guide channels' listings (full details), in one pass over the guide. */
    fun stagedProgramsFor(src: String, xids: Collection<String>): Map<String, List<EpgProgram>> {
        val out = HashMap<String, ArrayList<EpgProgram>>()
        xids.distinct().chunked(400).forEach { part ->
            readableDatabase.rawQuery(
                "SELECT xid, start, stop, title, descr, cat, ep, icon, year, people FROM stage WHERE src = ? AND xid IN (" +
                    part.joinToString(",") { "?" } + ")",
                (listOf(src) + part).toTypedArray()
            ).use { c ->
                while (c.moveToNext()) out.getOrPut(c.getString(0)) { ArrayList() } += EpgProgram(
                    startMs = c.getLong(1), stopMs = c.getLong(2), title = c.getString(3),
                    description = if (c.isNull(4)) null else c.getString(4),
                    category = if (c.isNull(5)) null else c.getString(5),
                    episode = if (c.isNull(6)) null else c.getString(6),
                    icon = if (c.isNull(7)) null else c.getString(7),
                    year = if (c.isNull(8)) null else c.getInt(8),
                    people = if (c.isNull(9)) emptyList() else c.getString(9).split('\u001F')
                )
            }
        }
        out.values.forEach { l -> l.sortBy { it.startMs } }
        return out
    }

    /** One staged guide channel's listings (full details), in time order. */
    fun stagedPrograms(src: String, xid: String): List<EpgProgram> {
        val out = ArrayList<EpgProgram>()
        readableDatabase.rawQuery(
            "SELECT start, stop, title, descr, cat, ep, icon, year, people FROM stage WHERE src = ? AND xid = ? ORDER BY start",
            arrayOf(src, xid)
        ).use { c ->
            while (c.moveToNext()) out += EpgProgram(
                startMs = c.getLong(0), stopMs = c.getLong(1), title = c.getString(2),
                description = if (c.isNull(3)) null else c.getString(3),
                category = if (c.isNull(4)) null else c.getString(4),
                episode = if (c.isNull(5)) null else c.getString(5),
                icon = if (c.isNull(6)) null else c.getString(6),
                year = if (c.isNull(7)) null else c.getInt(7),
                people = if (c.isNull(8)) emptyList() else c.getString(8).split('\u001F')
            )
        }
        return out
    }

    /** A staged guide's listings in [fromMs, toMs), short form (no descriptions), by guide channel. */
    fun stagedWindow(src: String, fromMs: Long, toMs: Long): Map<String, List<EpgProgram>> {
        val out = HashMap<String, ArrayList<EpgProgram>>()
        readableDatabase.rawQuery(
            "SELECT xid, start, stop, title, ep, icon FROM stage WHERE src = ? AND stop > ? AND start < ?",
            arrayOf(src, fromMs.toString(), toMs.toString())
        ).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getString(0)) { ArrayList() } += EpgProgram(
                startMs = c.getLong(1), stopMs = c.getLong(2), title = c.getString(3),
                episode = if (c.isNull(4)) null else c.getString(4),
                icon = if (c.isNull(5)) null else c.getString(5)
            )
        }
        return out
    }

    /** Guides no longer used: their staged listings go. */
    fun dropStagesExcept(keep: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (keep.isEmpty()) {
                STAGING_TABLES.forEach { db.execSQL("DROP TABLE IF EXISTS $it") }; createStageTables(db)
            } else {
                val stale = ArrayList<String>()
                db.rawQuery(STAGING_TABLES.joinToString(" UNION ") { "SELECT DISTINCT src FROM $it" }, null).use { c ->
                    while (c.moveToNext()) if (c.getString(0) !in keep) stale += c.getString(0)
                }
                stale.forEach { src -> STAGING_TABLES.forEach { db.delete(it, "src = ?", arrayOf(src)) } }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * Replaces the whole guide from the staged guides in one step: each channel in [choice] gets
     * the listings of its chosen guide channel (source, guide id) in [fromMs, toMs); [extra] are
     * listings given directly (hand-picked guides). Like [replaceAll], in one transaction.
     */
    fun replaceAllFromStage(
        fingerprint: String,
        choice: Map<String, EpgAssignment>,
        extra: Map<String, List<EpgProgram>>,
        auto: Map<String, EpgAssignment>,
        parts: String?,
        fromMs: Long,
        toMs: Long
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DROP INDEX IF EXISTS programs_ch_start")
            db.execSQL("DROP INDEX IF EXISTS programs_stop")
            db.execSQL("DROP INDEX IF EXISTS programs_start")
            db.execSQL("DROP INDEX IF EXISTS next_programs_ch_start")
            db.execSQL("DROP INDEX IF EXISTS next_programs_stop")
            db.execSQL("DROP INDEX IF EXISTS next_programs_start")
            db.delete("schedule_channels", null, null)
            db.delete("programs", null, null)
            db.delete("auto", null, null)
            db.execSQL("DROP TABLE IF EXISTS temp.choice")
            db.execSQL("CREATE TEMP TABLE choice (src TEXT NOT NULL, xid TEXT NOT NULL, ch TEXT NOT NULL, PRIMARY KEY (src, xid, ch))")
            val ins = db.compileStatement("INSERT OR IGNORE INTO temp.choice (src, xid, ch) VALUES (?,?,?)")
            choice.forEach { (ch, a) ->
                ins.clearBindings(); ins.bindString(1, a.sourceId); ins.bindString(2, a.xmltvId); ins.bindString(3, ch)
                ins.executeInsert()
            }
            db.execSQL(
                "INSERT INTO programs (ch, start, stop, title, descr, cat, ep, icon, year, people) " +
                    "SELECT c.ch, s.start, s.stop, s.title, ${if (retainDescriptions) "s.descr" else "NULL"}, s.cat, s.ep, s.icon, s.year, ${if (retainDescriptions) "s.people" else "NULL"} " +
                    "FROM stage s JOIN temp.choice c ON c.src = s.src AND c.xid = s.xid WHERE s.stop > ? AND s.start < ? " +
                    "AND NOT (${GuideGapTitles.predicate("s.title")})",
                arrayOf<Any>(fromMs, toMs)
            )
            db.execSQL("DROP TABLE IF EXISTS temp.choice")
            val stmt = db.compileStatement(
                "INSERT INTO programs (ch, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?)"
            )
            extra.forEach { (ch, list) ->
                for (p in list) {
                    if (GuideGapTitles.matches(p.title)) continue
                    stmt.clearBindings()
                    stmt.bindString(1, ch); stmt.bindLong(2, p.startMs); stmt.bindLong(3, p.stopMs); stmt.bindString(4, p.title)
                    p.description?.takeIf { retainDescriptions }?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
                    p.category?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                    p.episode?.let { stmt.bindString(7, it) } ?: stmt.bindNull(7)
                    p.icon?.let { stmt.bindString(8, it) } ?: stmt.bindNull(8)
                    p.year?.let { stmt.bindLong(9, it.toLong()) } ?: stmt.bindNull(9)
                    if (!retainDescriptions || p.people.isEmpty()) stmt.bindNull(10) else stmt.bindString(10, p.people.joinToString("\u001F"))
                    stmt.executeInsert()
                }
            }
            auto.forEach { (ch, a) ->
                db.insertWithOnConflict("auto", null, ContentValues().apply {
                    put("ch", ch); put("source", a.sourceId); put("xmltv", a.xmltvId)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS programs_ch_start ON programs(ch, start)")
            db.execSQL("CREATE INDEX IF NOT EXISTS programs_stop ON programs(stop)")
            db.execSQL("CREATE INDEX IF NOT EXISTS programs_start ON programs(start)")
            putFingerprint(db, fingerprint, parts)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Fill gaps in the replacement from aired programmes still inside the retention window. */
    private suspend fun carryGuideHistory(
        db: SQLiteDatabase,
        fromMs: Long,
        airedByMs: Long,
        archiveOnlyChannels: Set<String>,
        newParts: String?,
        checkpoint: suspend () -> Unit
    ) {
        // Prior releases only recorded the source and XMLTV ID for automatic assignments.
        val hasSource = db.rawQuery("PRAGMA table_info(schedule_channels)", null).use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(name) else null }.any { it == "source" }
        }
        val oldAssignments = if (hasSource) {
            "WHERE o.source=n.source AND o.xmltv=n.xmltv"
        } else {
            // For legacy hand-picked assignments there was no auto row. The fingerprint's
            // base includes the override hash, so only reuse them when it still matches.
            fun baseOf(parts: String?) = parts?.let { runCatching { org.json.JSONObject(it).getString("base") }.getOrNull() }
            val sameBase = baseOf(newParts)?.let { it == baseOf(parts()) } == true
            "LEFT JOIN auto a ON a.ch=o.ch WHERE (a.source=n.source AND a.xmltv=n.xmltv)" +
                if (sameBase) " OR a.ch IS NULL" else ""
        }
        val shared = db.rawQuery(
            "SELECT DISTINCT o.ref,n.ref FROM channels_next n JOIN schedule_channels o ON o.ch=n.ch $oldAssignments", null
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) } }

        // Xtream archive rows belong to an individual channel, even when multiple variants
        // share an XMLTV schedule. Never publish one variant's archive under all its aliases.
        val direct = db.rawQuery(
            "SELECT DISTINCT p.ch,n.ref FROM programs p LEFT JOIN channels_next n ON n.ch=p.ch " +
                "LEFT JOIN auto a ON a.ch=p.ch WHERE p.shared=0 AND p.stop>? AND p.stop<=? " +
                "AND (a.source IS NULL OR (a.source=n.source AND a.xmltv=n.xmltv)) " +
                "AND NOT EXISTS (SELECT 1 FROM guide_next f WHERE f.ch=n.ref AND f.shared=1 " +
                "AND f.start<p.stop AND f.stop>p.start)",
            arrayOf(fromMs.toString(), airedByMs.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val channel = cursor.getString(0)
                    val ref = if (cursor.isNull(1)) null else cursor.getString(1)
                    if (ref != null || channel in archiveOnlyChannels) add(channel to ref)
                }
            }
        }

        val description = if (retainDescriptions) "p.descr" else "NULL"
        val people = if (retainDescriptions) "p.people" else "NULL"
        fun copy(old: String, destination: String, sharedRow: Int) {
            db.execSQL(
                "INSERT INTO guide_next(ch,start,stop,title,descr,cat,ep,icon,year,people,shared) " +
                "SELECT ?,p.start,p.stop,p.title,$description,p.cat,p.ep,p.icon,p.year,$people,? " +
                    "FROM programs p WHERE p.ch=? AND p.shared=? AND p.stop>? AND p.stop<=? " +
                    "AND NOT (${GuideGapTitles.predicate("p.title")}) " +
                    "AND NOT EXISTS (SELECT 1 FROM guide_next n WHERE n.ch=? AND n.shared=? " +
                    "AND n.start<p.stop AND n.stop>p.start)",
                arrayOf<Any>(destination, sharedRow, old, sharedRow, fromMs, airedByMs, destination, sharedRow)
            )
        }
        suspend fun copyRoutes(routes: List<Pair<String, String?>>, isShared: Boolean) {
            for (batch in routes.chunked(32)) {
                checkpoint()
                db.beginTransaction()
                try {
                    for ((old, ref) in batch) {
                        if (isShared) {
                            copy(old, requireNotNull(ref), 1)
                        } else {
                            // A detached channel may have different archive titles from its
                            // aliases. Keep it private until its extra history has expired.
                            if (ref != null) {
                                db.execSQL("INSERT INTO guide_next SELECT ?,start,stop,title,descr,cat,ep,icon,year,people,0 " +
                                    "FROM guide_next WHERE ch=? AND shared=1", arrayOf(old, ref))
                                db.delete("channels_next", "ch=?", arrayOf(old))
                            }
                            copy(old, old, 0)
                        }
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
        }
        copyRoutes(direct, isShared = false)
        copyRoutes(shared, isShared = true)
    }

    /** Build a private replacement in bounded transactions, then publish it atomically. */
    suspend fun replaceAllFromStageBatched(
        fingerprint: String, choice: Map<String, EpgAssignment>, auto: Map<String, EpgAssignment>,
        parts: String?, fromMs: Long, toMs: Long,
        airedByMs: Long = System.currentTimeMillis(), archiveOnlyChannels: Set<String> = emptySet(),
        checkpoint: suspend () -> Unit
    ) {
        val db = writableDatabase
        fun transaction(block: () -> Unit) {
            db.beginTransaction()
            try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
        }
        fun cleanup() {
            transaction {
                db.execSQL("DROP TABLE IF EXISTS guide_search_next")
                db.execSQL("DROP TABLE IF EXISTS guide_next")
                db.execSQL("DROP TABLE IF EXISTS guide_choice")
                db.execSQL("DROP TABLE IF EXISTS auto_next")
                db.execSQL("DROP TABLE IF EXISTS channels_next")
            }
        }
        try {
        cleanup()
        db.execSQL("CREATE TABLE guide_next ($PROGRAM_COLUMNS)")
        db.execSQL("CREATE TABLE guide_choice (src TEXT NOT NULL, xid TEXT NOT NULL, ch TEXT NOT NULL, PRIMARY KEY(src,xid))")
        db.execSQL("CREATE TABLE auto_next (ch TEXT PRIMARY KEY, source TEXT NOT NULL, xmltv TEXT NOT NULL)")
        db.execSQL("CREATE TABLE channels_next (ch TEXT PRIMARY KEY, ref TEXT NOT NULL, source TEXT NOT NULL, xmltv TEXT NOT NULL)")
        // Alternate index names so building the replacement never removes the live guide's indexes.
        val prefix = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='index' AND name='programs_ch_start'", null).use {
            if (it.moveToFirst()) "next_programs" else "programs"
        }
            db.execSQL("CREATE INDEX ${prefix}_ch_start ON guide_next(ch,start)")
            db.execSQL("CREATE INDEX ${prefix}_stop ON guide_next(stop)")
            db.execSQL("CREATE INDEX ${prefix}_start ON guide_next(start)")
            val representatives = HashMap<EpgAssignment, String>()
            for (batch in choice.asSequence().chunked(512)) {
                checkpoint()
                transaction {
                    db.compileStatement("INSERT INTO guide_choice VALUES (?,?,?)").use { schedule ->
                        db.compileStatement("INSERT INTO channels_next VALUES (?,?,?,?)").use { channel ->
                            for ((ch, assignment) in batch) {
                                val ref = representatives.getOrPut(assignment) {
                                    schedule.bindString(1, assignment.sourceId)
                                    schedule.bindString(2, assignment.xmltvId)
                                    schedule.bindString(3, ch)
                                    schedule.executeInsert()
                                    ch
                                }
                                channel.bindString(1, ch)
                                channel.bindString(2, ref)
                                channel.bindString(3, assignment.sourceId)
                                channel.bindString(4, assignment.xmltvId)
                                channel.executeInsert()
                            }
                        }
                    }
                }
            }
            val maxRow = db.rawQuery("SELECT COALESCE(MAX(rowid),0) FROM stage", null).use { it.moveToFirst(); it.getLong(0) }
            var row = 0L
            while (row < maxRow) {
                checkpoint()
                transaction {
                    db.execSQL("INSERT INTO guide_next (ch,start,stop,title,descr,cat,ep,icon,year,people,shared) " +
                        "SELECT c.ch,s.start,s.stop,s.title,${if (retainDescriptions) "s.descr" else "NULL"},s.cat,s.ep,s.icon,s.year,${if (retainDescriptions) "s.people" else "NULL"},1 " +
                        "FROM stage s CROSS JOIN guide_choice c ON c.src=s.src AND c.xid=s.xid " +
                        "WHERE s.rowid>? AND s.rowid<=? AND s.stop>? AND s.start<? " +
                        "AND NOT (${GuideGapTitles.predicate("s.title")})",
                        arrayOf<Any>(row, row + 1024, fromMs, toMs))
                }
                row += 1024
            }
            carryGuideHistory(db, fromMs, minOf(airedByMs, toMs), archiveOnlyChannels, parts, checkpoint)
            for (batch in auto.asSequence().chunked(512)) {
                checkpoint()
                transaction {
                    batch.forEach { (ch,a) -> db.insertOrThrow("auto_next",null,ContentValues().apply {
                        put("ch",ch); put("source",a.sourceId); put("xmltv",a.xmltvId)
                    }) }
                }
            }
            // Build postings alongside the private replacement, with the same safe checkpoints.
            db.execSQL("CREATE VIRTUAL TABLE guide_search_next USING fts4(title,descr,content='programs',tokenize=unicode61)")
            val lastProgram = db.rawQuery("SELECT COALESCE(MAX(rowid),0) FROM guide_next", null)
                .use { it.moveToFirst(); it.getLong(0) }
            var indexedRow = 0L
            while (indexedRow < lastProgram) {
                checkpoint()
                transaction {
                    db.execSQL("INSERT INTO guide_search_next(docid,title,descr) " +
                        "SELECT rowid,title,descr FROM guide_next WHERE rowid>? AND rowid<=?",
                        arrayOf<Any>(indexedRow, indexedRow + 1024))
                }
                indexedRow += 1024
            }
            checkpoint()
            transaction {
                db.execSQL("DROP TABLE schedule_channels")
                db.execSQL("ALTER TABLE channels_next RENAME TO schedule_channels")
                db.execSQL("CREATE INDEX schedule_ref ON schedule_channels(ref)")
                db.execSQL("DROP TABLE programs")
                db.execSQL("ALTER TABLE guide_next RENAME TO programs")
                db.execSQL("DROP TABLE program_terms")
                db.execSQL("DROP TABLE program_search")
                db.execSQL("ALTER TABLE guide_search_next RENAME TO program_search")
                ensureSearchIndex(db, rebuild = false)
                db.execSQL("DROP TABLE auto")
                db.execSQL("ALTER TABLE auto_next RENAME TO auto")
                putFingerprint(db,fingerprint,parts)
            }
        } catch (error: Throwable) {
            try {
                // The failed copy owns only the replacement tables. Retry from the same
                // completed source rows, including after the process restarts.
                cleanup()
                SqliteCompact.compactIfWasteful(db,"interrupted guide")
            } catch (cleanupError: Throwable) { error.addSuppressed(cleanupError) }
            throw error
        }
        cleanup()
    }

    /** Gives back space left empty by deleted listings (see [SqliteCompact]). */
    fun compact(): Long {
        val db = writableDatabase
        val cleaned = db.rawQuery("SELECT 1 FROM meta WHERE k='guide_gap_rows_removed'", null).use { it.moveToFirst() }
        if (!cleaned) {
            // Idle or manual maintenance. Bounded deletes allow WAL checkpoints on space-constrained TVs.
            for (table in listOf("programs", "stage")) {
                var after = 0L
                val missing = GuideGapTitles.predicate("title")
                while (true) {
                    val last = db.rawQuery("SELECT MAX(rowid) FROM (SELECT rowid FROM $table " +
                        "WHERE rowid>? AND $missing ORDER BY rowid LIMIT 1000)", arrayOf(after.toString())).use {
                        it.moveToFirst()
                        if (it.isNull(0)) null else it.getLong(0)
                    } ?: break
                    db.delete(table, "rowid>? AND rowid<=? AND $missing", arrayOf(after.toString(), last.toString()))
                    after = last
                }
            }
            db.transaction {
                bumpContentRevision(db)
                db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES ('guide_gap_rows_removed','1')")
            }
        }
        return SqliteCompact.compactIfWasteful(db, "guide")
    }

    fun clear() {
        val db = writableDatabase
        db.delete("schedule_channels", null, null)
        db.delete("programs", null, null)
        db.delete("auto", null, null)
        db.delete("meta", null, null)
        db.delete("search_words", null, null)
        runCatching { db.delete("stage", null, null); db.delete("stage_meta", null, null); db.delete("stage_channels", null, null); db.delete("stage_info", null, null) }
    }
}


private val STAGING_TABLES = listOf("stage", "stage_meta", "stage_channels", "stage_info")

/** Longest single listing assumed when reading a time window (longer ones are very rare). */
private const val MAX_SHOW_MS = 6L * 60 * 60 * 1000

private const val PROGRAM_COLUMNS = "ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL, " +
    "descr TEXT, cat TEXT, ep TEXT, icon TEXT, year INTEGER, people TEXT, shared INTEGER NOT NULL DEFAULT 0"

// Old installations and per-channel overrides have direct rows. Each shared XMLTV listing
// is exposed under every playlist channel assigned to its schedule.
private const val GUIDE_LISTINGS = "(SELECT ch,start,stop,title,descr,cat,ep,icon,year,people FROM programs WHERE shared=0 " +
    "UNION ALL SELECT a.ch,p.start,p.stop,p.title,p.descr,p.cat,p.ep,p.icon,p.year,p.people " +
    "FROM schedule_channels a JOIN programs p ON p.ch=a.ref AND p.shared=1)"
