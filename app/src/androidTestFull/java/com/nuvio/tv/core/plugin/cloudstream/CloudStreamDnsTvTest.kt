package com.nuvio.tv.core.plugin.cloudstream

import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.app
import com.nuvio.tv.core.runtime.ExtensionDns
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class CloudStreamDnsTvTest {
    @Test fun retainedExtensionClientUsesEachSelectedResolver() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cloudStreamDns") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = EntryPointAccessors.fromApplication(context, CloudStreamTestEntryPoint::class.java).pluginManager()
        val original = manager.extensionDns.first()
        suspend fun select(mode: String) {
            manager.setExtensionDns(mode)
            withTimeout(5_000) { while (ExtensionDns.selected != mode) delay(25) }
        }
        try {
            PluginRuntimeHooks.ensureCloudstreamInitialized()
            val retainedClient = app.baseClient.newBuilder().build()
            assertSame(ExtensionDns, retainedClient.dns)
            for (mode in listOf("CLOUDFLARE", "GOOGLE", "QUAD9", "SYSTEM")) {
                select(mode)
                assertEquals(mode, ExtensionDns.selected)
                val addresses = retainedClient.dns.lookup("example.com")
                assertTrue("$mode should return addresses", addresses.isNotEmpty())
                assertFalse("$mode should resolve public addresses", addresses.any { it.isLoopbackAddress || it.isSiteLocalAddress })
            }
        } finally {
            select(original)
        }
    }
}
