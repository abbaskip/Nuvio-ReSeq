package com.nuvio.tv.livetv.ondemand

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nuvio.tv.livetv.data.BackgroundWork
import com.nuvio.tv.livetv.data.M3uEntry
import com.nuvio.tv.livetv.data.M3uParser
import com.nuvio.tv.livetv.data.StalkerPortalClient
import com.nuvio.tv.livetv.model.PlaylistSource
import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/** Rebuildable private link index. Older releases can open their existing v11 catalogue in place. */
internal class M3uVodStore(private val context: Context) :
    SQLiteOpenHelper(context, "m3u-vod-links.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sources (pl TEXT PRIMARY KEY, config TEXT NOT NULL, stamp TEXT,
            generation TEXT, catalogStamp TEXT, server TEXT, username TEXT, password TEXT)""")
        db.execSQL("""CREATE TABLE links (pl TEXT NOT NULL, gen TEXT NOT NULL, item TEXT NOT NULL,
            entry TEXT NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, cat TEXT NOT NULL, ext TEXT NOT NULL,
            season INTEGER NOT NULL, episode INTEGER NOT NULL, title TEXT NOT NULL, icon TEXT, url TEXT NOT NULL,
            headers TEXT, PRIMARY KEY(pl,gen,entry))""")
        db.execSQL("CREATE INDEX titles ON links(pl,gen,kind,item,season,episode)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun file(pl: PlaylistSource): File {
        val filename = "${pl.id}.m3u"
        return com.nuvio.tv.livetv.data.preferredSourceFile(File(context.cacheDir, "livetv_sources/playlists/$filename"),
            File(context.filesDir, "livetv/playlists/$filename"))
    }

    private fun config(pl: PlaylistSource) = m3uDigest(pl.url, pl.userAgent)
    fun revision(pl: PlaylistSource) = m3uDigest(config(pl), stamp(pl))

    fun due(pl: PlaylistSource): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM sources WHERE pl=? AND config=? AND catalogStamp=?", arrayOf(pl.id, config(pl), revision(pl))
    ).use { !it.moveToFirst() }

    fun complete(pl: PlaylistSource, revision: String) {
        writableDatabase.execSQL("UPDATE sources SET catalogStamp=? WHERE pl=? AND config=?", arrayOf(revision, pl.id, config(pl)))
    }

    fun stamp(pl: PlaylistSource): String {
        val file = file(pl)
        if (!file.exists() || !belongsTo(file, pl)) return ""
        return m3uDigest(config(pl), runCatching { File(file.parentFile, file.name + ".stamp").readText().trim() }.getOrNull()?.takeIf(String::isNotEmpty)
            ?: "${file.length()}-${file.lastModified()}")
    }

    private fun reader(file: File): java.io.BufferedReader {
        val input = file.inputStream().buffered(64 * 1024)
        try {
            input.mark(2)
            val zipped = input.read() == 0x1f && input.read() == 0x8b
            input.reset()
            return (if (zipped) GZIPInputStream(input, 64 * 1024) else input).reader(Charsets.UTF_8).buffered(64 * 1024)
        } catch (error: Exception) { input.close(); throw error }
    }

    fun login(pl: PlaylistSource): M3uLogin? =
        readableDatabase.rawQuery("SELECT server,username,password FROM sources WHERE pl=? AND config=? AND server IS NOT NULL",
            arrayOf(pl.id, config(pl))).use { if (it.moveToFirst()) M3uLogin(it.getString(0), it.getString(1), it.getString(2)) else null }

    fun rememberLogin(pl: PlaylistSource, login: M3uLogin?) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO sources(pl,config) VALUES(?,?)", arrayOf(pl.id, config(pl)))
        db.execSQL("UPDATE sources SET generation=CASE WHEN config=? THEN generation END, config=?, server=?, username=?, password=? WHERE pl=?",
            arrayOf(config(pl), config(pl), login?.server, login?.username, login?.password, pl.id))
    }

    suspend fun acceptedLogin(pl: PlaylistSource, accepts: (M3uLogin) -> Boolean): M3uLogin? {
        M3uLogin.direct(pl)?.let { return it.takeIf(accepts) }
        val file = file(pl)
        if (!file.isFile || !belongsTo(file, pl)) return null
        return reader(file).use { input ->
            val tried = HashSet<String>()
            var scanned = 0
            for (raw in input.lineSequence()) {
                if (++scanned % 512 == 0) { BackgroundWork.awaitIdle(); BackgroundWork.yieldToNavigation() }
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '#') continue
                val candidate = M3uLogin.channel(line.substringBefore('|').trim()) ?: continue
                if (tried.add(candidate.key())) {
                    if (accepts(candidate)) return@use candidate
                    if (tried.size >= 3) break
                }
            }
            null
        }
    }

    private fun belongsTo(file: File, pl: PlaylistSource): Boolean {
        val origin = File(file.parentFile, file.name + ".vod-origin")
        return !origin.exists() || runCatching { origin.readText() == config(pl) }.getOrDefault(false)
    }

    fun current(pl: PlaylistSource): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM sources WHERE pl=? AND config=? AND generation IS NOT NULL",
        arrayOf(pl.id, config(pl))).use { it.moveToFirst() }

    fun indexed(pl: PlaylistSource, stamp: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM sources WHERE pl=? AND config=? AND stamp=? AND generation IS NOT NULL",
        arrayOf(pl.id, config(pl), stamp)).use { it.moveToFirst() }

    private val active = "gen=(SELECT generation FROM sources WHERE pl=?1)"
    private fun SQLiteDatabase.transaction(block: () -> Unit) {
        beginTransactionNonExclusive()
        try { block(); setTransactionSuccessful() } finally { endTransaction() }
    }

    /** Keep stale generations unreachable immediately. Reclaim their pages in small, interruptible batches. */
    private fun prune(db: SQLiteDatabase, pl: String) {
        if (com.nuvio.tv.core.player.PlayerSessionTracker.hasBulkPlayback) return
        val obsolete = db.rawQuery("""SELECT DISTINCT gen FROM links WHERE pl=? AND
            gen != COALESCE((SELECT generation FROM sources WHERE pl=?1),'')""", arrayOf(pl)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        for (generation in obsolete) {
            while (!com.nuvio.tv.core.player.PlayerSessionTracker.hasBulkPlayback) {
                val count = db.delete("links", "rowid IN (SELECT rowid FROM links WHERE pl=? AND gen=? LIMIT 256)",
                    arrayOf(pl, generation))
                if (count < 256) break
                BackgroundWork.yieldToNavigation()
            }
        }
    }

    /** The complete file is read once. Only the generation whose final transaction committed is visible. */
    fun index(pl: PlaylistSource) {
        val stamp = stamp(pl)
        if (stamp.isEmpty()) throw java.io.FileNotFoundException("Playlist download pending")
        if (indexed(pl, stamp)) return
        val db = writableDatabase
        val gen = java.util.UUID.randomUUID().toString()
        prune(db, pl.id)
        val rows = ArrayList<Pair<M3uTitle, M3uEntry>>(128)
        try {
            db.compileStatement("""INSERT OR REPLACE INTO links(pl,gen,item,entry,kind,name,cat,ext,season,episode,title,icon,url,headers)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""").use { stmt ->
                fun write() {
                    if (rows.isEmpty()) return
                    BackgroundWork.importCheckpoint()
                    db.transaction {
                        for ((item, entry) in rows) {
                            stmt.clearBindings()
                            stmt.bindString(1, pl.id); stmt.bindString(2, gen); stmt.bindString(3, item.id)
                            stmt.bindString(4, item.entryId); stmt.bindString(5, item.kind.key); stmt.bindString(6, item.name)
                            stmt.bindString(7, item.group); stmt.bindString(8, item.ext)
                            stmt.bindLong(9, (item.season ?: -1).toLong())
                            val number = item.episode ?: if (item.season == null) -1 else db.rawQuery(
                                "SELECT COALESCE(MAX(episode),0)+1 FROM links WHERE pl=? AND gen=? AND kind=? AND item=? AND season=?",
                                arrayOf(pl.id, gen, item.kind.key, item.id, item.season.toString())).use { it.moveToFirst(); it.getInt(0) }
                            stmt.bindLong(10, number.toLong()); stmt.bindString(11, entry.name)
                            entry.logo?.let { stmt.bindString(12, it) }
                            stmt.bindString(13, entry.url)
                            if (entry.headers.isNotEmpty()) stmt.bindString(14, JSONObject(entry.headers).toString())
                            stmt.executeInsert()
                        }
                    }
                    rows.clear()
                    BackgroundWork.yieldToNavigation()
                }
                reader(file(pl)).use { input ->
                    var read = 0
                    M3uParser.parse(input) { entry ->
                        if (++read % 128 == 0) BackgroundWork.importCheckpoint()
                        M3uTitle.from(entry)?.let { rows.add(it to entry) }
                        if (rows.size == 128) write()
                    }
                    write()
                }
            }
            check(stamp == stamp(pl)) { "Playlist changed during import" }
            db.transaction {
                db.execSQL("INSERT OR IGNORE INTO sources(pl,config) VALUES(?,?)", arrayOf(pl.id, config(pl)))
                db.execSQL("UPDATE sources SET config=?, stamp=?, generation=? WHERE pl=?", arrayOf(config(pl), stamp, gen, pl.id))
            }
        } finally {
            prune(db, pl.id)
        }
    }

    fun categories(pl: PlaylistSource, kind: VodKind): List<Pair<String, String>> = readableDatabase.rawQuery(
        "SELECT cat FROM links WHERE pl=? AND $active AND kind=? GROUP BY cat ORDER BY MIN(rowid)", arrayOf(pl.id, kind.key)).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(0)) }
    }

    fun items(pl: PlaylistSource, kind: VodKind, emit: (VodItem) -> Unit) {
        val selected = pl.vodCategories(kind == VodKind.SERIES)
        readableDatabase.rawQuery("""SELECT item,name,cat,icon,ext,MIN(rowid) FROM links WHERE pl=? AND $active AND kind=?
            GROUP BY item ORDER BY MIN(rowid)""", arrayOf(pl.id, kind.key)).use { c ->
            while (c.moveToNext()) {
                val cat = c.getString(2)
                if (selected != null && cat !in selected) continue
                val name = c.getString(1)
                emit(VodItem(pl.id, kind, c.getString(0), name, if (pl.vodSourceOnly) null else c.getString(3),
                    cat, if (kind == VodKind.MOVIE) c.getString(4) else null, null, OnDemandDatabase.releaseYearInName(name), null, 0L))
            }
        }
    }

    fun artwork(item: VodItem): String? = readableDatabase.rawQuery(
        "SELECT icon FROM links WHERE pl=? AND $active AND kind=? AND item=? AND icon IS NOT NULL LIMIT 1",
        arrayOf(item.playlistId, item.kind.key, item.id)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun info(item: VodItem): VodInfo {
        val episodes = if (item.kind == VodKind.MOVIE) emptyList() else readableDatabase.rawQuery(
            "SELECT season,episode,entry,ext,title,icon FROM links WHERE pl=? AND $active AND kind=? AND item=? ORDER BY season,episode,rowid",
            arrayOf(item.playlistId, item.kind.key, item.id)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val season = c.getInt(0); val number = c.getInt(1); val entry = c.getString(2)
                    add(VodEpisode(season, number, entry, c.getString(3), c.getString(4), null, c.getString(5),
                        StalkerVod.marker(item, season, number, entry, m3u = true)))
                }
            }
        }
        return VodInfo(null, item.icon, null, item.genre, item.year?.toString(), null, episodes)
    }

    fun resolve(pl: PlaylistSource, item: VodItem, season: Int?, episode: Int?, entry: String?): StalkerPortalClient.Link? {
        if (!current(pl)) return null
        val clause = if (entry == null) "" else " AND entry=?"
        val args = listOf(pl.id, item.kind.key, item.id, (season ?: -1).toString(), (episode ?: -1).toString()) + listOfNotNull(entry)
        return readableDatabase.rawQuery("SELECT url,headers FROM links WHERE pl=? AND $active AND kind=? AND item=? AND season=? AND episode=?$clause ORDER BY rowid LIMIT 1",
            args.toTypedArray()).use { c ->
            if (!c.moveToFirst()) return@use null
            val headers = buildMap {
                if (pl.userAgent.isNotBlank()) put("User-Agent", pl.userAgent)
                c.getString(1)?.let { raw -> val obj = JSONObject(raw); obj.keys().forEach { put(it, obj.getString(it)) } }
            }
            StalkerPortalClient.Link(c.getString(0), headers)
        }
    }

    fun retain(ids: Set<String>) {
        if (!context.getDatabasePath(databaseName).exists()) return
        val db = writableDatabase
        val sources = db.rawQuery("SELECT pl FROM sources", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        for (pl in sources) {
            if (pl in ids) continue
            db.execSQL("UPDATE sources SET generation=NULL, stamp=NULL, catalogStamp=NULL, server=NULL, username=NULL, password=NULL WHERE pl=?", arrayOf(pl))
            prune(db, pl)
            val remaining = db.rawQuery("SELECT 1 FROM links WHERE pl=? LIMIT 1", arrayOf(pl)).use { it.moveToFirst() }
            if (!remaining) db.delete("sources", "pl=?", arrayOf(pl))
        }
    }
}
