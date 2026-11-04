package com.nuvio.tv.core.util

import android.content.Context
import android.content.res.Resources
import com.nuvio.tv.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogoTest {
    private val resources = mockk<Resources>()
    private val context = mockk<Context> {
        every { resources } returns this@AppLogoTest.resources
    }

    @Test fun `reads the existing branded wordmark bytes and closes the resource`() {
        val expected = byteArrayOf(0x50, 0x4e, 0x47, 0, -1)
        var closed = false
        val stream = object : ByteArrayInputStream(expected) {
            override fun close() { closed = true; super.close() }
        }
        every { resources.openRawResource(R.drawable.app_logo_wordmark) } returns stream
        assertArrayEquals(expected, context.readAppLogoBytes())
        assertTrue(closed)
        verify(exactly = 1) { resources.openRawResource(R.drawable.app_logo_wordmark) }
    }

    @Test fun `missing optional logo keeps the web server fallback available`() {
        every { resources.openRawResource(R.drawable.app_logo_wordmark) } throws Resources.NotFoundException()
        assertNull(context.readAppLogoBytes())
    }

    @Test fun `failed reads close the stream and keep the optional logo absent`() {
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = throw IOException("Unreadable resource")
            override fun close() { closed = true }
        }
        every { resources.openRawResource(R.drawable.app_logo_wordmark) } returns stream
        assertNull(context.readAppLogoBytes())
        assertTrue(closed)
    }
}
