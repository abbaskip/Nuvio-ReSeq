package com.nuvio.tv.core.storage

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvPosterStore
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.ondemand.OnDemandDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class DatabaseDowngradeSafetyTest {
    // SQLiteOpenHelper only implements AutoCloseable on newer Android SDKs.
    private inline fun <T : SQLiteOpenHelper, R> T.withDatabase(block: (T) -> R): R =
        try { block(this) } finally { close() }

    private val context: android.app.Application = RuntimeEnvironment.getApplication()
    @After fun clean() {
        (DatabaseVersions.all.map { it.fileName } + listOf("fault-cache.db", "exoplayer_internal.db")).forEach(context::deleteDatabase)
        java.io.File(context.filesDir, "guide-stage-in-progress").delete()
    }

    @Test fun `combined schemas retain the Stage 2 versions and mark manual corrections as user data`() {
        assertEquals(7, DatabaseVersions.guide.version)
        assertEquals(3, DatabaseVersions.posters.version)
        assertEquals(11, DatabaseVersions.onDemand.version)
        assertFalse(DatabaseVersions.onDemand.rebuildable)
    }

    @Test fun `a future guide schema rebuilds only the cache including unknown FTS tables triggers and views`() {
        OnDemandDatabase(context).withDatabase { it.setPosterFix("series:playlist:1", "My correction", 2005) }
        EpgDatabase(context).withDatabase { guide ->
            guide.replaceAll("old-guide", mapOf("channel" to listOf(EpgProgram(1, 100, "Old listing"))), emptyMap())
            guide.writableDatabase.apply {
                execSQL("CREATE VIRTUAL TABLE future_search USING fts4(title)")
                execSQL("INSERT INTO future_search(title) VALUES('future listing')")
                execSQL("CREATE TABLE future_cache (value TEXT)")
                execSQL("CREATE VIEW future_view AS SELECT value FROM future_cache")
                execSQL("CREATE TRIGGER future_insert AFTER INSERT ON future_cache BEGIN INSERT INTO future_search(title) VALUES(new.value); END")
                version = 8
            }
        }
        EpgDatabase(context).withDatabase { rebuilt ->
            assertEquals(7, rebuilt.readableDatabase.version)
            assertNull(rebuilt.fingerprint())
            assertTrue(rebuilt.channelRange("channel", 0, 200).isEmpty())
            assertEquals(0, rebuilt.readableDatabase.rawQuery("SELECT count(*) FROM sqlite_master WHERE name LIKE 'future_%'", null).use {
                it.moveToFirst(); it.getInt(0)
            })
            rebuilt.replaceAll("new-guide", mapOf("new" to listOf(EpgProgram(1, 100, "Rebuilt listing"))), emptyMap())
            assertEquals("Rebuilt listing", rebuilt.channelRange("new", 0, 200).single().title)
        }
        OnDemandDatabase(context).withDatabase { assertEquals("My correction" to 2005, it.posterFix("series:playlist:1")) }
    }

    @Test fun `a future poster cache becomes a miss and can be populated again`() {
        LiveTvPosterStore(context).withDatabase { cache ->
            cache.put("show", "https://fixture/old.jpg")
            cache.writableDatabase.execSQL("CREATE TABLE future_posters (name TEXT)")
            cache.writableDatabase.version = 4
        }
        LiveTvPosterStore(context).withDatabase { cache ->
            assertNull(cache.get("show"))
            assertEquals(3, cache.readableDatabase.version)
            cache.put("show", "https://fixture/new.jpg")
            assertEquals("https://fixture/new.jpg", cache.get("show"))
        }
    }

    @Test fun `future On Demand data is refused with a clear message and manual choices are not modified`() {
        OnDemandDatabase(context).withDatabase { saved ->
            saved.setPosterFix("movie:playlist:42", "User chosen title", 1994)
            saved.writableDatabase.version = 12
        }
        val issue = requireNotNull(DatabaseVersions.blockingIssue(context))
        assertEquals(DatabaseVersions.onDemand, issue.schema)
        assertEquals(12, issue.storedVersion)
        assertTrue(issue.description.contains("Your data has not been changed"))
        assertTrue(issue.description.contains("11"))
        assertTrue(issue.description.contains("12"))
        OnDemandDatabase(context).withDatabase { old ->
            val failure = runCatching { old.writableDatabase }.exceptionOrNull()
            assertTrue(failure.toString(), failure is NewerUserDatabaseException)
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(DatabaseVersions.onDemand.fileName).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(12, raw.version)
            raw.rawQuery("SELECT title,year FROM poster_fix WHERE uid='movie:playlist:42'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("User chosen title", it.getString(0))
                assertEquals(1994, it.getInt(1))
            }
        }
    }

    @Test fun `cache rebuilding explicitly refuses databases with user data before dropping any rows`() {
        OnDemandDatabase(context).withDatabase { saved ->
            saved.setPosterFix("series:p:1", "Keep this", 2005)
            val raw = saved.writableDatabase
            raw.beginTransaction()
            try {
                assertTrue(runCatching { rebuildCacheDatabase(raw, DatabaseVersions.onDemand) { error("must not create") } }.exceptionOrNull() is IllegalArgumentException)
            } finally { raw.endTransaction() }
            assertEquals("Keep this" to 2005, saved.posterFix("series:p:1"))
        }
    }

    @Test fun `a failed cache rebuild rolls back its schema version and old cache contents`() {
        val schema = LocalDatabaseSchema("fault-cache.db", "Fixture cache", 2, true)
        fun helper(fail: Boolean) = object : SQLiteOpenHelper(context, schema.fileName, null, schema.version) {
            override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE cache (value TEXT)") }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = rebuildCacheDatabase(db, schema) {
                onCreate(it)
                if (fail) throw SQLiteException("Injected rebuild failure")
            }
        }
        helper(false).withDatabase { saved ->
            saved.writableDatabase.execSQL("INSERT INTO cache VALUES('previous')")
            saved.writableDatabase.version = 9
        }
        helper(true).withDatabase { assertTrue(runCatching { it.writableDatabase }.isFailure) }
        SQLiteDatabase.openDatabase(context.getDatabasePath(schema.fileName).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(9, raw.version)
            assertEquals("previous", raw.rawQuery("SELECT value FROM cache", null).use { it.moveToFirst(); it.getString(0) })
        }
    }

    @Test fun `current and absent user databases do not block startup or rewrite stored choices`() {
        assertNull(DatabaseVersions.blockingIssue(context))
        OnDemandDatabase(context).withDatabase { it.setPosterFix("series:p:1", "Original choice", 2005) }
        assertNull(DatabaseVersions.blockingIssue(context))
        OnDemandDatabase(context).withDatabase { assertEquals("Original choice" to 2005, it.posterFix("series:p:1")) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun `Media3 playback index already rebuilds a newer cache schema`() {
        androidx.media3.database.StandaloneDatabaseProvider(context).withDatabase { saved ->
            saved.writableDatabase.execSQL("CREATE TABLE future_index (value TEXT)")
            saved.writableDatabase.version = 99
        }
        androidx.media3.database.StandaloneDatabaseProvider(context).withDatabase { rebuilt ->
            assertEquals(1, rebuilt.readableDatabase.version)
            assertEquals(0, rebuilt.readableDatabase.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='future_index'", null).use {
                it.moveToFirst(); it.getInt(0)
            })
        }
    }
}
