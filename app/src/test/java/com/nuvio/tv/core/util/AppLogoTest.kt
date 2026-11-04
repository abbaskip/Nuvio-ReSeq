package com.nuvio.tv.core.util

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import com.nuvio.tv.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.FileNotFoundException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After

class AppLogoTest {
    private val resolver = mockk<ContentResolver>()
    private val uri = mockk<Uri>()
    private val context = mockk<Context> {
        every { packageName } returns "com.nuvio.tv"
        every { contentResolver } returns resolver
    }

    @Before fun resourceUri() {
        mockkStatic(Uri::class)
        every { Uri.parse("android.resource://com.nuvio.tv/${R.drawable.app_logo_wordmark}") } returns uri
    }

    @After fun cleanup() { unmockkStatic(Uri::class) }

    @Test fun `reads the existing branded wordmark bytes and closes the resource`() {
        val expected = byteArrayOf(0x50, 0x4e, 0x47, 0, -1)
        var closed = false
        val stream = object : ByteArrayInputStream(expected) {
            override fun close() { closed = true; super.close() }
        }
        every { resolver.openInputStream(uri) } returns stream
        assertArrayEquals(expected, context.readAppLogoBytes())
        assertTrue(closed)
        verify(exactly = 1) { resolver.openInputStream(uri) }
    }

    @Test fun `missing optional logo keeps the web server fallback available`() {
        every { resolver.openInputStream(uri) } throws FileNotFoundException()
        assertNull(context.readAppLogoBytes())
    }

    @Test fun `failed reads close the stream and keep the optional logo absent`() {
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = throw IOException("Unreadable resource")
            override fun close() { closed = true }
        }
        every { resolver.openInputStream(uri) } returns stream
        assertNull(context.readAppLogoBytes())
        assertTrue(closed)
    }

    @Test fun `nullable stream keeps the logo optional`() {
        every { resolver.openInputStream(uri) } returns null
        assertNull(context.readAppLogoBytes())
    }

    @Test fun `resource URI uses the installed variant package`() {
        every { context.packageName } returns "com.nuvio.tv.debug"
        val debugUri = mockk<Uri>()
        every { Uri.parse("android.resource://com.nuvio.tv.debug/${R.drawable.app_logo_wordmark}") } returns debugUri
        every { resolver.openInputStream(debugUri) } returns ByteArrayInputStream(byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), context.readAppLogoBytes())
        verify(exactly = 1) { resolver.openInputStream(debugUri) }
    }
}
