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
}
