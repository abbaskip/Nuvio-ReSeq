package com.nuvio.tv.updater

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private fun file(dir: File, name: String, age: Long = 0) = File(dir, name).apply {
        writeText("synthetic installer"); setLastModified(now - age)
    }

    @Test fun `missing preferences do not protect orphan installers or partial downloads`() {
        val dir = temp.newFolder("updates")
        file(dir, "old.apk"); file(dir, "new.apk.part"); file(dir, "update.dm")
        assertTrue(UpdateFiles(dir) { now }.sweep(null, false))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun `only a valid pending file younger than one day is kept`() {
        val dir = temp.newFolder("updates")
        val pending = file(dir, "pending.apk")
        val stale = file(dir, "stale.apk", UpdateFiles.PENDING_TTL_MS + 1)
        val files = UpdateFiles(dir) { now }
        assertTrue(files.sweep(pending, true))
        assertTrue(pending.exists()); assertFalse(stale.exists())
        pending.setLastModified(now - UpdateFiles.PENDING_TTL_MS)
        assertTrue(files.sweep(pending, true)); assertFalse(pending.exists())
    }

    @Test fun `active installer is protected and successful completion releases source and marker`() {
        val dir = temp.newFolder("updates")
        val apk = file(dir, "active.apk")
        val files = UpdateFiles(dir) { now }
        files.pin(apk)
        assertTrue(files.isPinned(apk))
        assertTrue(files.sweep(null, false)); assertTrue(apk.exists())
        assertTrue(files.completed(apk)); assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun `expired install pins and outside paths cannot retain or delete unrelated user files`() {
        val dir = temp.newFolder("updates")
        val apk = file(dir, "active.apk")
        val outside = temp.newFile("recording.ts").apply { writeText("preserve user recording") }
        val files = UpdateFiles(dir) { now }
        files.pin(apk)
        File(dir, apk.name + ".installing").setLastModified(now - 3 * 60 * 60_000L)
        files.sweep(outside, true)
        assertFalse(apk.exists()); assertFalse(files.completed(outside)); assertTrue(outside.exists())
    }
}
