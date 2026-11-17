package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class EpgSearchIndexTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = EpgDatabase(context)

    @After fun close() { db.close(); context.deleteDatabase("livetv_guide.db") }
    @Test fun `full disk still reads the committed guide without a postings disk cache`() {
        db.writableDatabase.disableWriteAheadLogging()
        db.replaceAll("full",(0..5000).associate { "c$it" to listOf(EpgProgram(100,300,"Cricket final")) },emptyMap())
        val pages=db.writableDatabase.rawQuery("PRAGMA page_count",null).use { it.moveToFirst();it.getLong(0) }
        db.writableDatabase.rawQuery("PRAGMA max_page_count=${pages+1}",null).use { it.moveToFirst() }
        var found=0
        db.visitSportsPrograms(0,200,0,listOf("cricket")) { _,_ -> found++ }
        assertEquals(5001,found)
        assertEquals("full",db.fingerprint())
        assertEquals(0,db.readableDatabase.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('sports_search_sets','sports_search_rows')",null).use { it.moveToFirst();it.getInt(0) })
        db.writableDatabase.rawQuery("PRAGMA max_page_count=2147483647",null).use { it.moveToFirst() }
    }
    @Test fun `a committed guide revision change aborts paged discovery before reading a mixed generation`() {
        db.replaceAll("pages",(0..8000).associate { "c$it" to listOf(EpgProgram(100,300,"Cricket final")) },emptyMap())
        var seen=0
        val error=runCatching {
            db.visitSportsPrograms(0,200,0,listOf("cricket")) { _,_ ->
                if(++seen==1) db.writableDatabase.execSQL("UPDATE meta SET v='new-committed-revision' WHERE k='content_revision'")
            }
        }.exceptionOrNull()
        assertTrue(error.toString(),error is kotlinx.coroutines.CancellationException)
        assertTrue(seen in 1..4000)
    }
    @Test fun `bounded index pages cross direct schedules and shared alias fanout without gaps or reordered results`() {
        val rows=(0..4100).associate { "direct$it" to listOf(EpgProgram(100,300,"Cricket final $it")) }+
            mapOf("shared" to listOf(EpgProgram(100,300,"Cricket combined")))
        db.replaceAll("pages",rows,emptyMap())
        val sql=db.writableDatabase
        sql.execSQL("UPDATE programs SET shared=1 WHERE ch='shared'")
        sql.beginTransaction()
        try {
            sql.compileStatement("INSERT INTO schedule_channels VALUES(?,'shared')").use { insert ->
                for(i in 0..9000) { insert.bindString(1,"alias$i");insert.executeInsert() }
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        val expected=sql.rawQuery("WITH hits AS (SELECT docid FROM program_search WHERE program_search MATCH 'cricket') " +
            "SELECT CASE WHEN p.shared=0 THEN p.ch ELSE a.ch END FROM hits h JOIN programs p ON p.rowid=h.docid " +
            "LEFT JOIN schedule_channels a ON p.shared=1 AND a.ref=p.ch WHERE p.shared=0 OR a.ch IS NOT NULL",null).use { c ->
            buildList { while(c.moveToNext()) add(c.getString(0)) }
        }
        val actual=ArrayList<String>()
        db.visitSportsPrograms(0,200,0,listOf("cricket")) { ch,_ -> actual+=ch }
        assertEquals(13_102,actual.size)
        assertEquals(actual.size,actual.toSet().size)
        assertEquals(expected,actual)
    }
    @Test fun `unassigned membership matches the full window without retaining programme payloads`() {
        db.replaceAll("membership",mapOf("direct" to listOf(EpgProgram(100,300,"Present")),
            "shared" to listOf(EpgProgram(100,300,"Also present")),
            "past" to listOf(EpgProgram(1,20,"Expired")),"future" to listOf(EpgProgram(500,600,"Later"))),emptyMap())
        db.writableDatabase.execSQL("UPDATE programs SET shared=1 WHERE ch='shared'")
        db.writableDatabase.execSQL("INSERT INTO schedule_channels VALUES('alias','shared')")
        assertEquals(setOf("direct","alias"),db.channelsWithListings(90,400))
        assertEquals(db.range(90,400).keys,db.channelsWithListings(90,400))
    }

    @Test fun `matching reads the committed dictionary without decoding FTS auxiliary postings`() {
        db.replaceAll("one",mapOf("c" to listOf(EpgProgram(100,300,"Live: Cricket final"))),emptyMap())
        db.writableDatabase.execSQL("DROP TABLE program_terms")
        assertEquals("c",db.findByWords(0,500,0,listOf("cricket")).single().first)
        val found=ArrayList<String>()
        db.visitSportsPrograms(0,500,0,listOf("cricket")) { ch,_ -> found+=ch }
        assertEquals(listOf("c"),found)
    }

    @Test fun `version six gains a committed dictionary and later replacements remove obsolete words`() {
        db.replaceAll("one",mapOf("c" to listOf(EpgProgram(100,300,"Cricket final"))),emptyMap())
        db.writableDatabase.execSQL("DROP TABLE search_words")
        db.writableDatabase.version=6
        db.close()
        val migrated=EpgDatabase(context)
        try {
            assertEquals("c",migrated.findByWords(0,500,0,listOf("cricket")).single().first)
            migrated.replaceAll("two",mapOf("d" to listOf(EpgProgram(100,300,"Football final"))),emptyMap())
            assertTrue(migrated.findByWords(0,500,0,listOf("cricket")).isEmpty())
            assertEquals("d",migrated.findByWords(0,500,0,listOf("football")).single().first)
        } finally { migrated.close() }
    }

    @Test fun `upgrading version four backfills the index without losing the committed guide`() {
        db.replaceAll("old", mapOf("old" to listOf(EpgProgram(100,300,"Old event"))), emptyMap())
        for (name in listOf("insert", "delete", "update_before", "update_after"))
            db.writableDatabase.execSQL("DROP TRIGGER program_search_$name")
        db.writableDatabase.execSQL("DROP TABLE program_terms")
        db.writableDatabase.execSQL("DROP TABLE program_search")
        db.writableDatabase.version = 4
        db.close()
        val migrated = EpgDatabase(context)
        try {
            assertEquals("old", migrated.fingerprint())
            assertEquals("old", migrated.findByWords(0,500,0,listOf("Old event")).single().first)
        } finally { migrated.close() }
    }

    @Test fun `staged index and shared schedule publish atomically`() = kotlinx.coroutines.runBlocking {
        db.replaceAll("old", mapOf("old" to listOf(EpgProgram(100,300,"Old event"))), emptyMap())
        val writer = db.startStage("source")
        writer.add("channel", listOf(EpgProgram(100,300,"Cricket Final"))) { false }
        writer.finishBatched("stamp", listOf(Triple("channel",listOf("Fixture"),null))) { }
        val assignment = com.nuvio.tv.livetv.model.EpgAssignment("source","channel")
        db.replaceAllFromStageBatched("new", mapOf("alias" to assignment), emptyMap(), null, 0,500) {
            assertEquals("old",db.findByWords(0,500,0,listOf("Old event")).single().first)
        }
        assertEquals("new",db.fingerprint())
        assertTrue(db.findByWords(0,500,0,listOf("Old event")).isEmpty())
        assertEquals("alias",db.findByWords(0,500,0,listOf("Cricket Final")).single().first)
    }

    @Test fun `index preserves capped traversal with direct and shared schedules`() {
        val rows = (0..39).associate { i -> "ch$i" to listOf(
            EpgProgram(((i * 31) % 200).toLong(), 400L + i, "Cricket $i")) }
        db.replaceAll("fixture", rows, emptyMap())
        db.writableDatabase.execSQL("UPDATE programs SET shared=1 WHERE ch='ch3'")
        db.writableDatabase.execSQL("INSERT INTO schedule_channels VALUES('alias1','ch3'),('alias2','ch3')")
        val listings = "(SELECT ch,start,stop,title,descr,cat FROM programs WHERE shared=0 UNION ALL " +
            "SELECT a.ch,p.start,p.stop,p.title,p.descr,p.cat FROM schedule_channels a JOIN programs p ON p.ch=a.ref AND p.shared=1)"
        for (limit in listOf(1, 7, 1000)) {
            val expected = db.readableDatabase.rawQuery("SELECT ch,start FROM $listings " +
                "WHERE start>=? AND start<? AND stop>? AND (title LIKE ? ESCAPE '\\') LIMIT $limit",
                arrayOf("0", "300", "0", "%cricket%")).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) }
            }
            assertEquals(expected, db.findByWords(0,300,0,listOf("cricket"),false,limit)
                .map { it.first to it.second.startMs })
        }
    }

    @Test fun `indexed search preserves literal substring phrase description and unicode results`() {
        val programmes = listOf(
            "Australia vs England", "English Premier League", "Women's Super League",
            "100% cricket_a", "Équipe française", "Rugby-League highlights", "Golden Point Thrillers"
        ).mapIndexed { i, title -> EpgProgram(100L + i, 300L, title, "Sky Sports presents $title") }
        db.replaceAll("first", programmes.mapIndexed { i, p -> "ch$i" to listOf(p) }.toMap(), emptyMap())
        for (description in listOf(false, true)) for (word in listOf("England", "nglish", "Sky Sports", "100%", "cricket_a", "Équipe", "Rugby-League", "missing")) {
            fun like(value: String) = "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            val where = if (description) "(title LIKE ? ESCAPE '\\' OR descr LIKE ? ESCAPE '\\')" else "title LIKE ? ESCAPE '\\'"
            val args = if (description) arrayOf(like(word), like(word)) else arrayOf(like(word))
            val expected = db.readableDatabase.rawQuery("SELECT ch FROM programs WHERE $where", args).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            assertEquals("$word, description=$description", expected,
                db.findByWords(0, 200, 0, listOf(word), description).map { it.first }.toSet())
        }
        db.replaceChannels("second", setOf("ch0"), mapOf("ch0" to listOf(EpgProgram(110, 300, "New event"))), emptyMap())
        assertTrue(db.findByWords(0, 200, 0, listOf("Australia")).isEmpty())
        assertEquals("ch0", db.findByWords(0, 200, 0, listOf("New event")).single().first)
    }

    @Test fun `indexed current discovery folds accents and expands shared aliases without unrelated rows`() {
        db.replaceAll("sports",mapOf("direct" to listOf(EpgProgram(100,300,"Équipe Française: Rugby")),
            "shared" to listOf(EpgProgram(100,300,"Women's Super League")),
            "food" to listOf(EpgProgram(100,300,"MasterChef"))),emptyMap())
        db.writableDatabase.execSQL("UPDATE programs SET shared=1 WHERE ch='shared'")
        db.writableDatabase.execSQL("INSERT INTO schedule_channels VALUES('alias','shared')")
        val found=mutableListOf<Pair<String,EpgProgram>>()
        db.visitSportsPrograms(0,200,200,listOf("equipe francaise","Women's Super League")) { ch,p -> found+=ch to p }
        assertEquals(setOf("direct","alias"),found.map { it.first }.toSet())
        assertTrue(found.none { it.first=="food" })
    }
    @Test fun `matching stream retains the published candidate eligibility limit`() {
        db.replaceAll("sports",(0..4100).associate { "ch$it" to listOf(EpgProgram(100,300,"Cricket Final $it")) },emptyMap())
        val found=mutableListOf<Pair<String,EpgProgram>>()
        db.visitMatchPrograms(0,200,0,listOf("cricket")) { ch,p -> found+=ch to p }
        assertEquals(4000,found.size)
        assertEquals(db.findByWords(0,200,0,listOf("cricket")).toSet(),found.toSet())
    }

    @Test fun `reduced taxonomy vocabulary returns identical postings to the complete disjunction`() {
        val words=com.nuvio.tv.livetv.sports.SportsTaxonomy.nodes.flatMap { it.aliases.map { a -> a.term } }.distinct()
        val programmes=words.mapIndexed { n,word -> "ch$n" to listOf(EpgProgram(100,300,"Live: $word")) }.toMap()
        db.replaceAll("taxonomy",programmes,emptyMap())
        val complete=words.joinToString(" OR ") { "\"${it.replace("\"","\"\"")}\"" }
        val expected=db.readableDatabase.rawQuery("SELECT ch FROM programs WHERE rowid IN " +
            "(SELECT docid FROM program_search WHERE program_search MATCH ?)",arrayOf(complete)).use { c ->
            buildSet { while(c.moveToNext()) add(c.getString(0)) }
        }
        val actual=HashSet<String>()
        db.visitSportsPrograms(0,200,0,words) { ch,_ -> actual+=ch }
        assertEquals(expected,actual)
        assertTrue(SportsSearchVocabulary.minimalCover(words).size<words.size/2)
    }
    @Test fun `taxonomy index retains compatibility characters recognized by the old classifier`() {
        val title="ＮＢＡ Basketball"
        assertNotNull(com.nuvio.tv.livetv.sports.SportsTaxonomy.classifyProgramme(title,null))
        db.replaceAll("unicode",mapOf("fullwidth" to listOf(EpgProgram(100,300,title))),emptyMap())
        val actual=ArrayList<String>()
        db.visitSportsPrograms(0,200,0,listOf("nba")) { ch,_ -> actual+=ch }
        assertEquals(listOf("fullwidth"),actual)
    }
}
