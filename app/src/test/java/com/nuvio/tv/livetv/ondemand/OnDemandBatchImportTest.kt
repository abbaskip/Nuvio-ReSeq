package com.nuvio.tv.livetv.ondemand

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OnDemandBatchImportTest {
    private lateinit var db: OnDemandDatabase
    private val cats = listOf("a" to "Movies")

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_on_demand.db")
        db = OnDemandDatabase(context)
    }
    @After fun cleanup() { db.close() }

    private fun item(id: Int, name: String = "Film $id", pl: String = "test", kind: VodKind = VodKind.MOVIE) =
        VodItem(pl, kind, id.toString(), name, null, "a", "mp4", null, null, null, 0)

    @Test fun `batches are durable and network reads never hold the write transaction`() {
        var commits = 0
        db.replace("test", VodKind.MOVIE, cats, onBatchCommitted = { commits++ }) { emit ->
            repeat(56722) {
                assertFalse(db.writableDatabase.inTransaction())
                emit(item(it))
                if (it == 499) assertEquals(500, db.count(VodKind.MOVIE))
            }
            false
        }
        assertEquals(114, commits)
        assertEquals(56722, db.count(VodKind.MOVIE))
    }

    @Test fun `partial recovery merges without deleting old titles and complete refresh prunes`() {
        db.replace("test", VodKind.MOVIE, cats) { emit -> repeat(1001) { emit(item(it)) }; true }
        db.replace("other", VodKind.MOVIE, cats) { emit -> emit(item(0, pl = "other")); true }
        db.replace("test", VodKind.SERIES, cats) { emit -> emit(item(0, kind = VodKind.SERIES)); true }
        db.replace("test", VodKind.MOVIE, cats) { emit -> emit(item(0, "Changed")); emit(item(2000)); false }
        assertEquals(1003, db.count(VodKind.MOVIE))
        assertEquals(0, db.lastChanges.second)
        // Category retry sees fewer titles: it must not erase either prior result.
        db.replace("test", VodKind.MOVIE, cats) { emit -> emit(item(2001)); false }
        assertEquals(1004, db.count(VodKind.MOVIE))
        db.replace("test", VodKind.MOVIE, cats) { emit -> emit(item(2001)); emit(item(2001)); true }
        assertEquals(2, db.count(VodKind.MOVIE))
        assertEquals(0 to 1002, db.lastChanges)
        assertEquals(1, db.count(VodKind.SERIES))
    }

    @Test fun `unexpected failure retains prior commits and next refresh recovers`() {
        try {
            db.replace("test", VodKind.MOVIE, cats) { emit ->
                repeat(750) { emit(item(it)) }
                throw IllegalStateException("Simulated interruption")
            }
            fail("Expected interruption")
        } catch (_: IllegalStateException) {}
        assertEquals(500, db.count(VodKind.MOVIE))
        db.replace("test", VodKind.MOVIE, cats) { emit -> repeat(750) { emit(item(it)) }; true }
        assertEquals(750, db.count(VodKind.MOVIE))
        assertEquals(250 to 0, db.lastChanges)
    }

    @Test fun `version 9 catalog upgrades without losing titles`() {
        db.close()
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_on_demand.db")
        // Build the actual released v9 table, rather than relabel a newer storage schema as v9.
        context.openOrCreateDatabase("livetv_on_demand.db", 0, null).use { legacy ->
            legacy.execSQL("CREATE TABLE items(pl TEXT NOT NULL,kind TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,norm TEXT NOT NULL,icon TEXT,cat TEXT NOT NULL,ext TEXT,tmdb TEXT,year INTEGER,rating TEXT,added INTEGER NOT NULL DEFAULT 0,genre TEXT,h INTEGER,PRIMARY KEY(pl,kind,id))")
            legacy.execSQL("CREATE TABLE categories(pl TEXT NOT NULL,kind TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(pl,kind,id))")
            legacy.execSQL("CREATE TABLE prefixes(pl TEXT NOT NULL,prefix TEXT NOT NULL,PRIMARY KEY(pl,prefix))")
            legacy.execSQL("CREATE TABLE posters(uid TEXT PRIMARY KEY,poster TEXT,checked INTEGER NOT NULL,title TEXT)")
            legacy.execSQL("CREATE TABLE poster_fix(uid TEXT PRIMARY KEY,title TEXT NOT NULL,year INTEGER)")
            legacy.execSQL("INSERT INTO items VALUES ('test','movie','1','Film 1','film1',NULL,'a','mp4',NULL,NULL,NULL,0,NULL,NULL)")
            legacy.version = 9
        }
        db = OnDemandDatabase(context)
        db.replace("test", VodKind.MOVIE, cats) { emit -> emit(item(1)); emit(item(2)); true }
        assertEquals(2, db.count(VodKind.MOVIE))
    }
}
