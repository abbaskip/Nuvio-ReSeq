package com.nuvio.tv.core.plugin.cloudstream

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtensionPackageTest {
    @get:Rule val temp = TemporaryFolder()

    private fun archive(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write("dex\n035\u0000".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    @Test fun acceptsVerifiedArchiveAndLegacyUnhashedDex() {
        val bytes = archive()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertArrayEquals(bytes, stageExtension(temp.root, bytes.toResponseBody(), "sha256-$sha").readBytes())
        val dex = "dex\n035\u0000".toByteArray()
        assertArrayEquals(dex, stageExtension(temp.root, dex.toResponseBody(), null).readBytes())
    }

    @Test fun corruptOrWrongDownloadLeavesOnlyTheInstalledPackage() {
        val original = temp.newFile("installed.cs3").apply { writeBytes(archive()) }
        val before = original.readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            stageExtension(temp.root, archive().toResponseBody(), "sha256-" + "0".repeat(64))
        }
        assertThrows(java.io.IOException::class.java) {
            stageExtension(temp.root, "<html>unavailable</html>".toResponseBody(), null)
        }
        assertArrayEquals(before, original.readBytes())
        assertEquals(listOf(original), temp.root.listFiles()?.toList())
    }
}
