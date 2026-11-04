package com.nuvio.tv.livetv.storage

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID

/** Every test owns its files/databases; never opens a production package or its accounts. */
internal class StorageTestContext(base: Context) : ContextWrapper(base) {
    val root = File(base.cacheDir, "iptv-storage-tests/" + UUID.randomUUID()).apply { mkdirs() }
    override fun getApplicationContext(): Context = this
    override fun getFilesDir() = File(root, "files").apply { mkdirs() }
    override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(File(root, "databases").apply { mkdirs() }, name)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
}
