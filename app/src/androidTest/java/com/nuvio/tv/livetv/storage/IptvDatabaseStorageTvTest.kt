package com.nuvio.tv.livetv.storage

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.IptvStorageReport
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.ondemand.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IptvDatabaseStorageTvTest {
    private fun context() = StorageTestContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private fun item(id: Int, provider: String = "11111111-2222-3333-4444-555555555555", name: String = "Friends (en)",
        kind: VodKind = VodKind.SERIES, category: String = "en") = VodItem(provider, kind, id.toString(), name,
        "https://artwork.example.invalid/provider/catalogue/poster-${id / 4}.jpg?size=large&format=poster",
        category, "mp4", if (name.startsWith("Friends")) "1668" else id.toString(), 1994, "8.5", id.toLong(), "Comedy")

    @Test fun compactCataloguePreservesRowsImagesVariantsAndIndexedMatching() {
        val ctx = context()
        OnDemandDatabase(ctx).use { db ->
            val rows = listOf(item(1), item(2, name = "Friends (fr)"), item(3, provider = "second-provider"),
                item(4, name = "Edge title (en)").copy(icon = "https://images.example.invalid?item=4#art"))
            rows.groupBy { it.playlistId }.forEach { (provider, values) ->
                db.replace(provider, VodKind.SERIES, listOf("en" to "English")) { emit -> values.forEach(emit); true }
            }
            assertEquals(setOf("1", "2", "3"), db.byTitle(VodKind.SERIES, "Friends").map { it.id }.toSet())
            rows.forEach { original -> assertEquals(original, db.item(original.playlistId, original.kind, original.id)) }
            assertEquals(2, db.versions(rows.first()).count { it.playlistId == rows.first().playlistId })
            val plan = db.readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT * FROM items WHERE kind=? AND norm=?",
                arrayOf("series", "friends")).use { cursor -> buildString { while (cursor.moveToNext()) append(cursor.getString(3)) } }
            assertTrue("Title matching must retain its index", plan.contains("items_norm"))
            assertEquals(2L, scalar(db.readableDatabase, "SELECT COUNT(*) FROM catalog_playlists"))
        }
        ctx.root.deleteRecursively()
    }

    @Test fun migrationFromVersion10PreservesCatalogueAndReindexesExistingEnglishNames() {
        val ctx = context()
        val raw = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("livetv_on_demand.db"), null)
        legacySchema(raw)
        raw.execSQL("INSERT INTO items VALUES ('provider','series','1','Friends (en)','friendsen','https://images.example.invalid/friends.jpg','en','mp4','1668',1994,'8',1,NULL,1)")
        raw.execSQL("INSERT INTO categories VALUES ('provider','series','en','English',0)")
        raw.version = 10; raw.close()
        OnDemandDatabase(ctx).use { db ->
            val migrated = db.byTitle(VodKind.SERIES, "Friends").single()
            assertEquals("Friends (en)", migrated.name)
            assertEquals("https://images.example.invalid/friends.jpg", migrated.icon)
            assertEquals("provider", migrated.playlistId)
            assertEquals("English", db.categories(VodKind.SERIES).single().name)
        }
        ctx.root.deleteRecursively()
    }

    @Test fun sourceOnlyModeAndCategorySelectionPreserveAllowedMatchingAndBoundArtwork() {
        val ctx = context()
        val source = PlaylistSource("provider", "Fixture", "", xtreamServer = "http://127.0.0.1", importVod = true)
        OnDemandDatabase(ctx).use { db ->
            db.configureImport(source)
            db.replace(source.id, VodKind.SERIES, listOf("en" to "English", "fr" to "French")) { emit ->
                repeat(650) { emit(item(it, source.id, category = if (it == 649) "fr" else "en")) }; true
            }
            val expected = db.byTitle(VodKind.SERIES, "Friends").map { it.id }.toSet()
            db.configureImport(source.copy(vodSourceOnly = true))
            assertEquals(expected, db.byTitle(VodKind.SERIES, "Friends").map { it.id }.toSet())
            assertTrue(db.byTitle(VodKind.SERIES, "Friends").all { it.icon == null })
            db.cacheBrowseArtwork(source.id, VodKind.SERIES, (0..599).associate { it.toString() to "https://images.example.invalid/$it.jpg" })
            assertEquals(500L, scalar(db.readableDatabase, "SELECT COUNT(*) FROM items WHERE icon IS NOT NULL"))
            db.configureImport(source.copy(vodSourceOnly = true, vodSeriesCategories = setOf("en")))
            assertEquals(649, db.count(VodKind.SERIES))
            assertTrue(db.byTitle(VodKind.SERIES, "Friends").all { it.categoryId == "en" })
            db.configureImport(source.copy(vodSeries = false))
            assertEquals(0, db.count(VodKind.SERIES))
        }
        ctx.root.deleteRecursively()
    }

    @Test fun interruptedGuideImportsAndStartupSweepPreserveTheDisplayedGuide() {
        val ctx = context()
        EpgDatabase(ctx).use { db ->
            val program = EpgProgram(1_800_000_000_000, 1_800_000_001_000, "Existing guide", description = "Keep this description")
            db.replaceAll("before", mapOf("channel" to listOf(program)), emptyMap())
            val writer = db.startStage("interrupted")
            // Cross the actual batch-commit boundary; rollback alone cannot remove those rows.
            writer.add("fixture", List(50_000) { program }) { false }
            writer.abort()
            assertEquals(0L, scalar(db.readableDatabase, "SELECT COUNT(*) FROM stage"))
            db.writableDatabase.execSQL("INSERT INTO stage(src,xid,start,stop,title) VALUES ('orphan','id',1,2,'synthetic')")
            db.dropStagesExcept(emptyList())
            assertEquals(0L, scalar(db.readableDatabase, "SELECT COUNT(*) FROM stage"))
            assertEquals(1L, scalar(db.readableDatabase, "SELECT COUNT(*) FROM programs"))
            db.retainDescriptions = false
            db.replaceAll("lean", mapOf("channel" to listOf(program)), emptyMap())
            assertEquals(0L, scalar(db.readableDatabase, "SELECT COUNT(*) FROM programs WHERE descr IS NOT NULL"))
        }
        ctx.root.deleteRecursively()
    }

    @Test fun catalogueMigrationActuallyReducesSettledBytesWithoutLosingTitles() {
        val ctx = context()
        val path = ctx.getDatabasePath("livetv_on_demand.db")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { raw ->
            legacySchema(raw)
            raw.beginTransaction()
            try {
                raw.compileStatement("INSERT INTO items VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { insert ->
                    repeat(30_000) { id ->
                        val values = item(id, name = "Fixture title $id (en)")
                        listOf(values.playlistId, values.kind.key, values.id, values.name, "fixturetitle$id", values.icon,
                            values.categoryId, values.ext, values.tmdbId, "1994", "8.5", id.toString(), "Comedy", "1")
                            .forEachIndexed { index, value -> if (value == null) insert.bindNull(index + 1) else insert.bindString(index + 1, value) }
                        insert.executeInsert()
                    }
                }
                raw.setTransactionSuccessful()
            } finally { raw.endTransaction() }
            raw.version = 10
        }
        val before = path.length()
        OnDemandDatabase(ctx).use { db ->
            assertEquals(30_000, db.count(VodKind.SERIES))
            db.compact()
            db.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            val after = path.length() + File(path.path + "-wal").length()
            val report = org.json.JSONObject().put("rows", 30_000).put("legacyBytes", before).put("compactBytes", after)
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "iptv-storage-benchmark.json").writeText(report.toString())
            assertTrue("Compact catalogue must use less space for the same complete dataset", after < before)
        }
        ctx.root.deleteRecursively()
    }

    @Test fun lowSpaceDefersCopyButKeepsEnglishSourcesAndCanFinishLater() {
        val ctx = context()
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("livetv_on_demand.db"), null).use { legacy ->
            legacySchema(legacy)
            legacy.execSQL("INSERT INTO items VALUES ('provider','series','1','Friends (en)','friendsen','https://images.example.invalid/friends.jpg','en','mp4','1668',1994,'8',1,NULL,1)")
            assertFalse(CatalogSchema.migrateOrDefer(legacy, availableBytes = 0))
            assertFalse(CatalogSchema.isCompact(legacy))
            legacy.version = 11
        }
        OnDemandDatabase(ctx).use { db ->
            assertTrue(db.shrinkingNeedsSpace())
            assertEquals("1", db.byTitle(VodKind.SERIES, "Friends").single().id)
            assertEquals("https://images.example.invalid/friends.jpg", db.item("provider", VodKind.SERIES, "1")?.icon)
            db.replace("provider", VodKind.SERIES, listOf("en" to "English")) { emit -> emit(item(1, "provider", "Friends (EN)")); true }
            assertEquals(1, db.count(VodKind.SERIES))
            assertEquals("Friends (EN)", db.byTitle(VodKind.SERIES, "Friends").single().name)
            db.compact()
            assertFalse(db.shrinkingNeedsSpace())
            assertEquals("1", db.byTitle(VodKind.SERIES, "Friends").single().id)
        }
        ctx.root.deleteRecursively()
    }

    @Test fun maintenanceExpiresDerivedPostersAndPreservesCorrectionsSourcesAndRecordings() {
        val ctx = context()
        val recording = File(ctx.filesDir, "recordings/fixture.ts").apply { parentFile!!.mkdirs(); writeText("keep recording") }
        OnDemandDatabase(ctx).use { db ->
            db.replace("provider", VodKind.SERIES, listOf("en" to "English")) { emit -> emit(item(1)); true }
            db.setPosterFix("series:provider:1", "Friends", 1994)
            db.cachePoster("series:provider:1", "https://images.example.invalid/current.jpg")
            db.writableDatabase.execSQL("INSERT INTO posters VALUES ('expired','https://images.example.invalid/old.jpg',1,NULL)")
            db.compact()
            assertNull(db.cachedPoster("expired"))
            assertNotNull(db.cachedPoster("series:provider:1"))
            assertEquals("Friends" to 1994, db.posterFix("series:provider:1"))
            assertEquals(1, db.count(VodKind.SERIES))
            val report = IptvStorageReport.scan(ctx)
            assertTrue(report.categories.first { it.label.startsWith("On Demand") }.bytes > 0)
            assertTrue(report.categories.first { it.label.startsWith("Recordings") }.bytes >= recording.length())
            assertFalse(report.readable().contains("fixture.ts"))
            assertTrue(recording.exists())
        }
        ctx.root.deleteRecursively()
    }

    private fun scalar(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }
    private fun legacySchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE items(pl TEXT NOT NULL,kind TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,norm TEXT NOT NULL,icon TEXT,cat TEXT NOT NULL,ext TEXT,tmdb TEXT,year INTEGER,rating TEXT,added INTEGER NOT NULL DEFAULT 0,genre TEXT,h INTEGER,PRIMARY KEY(pl,kind,id))")
        db.execSQL("CREATE INDEX items_cat ON items(kind,pl,cat)")
        db.execSQL("CREATE INDEX items_norm ON items(kind,norm)")
        db.execSQL("CREATE INDEX items_tmdb ON items(kind,tmdb)")
        db.execSQL("CREATE INDEX items_added ON items(kind,added)")
        db.execSQL("CREATE TABLE categories(pl TEXT NOT NULL,kind TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(pl,kind,id))")
        db.execSQL("CREATE TABLE prefixes(pl TEXT NOT NULL,prefix TEXT NOT NULL,PRIMARY KEY(pl,prefix))")
        db.execSQL("CREATE TABLE posters(uid TEXT PRIMARY KEY,poster TEXT,checked INTEGER NOT NULL,title TEXT)")
        db.execSQL("CREATE TABLE poster_fix(uid TEXT PRIMARY KEY,title TEXT NOT NULL,year INTEGER)")
        db.execSQL("CREATE TABLE import_seen(session TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(session,id))")
    }
}
