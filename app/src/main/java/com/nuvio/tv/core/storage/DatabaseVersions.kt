package com.nuvio.tv.core.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException

/** Disposable means every row can be reconstructed without losing a user choice. */
data class LocalDatabaseSchema(val fileName: String, val displayName: String, val version: Int, val rebuildable: Boolean)

object DatabaseVersions {
    val guide = LocalDatabaseSchema("livetv_guide.db", "TV guide", 7, true)
    val posters = LocalDatabaseSchema("livetv_posters.db", "Poster lookup", 3, true)
    // poster_fix contains manual corrections: preserve the whole database.
    val onDemand = LocalDatabaseSchema("livetv_on_demand.db", "On Demand", 11, false)
    val all = listOf(guide, posters, onDemand)

    /** Read-only preflight before feature startup. Never recreate a user database. */
    fun blockingIssue(context: Context): DatabaseVersionIssue? {
        for (schema in all.filterNot { it.rebuildable }) {
            val path = context.getDatabasePath(schema.fileName)
            if (!path.exists()) continue
            val stored = try {
                SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
            } catch (_: SQLiteException) {
                return DatabaseVersionIssue(schema, null)
            }
            if (stored > schema.version) return DatabaseVersionIssue(schema, stored)
        }
        return null
    }
}

data class DatabaseVersionIssue(val schema: LocalDatabaseSchema, val storedVersion: Int?) {
    val description: String get() = if (storedVersion == null)
        "${schema.displayName} data could not be opened safely. Your data has not been changed. Try restarting, or reinstall the build that created it."
    else "${schema.displayName} data was saved by a newer version of Nuvio ReSeq. This build supports version ${schema.version}, but the saved data uses version $storedVersion. Your data has not been changed. Reinstall a compatible newer build to continue."
}

class NewerUserDatabaseException(val issue: DatabaseVersionIssue) : SQLiteException(issue.description)

/** SQLiteOpenHelper calls this within its schema transaction: failure rolls back. */
internal fun rebuildCacheDatabase(db: SQLiteDatabase, schema: LocalDatabaseSchema, create: (SQLiteDatabase) -> Unit) {
    require(schema.rebuildable) { "${schema.displayName} contains user data and cannot be rebuilt" }
    check(db.inTransaction()) { "Cache rebuild must be atomic" }
    data class Object(val type: String, val name: String, val virtual: Boolean)
    val objects = db.rawQuery(
        "SELECT type,name,sql FROM sqlite_master WHERE type IN ('trigger','view','table') " +
            "AND name!='android_metadata' AND substr(name,1,7)!='sqlite_'", null
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(Object(cursor.getString(0), cursor.getString(1),
            cursor.getString(2)?.trimStart()?.startsWith("CREATE VIRTUAL TABLE", ignoreCase = true) == true))
    } }
    // Dropping an FTS parent also drops its shadow tables. IF EXISTS handles that.
    for (entry in objects.sortedBy { when {
        it.type == "trigger" -> 0
        it.type == "view" -> 1
        it.virtual -> 2
        else -> 3
    } }) {
        val identifier = entry.name.replace("\"", "\"\"")
        db.execSQL("DROP ${entry.type} IF EXISTS \"$identifier\"")
    }
    create(db)
}
