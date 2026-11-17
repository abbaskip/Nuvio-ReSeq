package com.nuvio.tv.livetv.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImportWorkFilesTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `restart recovers abandoned download and migration copies without removing committed files`() {
        val dir=temp.newFolder()
        val committed=File(dir,"guide.xml").apply { writeText("published data") }
        val pending=File(dir,"guide.xml.tmp").apply { writeText("partial download") }
        val migration=File(dir,"old.xml.migration").apply { writeText("partial migration") }
        val child=File(dir,"keep.tmp").apply { mkdirs() }
        ImportWorkFiles.recover(dir)
        assertEquals("published data",committed.readText())
        assertFalse(pending.exists());assertFalse(migration.exists());assertTrue(child.isDirectory)
        ImportWorkFiles.recover(dir)
        assertEquals("published data",committed.readText())
    }
}
