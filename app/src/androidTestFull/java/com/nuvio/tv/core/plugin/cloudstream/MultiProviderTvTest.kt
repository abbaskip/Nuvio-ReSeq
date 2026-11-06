package com.nuvio.tv.core.plugin.cloudstream

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.Plugin
import com.nuvio.tv.core.plugin.TestDiagnostics
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

class ResourceFixturePlugin : Plugin() {
    override fun load(context: Context) {
        val marker = requireNotNull(resources).assets.open("cs-marker").bufferedReader().use { it.readText() }
        registerMainAPI(object : MainAPI() { override var name = "$marker-one" })
        registerMainAPI(object : MainAPI() { override var name = "$marker-two" })
    }
}

@RunWith(AndroidJUnit4::class)
class MultiProviderTvTest {
    @Test fun resourcesAndAllProvidersSurvivePreloadAndCachedLookups() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val loader = ExternalExtensionLoader(context, ExternalExtractorRegistry())
        val scraperId = "fixture-multiple"
        val directory = File(context.filesDir, "cs_extensions").apply { mkdirs() }
        val file = File(directory, "$scraperId.cs3")
        try {
            // Package the fixture's already-compiled Android bytecode with the standard .cs3 manifest.
            ZipFile(instrumentation.context.applicationInfo.sourceDir).use { source ->
                ZipOutputStream(file.outputStream()).use { out ->
                    for (entry in source.entries()) {
                        if (!entry.name.endsWith(".dex") && entry.name != "AndroidManifest.xml") continue
                        out.putNextEntry(ZipEntry(entry.name))
                        source.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                    out.putNextEntry(ZipEntry("manifest.json"))
                    out.write("""{"pluginClassName":"${ResourceFixturePlugin::class.java.name}","requiresResources":true}""".toByteArray())
                    out.closeEntry()
                    out.putNextEntry(ZipEntry("assets/cs-marker"))
                    out.write("fixture".toByteArray())
                }
            }
            loader.ensureExtractorsLoaded(listOf(scraperId))
            val apis = loader.loadExtension(scraperId)
            assertEquals(listOf("fixture-one", "fixture-two"), apis.map { it.name })
            assertEquals(apis, loader.loadExtension(scraperId, TestDiagnostics()))
            assertTrue(apis.all { it.sourcePlugin == file.absolutePath })
            assertEquals(apis, APIHolder.allProviders.filter { it.sourcePlugin == file.absolutePath })
            CloudStreamApp.setKey("cs-test", "option", 21)
            assertEquals(21, CloudStreamApp.getKeyClass("cs-test/option", Int::class.javaObjectType))
            assertEquals(listOf("cs-test/option"), CloudStreamApp.getKeys("cs-test"))
            assertEquals(1, CloudStreamApp.removeKeys("cs-test"))
            assertNull(CloudStreamApp.getKeyClass("cs-test/option", Int::class.javaObjectType))
        } finally {
            CloudStreamApp.removeKey("cs-test/option")
            loader.deleteExtension(scraperId)
        }
        assertTrue(APIHolder.allProviders.none { it.sourcePlugin == file.absolutePath })
    }
}
