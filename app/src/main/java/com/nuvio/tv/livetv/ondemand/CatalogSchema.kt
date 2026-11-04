package com.nuvio.tv.livetv.ondemand

import android.database.sqlite.SQLiteDatabase

/** Compact physical storage with the existing catalogue's SQL/read contract. */
internal object CatalogSchema {
    private val columns = listOf("id", "name", "norm", "cat", "ext", "tmdb", "year", "rating", "added", "genre", "h", "browse_at")

    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS catalog_policy (pl TEXT PRIMARY KEY, signature TEXT NOT NULL, lean INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE catalog_playlists (id INTEGER PRIMARY KEY, value TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE catalog_kinds (id INTEGER PRIMARY KEY, value TEXT NOT NULL UNIQUE)")
        db.execSQL("INSERT INTO catalog_kinds VALUES (0, 'movie'), (1, 'series')")
        db.execSQL("CREATE TABLE catalog_artwork (id INTEGER PRIMARY KEY, value TEXT NOT NULL UNIQUE)")
        db.execSQL("""CREATE TABLE catalog_items (
            pl INTEGER NOT NULL, kind INTEGER NOT NULL, id TEXT NOT NULL,
            name TEXT NOT NULL, norm TEXT NOT NULL, icon TEXT, artwork_base INTEGER, cat TEXT NOT NULL,
            ext TEXT, tmdb TEXT, year INTEGER, rating TEXT, added INTEGER NOT NULL DEFAULT 0,
            genre TEXT, h INTEGER, browse_at INTEGER NOT NULL DEFAULT 0,
            UNIQUE(pl, kind, id))""")
        db.execSQL("CREATE INDEX items_cat ON catalog_items(kind, pl, cat)")
        db.execSQL("CREATE INDEX items_norm ON catalog_items(kind, norm)")
        db.execSQL("CREATE INDEX items_tmdb ON catalog_items(kind, tmdb)")
        db.execSQL("CREATE INDEX items_added ON catalog_items(kind, added)")
        db.execSQL("""CREATE VIEW items AS SELECT c.rowid AS rowid, p.value AS pl, k.value AS kind,
            c.id, c.name, c.norm, CASE WHEN c.icon IS NULL THEN NULL ELSE COALESCE(a.value, '') || c.icon END AS icon, c.cat, c.ext, c.tmdb, c.year, c.rating,
            c.added, c.genre, c.h, c.browse_at
            FROM catalog_items c JOIN catalog_playlists p ON p.id = c.pl
            JOIN catalog_kinds k ON k.id = c.kind LEFT JOIN catalog_artwork a ON a.id = c.artwork_base""")
        // Intern just the shared URL origin, not whole URLs: a unique index on every complete
        // image URL would duplicate long, mostly-unique strings and could make storage larger.
        fun origin(offset: Int): String {
            val tail = "substr(NEW.icon, $offset)"
            val ends = listOf("/", "?", "#").joinToString { delimiter ->
                "CASE instr($tail, '$delimiter') WHEN 0 THEN length($tail)+1 ELSE instr($tail, '$delimiter') END"
            }
            return "substr(NEW.icon, 1, ${offset - 2} + min($ends))"
        }
        val base = "(CASE WHEN NEW.icon LIKE 'https://%' THEN ${origin(9)} WHEN NEW.icon LIKE 'http://%' THEN ${origin(8)} ELSE '' END)"
        val prepare = """
            INSERT OR IGNORE INTO catalog_playlists(value) VALUES (NEW.pl);
            INSERT OR IGNORE INTO catalog_artwork(value) SELECT $base WHERE NEW.icon IS NOT NULL;
        """
        val names = (listOf("rowid", "pl", "kind", "icon", "artwork_base") + columns).joinToString()
        val values = listOf("NEW.rowid", "(SELECT id FROM catalog_playlists WHERE value=NEW.pl)",
            "(SELECT id FROM catalog_kinds WHERE value=NEW.kind)",
            "substr(NEW.icon, length($base) + 1)", "(SELECT id FROM catalog_artwork WHERE value=$base)") + columns.map {
            if (it == "browse_at") "COALESCE(NEW.browse_at, 0)" else "NEW.$it"
        }
        db.execSQL("""CREATE TRIGGER items_insert INSTEAD OF INSERT ON items BEGIN
            $prepare INSERT OR REPLACE INTO catalog_items($names) VALUES (${values.joinToString()}); END""")
        val assignments = (listOf(
            "pl=(SELECT id FROM catalog_playlists WHERE value=NEW.pl)",
            "kind=(SELECT id FROM catalog_kinds WHERE value=NEW.kind)",
            "icon=substr(NEW.icon, length($base) + 1)",
            "artwork_base=(SELECT id FROM catalog_artwork WHERE value=$base)"
        ) + columns.map { "$it=NEW.$it" }).joinToString()
        db.execSQL("""CREATE TRIGGER items_update INSTEAD OF UPDATE ON items BEGIN
            $prepare UPDATE catalog_items SET $assignments WHERE rowid=OLD.rowid; END""")
        db.execSQL("CREATE TRIGGER items_delete INSTEAD OF DELETE ON items BEGIN DELETE FROM catalog_items WHERE rowid=OLD.rowid; END")
    }

    fun migrate(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE items RENAME TO items_legacy")
        for (name in listOf("items_cat", "items_norm", "items_tmdb", "items_added")) db.execSQL("DROP INDEX IF EXISTS $name")
        create(db)
        val hasBrowseTimestamp = db.rawQuery("PRAGMA table_info(items_legacy)", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(1) == "browse_at") found = true
            found
        }
        val legacyColumns = "rowid, pl, kind, id, name, norm, icon, cat, ext, tmdb, year, rating, added, genre, h" + if (hasBrowseTimestamp) ", browse_at" else ""
        db.execSQL("INSERT INTO items($legacyColumns) SELECT $legacyColumns FROM items_legacy")
        db.execSQL("DROP TABLE items_legacy")
    }

    fun isCompact(db: SQLiteDatabase): Boolean = db.rawQuery("SELECT type FROM sqlite_master WHERE name='items'", null).use {
        it.moveToFirst() && it.getString(0) == "view"
    }

    fun canMigrate(databaseBytes: Long, availableBytes: Long): Boolean =
        availableBytes >= databaseBytes * 2 + 64L * 1024 * 1024

    fun canMigrate(db: SQLiteDatabase): Boolean {
        val file = java.io.File(db.path)
        return canMigrate(file.length() + java.io.File(db.path + "-wal").length(), file.usableSpace)
    }

    /** Called inside the upgrade transaction. Legacy SQL remains usable when copy space is scarce. */
    fun migrateOrDefer(db: SQLiteDatabase, availableBytes: Long = java.io.File(db.path).usableSpace): Boolean {
        val footprint = java.io.File(db.path).length() + java.io.File(db.path + "-wal").length()
        if (canMigrate(footprint, availableBytes)) {
            migrate(db)
            return true
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS catalog_policy (pl TEXT PRIMARY KEY, signature TEXT NOT NULL, lean INTEGER NOT NULL)")
        db.execSQL("ALTER TABLE items ADD COLUMN browse_at INTEGER NOT NULL DEFAULT 0")
        return false
    }

    fun pruneArtwork(db: SQLiteDatabase) {
        if (!isCompact(db)) return
        db.execSQL("DELETE FROM catalog_artwork WHERE id NOT IN (SELECT DISTINCT artwork_base FROM catalog_items WHERE artwork_base IS NOT NULL)")
    }
}
