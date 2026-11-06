package com.nuvio.tv.core.plugin.cloudstream

import android.content.Context
import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.plugin.TestDiagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val TAG = "ExtExtensionLoader"

/** Legacy entry points occasionally expose the registration API without extending BasePlugin. */
private fun looksLikePlugin(instance: Any): Boolean = instance.javaClass.methods.any { method ->
    when (method.name) {
        "getRegisteredMainAPIs" -> method.parameterCount == 0
        "load" -> method.parameterCount == 0 || method.parameterTypes.toList() in listOf(
            listOf(Context::class.java), listOf(android.app.Activity::class.java)
        )
        else -> false
    }
}

private class ReflectivePluginWrapper(private val foreignInstance: Any) : Plugin() {
    override fun load(activity: android.app.Activity?) {
        load(activity as? Context ?: AcraApplication.context ?: return)
    }

    override fun load(context: Context) {
        val clazz = foreignInstance.javaClass

        // Try load(Context) first
        try {
            val m = clazz.getMethod("load", Context::class.java)
            m.invoke(foreignInstance, context)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is ClassCastException) {
                Log.d(TAG, "ReflectivePluginWrapper: load(Context) got ClassCastException, retrying with null Activity")
                try {
                    val m = clazz.getMethod("load", android.app.Activity::class.java)
                    m.invoke(foreignInstance, null)
                } catch (e2: Exception) {
                    Log.w(TAG, "ReflectivePluginWrapper: load(Activity) also failed: ${e2.message}")
                }
            } else {
                Log.w(TAG, "ReflectivePluginWrapper: load(Context) threw: ${cause?.message ?: e.message}")
            }
        } catch (_: NoSuchMethodException) {
            // Try load(Activity?) next
            try {
                val m = clazz.getMethod("load", android.app.Activity::class.java)
                m.invoke(foreignInstance, null)
            } catch (_: NoSuchMethodException) {
                // Try no-arg load() (BasePlugin pattern)
                try {
                    val m = clazz.getMethod("load")
                    m.invoke(foreignInstance)
                    Log.d(TAG, "ReflectivePluginWrapper: loaded via no-arg load()")
                } catch (e: Exception) {
                    Log.w(TAG, "ReflectivePluginWrapper: load() (no-arg) failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ReflectivePluginWrapper: load(Activity) failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ReflectivePluginWrapper: load() failed: ${e.message}")
        }

        // Non-standard plugins may only expose local registration lists.
        val apis = clazz.methods.find { it.name == "getRegisteredMainAPIs" && it.parameterCount == 0 }
            ?.invoke(foreignInstance) as? List<*>
        apis.orEmpty().filterIsInstance<MainAPI>().filterNot { it in APIHolder.allProviders }.forEach(::registerMainAPI)
        val extractors = clazz.methods.find { it.name == "getRegisteredExtractorAPIs" && it.parameterCount == 0 }
            ?.invoke(foreignInstance) as? List<*>
        extractors.orEmpty().filterIsInstance<ExtractorApi>().filterNot { it in extractorApis }.forEach(::registerExtractorAPI)
    }
}

/**
 * Manages downloading, loading, and caching of DEX-based external extensions (.cs3 files).
 */
@Singleton
class ExternalExtensionLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val extractorRegistry: ExternalExtractorRegistry
) {
    private val httpClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private data class LoadedExtension(
        val plugin: BasePlugin?,
        val providers: List<MainAPI>,
        val extractors: List<ExtractorApi>
    )

    private val loaded = mutableMapOf<String, LoadedExtension>()
    private val preloaded = mutableSetOf<String>()

    private val extensionsDir: File
        get() = File(context.filesDir, "cs_extensions").also { it.mkdirs() }

    private val codeCacheDir: File
        get() = File(context.codeCacheDir, "cs_dex_cache").also { it.mkdirs() }

    /** Sanitize scraper ID for use as a filename (colons are not safe on all filesystems). */
    private fun safeFileName(scraperId: String): String =
        scraperId.replace(':', '_').replace('/', '_')

    /**
     * Download a .cs3 DEX file for the given scraper.
     * Returns the local file path, or null on failure.
     */
    suspend fun downloadExtension(scraperId: String, downloadUrl: String, fileHash: String? = null): File? = withContext(Dispatchers.IO) {
        com.nuvio.tv.core.runtime.PluginRuntimeHooks.ensureCloudstreamInitialized()
        try {
            val targetFile = File(extensionsDir, "${safeFileName(scraperId)}.cs3")

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "NuvioTV/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download extension $scraperId: HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body ?: return@withContext null
                val staged = stageExtension(targetFile.parentFile!!, body, fileHash)
                try {
                    synchronized(this@ExternalExtensionLoader) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            check(staged.setReadOnly()) { "Could not mark extension read-only" }
                        }
                        // Same directory and filesystem. POSIX rename replaces the old package atomically.
                        android.system.Os.rename(staged.absolutePath, targetFile.absolutePath)
                        evictCache(scraperId)
                    }
                } finally {
                    staged.delete()
                }
                targetFile
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading extension $scraperId: ${e.message}", e)
            null
        }
    }

    @Synchronized
    fun loadExtension(scraperId: String, diagnostics: TestDiagnostics? = null): List<MainAPI> {
        loaded[scraperId]?.providers?.let {
            diagnostics?.addStep("Cached providers: ${it.joinToString { api -> api.name }}")
            return it
        }
        com.nuvio.tv.core.runtime.PluginRuntimeHooks.ensureCloudstreamInitialized()
        val file = File(extensionsDir, "${safeFileName(scraperId)}.cs3")
        if (!file.isFile) {
            diagnostics?.addStep("Missing extension: ${file.name}")
            return emptyList()
        }

        try {
            ensureDexReadOnly(file)
            val loader = DexClassLoader(file.path, codeCacheDir.path, null, context.classLoader)
            AcraApplication.context = context
            extractorRegistry.installGlobal()
            val providersBefore = synchronized(APIHolder.allProviders) { APIHolder.allProviders.toSet() }
            val extractorsBefore = extractorApis.toSet()
            val plugin = findAndLoadPlugin(loader, file)
            if (plugin != null) {
                try {
                    if (plugin is Plugin) plugin.load((AcraApplication.getActivity() as Context?) ?: context)
                    else plugin.load()
                } catch (e: Exception) {
                    reportLoadFailure(scraperId, e, diagnostics)
                } catch (e: LinkageError) {
                    reportLoadFailure(scraperId, e, diagnostics)
                } catch (e: NotImplementedError) {
                    reportLoadFailure(scraperId, e, diagnostics)
                }
            }
            val providers = synchronized(APIHolder.allProviders) {
                APIHolder.allProviders.filterNot { it in providersBefore }.toMutableList()
            }
            val extractors = extractorApis.filterNot { it in extractorsBefore }.toMutableList()
            val scanProviders = plugin != null && providers.isEmpty()
            val scanExtractors = extractors.isEmpty()
            if (scanProviders || scanExtractors) {
                // Preserve ReSeq's fallback for old packages which omit registrations or an entry point.
                @Suppress("DEPRECATION")
                val classes = try {
                    dexClassNames(file)
                } catch (e: Exception) {
                    reportLoadFailure(scraperId, e, diagnostics)
                    emptyList()
                }
                for (name in classes.filterNot { '$' in it }) {
                    try {
                        val clazz = loader.loadClass(name)
                        if (java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) continue
                        if (scanProviders && MainAPI::class.java.isAssignableFrom(clazz)) {
                            providers += clazz.getDeclaredConstructor().newInstance() as MainAPI
                        } else if (scanExtractors && ExtractorApi::class.java.isAssignableFrom(clazz)) {
                            extractors += clazz.getDeclaredConstructor().newInstance() as ExtractorApi
                        }
                    } catch (_: ReflectiveOperationException) {
                        // Fallback scanning can encounter unrelated classes without default constructors.
                    } catch (_: LinkageError) {
                        // An optional dependency of this candidate is unavailable.
                    }
                }
            }
            extractorRegistry.registerAll(extractors)
            diagnostics?.addStep("Loaded ${providers.size} providers, ${extractors.size} extractors")
            if (providers.isNotEmpty() || extractors.isNotEmpty()) {
                loaded[scraperId] = LoadedExtension(plugin, providers.toList(), extractors)
            }
            return providers
        } catch (e: Exception) {
            reportLoadFailure(scraperId, e, diagnostics)
        } catch (e: Error) {
            if (e is VirtualMachineError || e is ThreadDeath) throw e
            reportLoadFailure(scraperId, e, diagnostics)
        }
        return emptyList()
    }

    private fun reportLoadFailure(id: String, error: Throwable, diagnostics: TestDiagnostics?) {
        Log.w(TAG, "Could not fully load extension $id", error)
        diagnostics?.addStep("Load error: ${error.javaClass.simpleName}: ${error.message?.take(160)}")
    }

    @Synchronized
    fun ensureExtractorsLoaded(scraperIds: List<String>, diagnostics: TestDiagnostics? = null) {
        // Broken packages should not be rescanned on every search. An actual provider request can retry.
        scraperIds.filter { preloaded.add(it) }.forEach { loadExtension(it, diagnostics) }
    }

    @Synchronized
    fun deleteExtension(scraperId: String) {
        evictCache(scraperId)
        File(extensionsDir, "${safeFileName(scraperId)}.cs3").delete()
    }

    @Synchronized
    private fun evictCache(scraperId: String) {
        preloaded.remove(scraperId)
        val extension = loaded.remove(scraperId) ?: return
        try {
            extension.plugin?.beforeUnload()
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            Log.w(TAG, "Extension unload hook failed", failure)
        } finally {
            synchronized(APIHolder.allProviders) {
                extension.providers.forEach { api ->
                    APIHolder.allProviders.remove(api)
                    APIHolder.removePluginMapping(api)
                }
            }
            extractorApis.removeAll(extension.extractors.toSet())
        }
    }

    /**
     * Ensure a DEX file is read-only. Required for Android API 28+ which blocks
     * writable DEX file loading.
     */
    private fun ensureDexReadOnly(dexFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dexFile.canWrite()) {
            dexFile.setReadOnly()
            Log.d(TAG, "Fixed DEX permissions (set read-only): ${dexFile.name}")
        }
    }

    private fun findAndLoadPlugin(classLoader: DexClassLoader, cs3File: File): BasePlugin? {
        val manifest = readManifest(cs3File)
        val pluginClassName = manifest?.optString("pluginClassName")?.takeIf { it.isNotBlank() }
        val plugin = instantiatePlugin(classLoader, cs3File, pluginClassName) ?: return null
        plugin.filename = cs3File.absolutePath
        if (plugin is Plugin && manifest?.optBoolean("requiresResources") == true) {
            // Same resource-loading contract as NuvioMobile-Enhanced's Android runtime.
            val assets = android.content.res.AssetManager::class.java.getDeclaredConstructor().newInstance()
            val cookie = assets.javaClass.getMethod("addAssetPath", String::class.java)
                .invoke(assets, cs3File.absolutePath) as Int
            check(cookie != 0) { "Could not load extension resources" }
            @Suppress("DEPRECATION")
            plugin.resources = android.content.res.Resources(assets, context.resources.displayMetrics, context.resources.configuration)
        }
        return plugin
    }

    private fun instantiatePlugin(classLoader: DexClassLoader, cs3File: File, pluginClassName: String?): BasePlugin? {
        if (pluginClassName != null) {
            try {
                Log.d(TAG, "Loading plugin class from manifest: $pluginClassName")
                val clazz = classLoader.loadClass(pluginClassName)
                val instance = clazz.getDeclaredConstructor().newInstance()
                if (instance is BasePlugin) {
                    return instance
                }
                if (looksLikePlugin(instance)) {
                    Log.d(TAG, "Using reflective wrapper for $pluginClassName (non-standard base class)")
                    return ReflectivePluginWrapper(instance)
                }
                Log.w(TAG, "Class $pluginClassName is not a Plugin and has no plugin methods")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load manifest class $pluginClassName: ${e.message}", e)
            } catch (e: Error) {
                Log.e(TAG, "Linkage error loading manifest class $pluginClassName: ${e.message}", e)
            }
        }

        return scanForPluginClass(classLoader, cs3File)
    }

    private fun readManifest(cs3File: File): JSONObject? {
        return try {
            ZipFile(cs3File).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json") ?: return null
                val json = zip.getInputStream(manifestEntry).bufferedReader().readText()
                JSONObject(json)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read manifest.json from ZIP: ${e.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun dexClassNames(file: File): List<String> {
        val dex = dalvik.system.DexFile(file)
        return try { dex.entries().toList() } finally { dex.close() }
    }

    private fun scanForPluginClass(classLoader: DexClassLoader, cs3File: File): BasePlugin? {
        try {
            for (className in dexClassNames(cs3File)) {
                try {
                    val clazz = classLoader.loadClass(className)
                    if (clazz.isAnnotationPresent(CloudstreamPlugin::class.java)) {
                        Log.d(TAG, "Found plugin class via scan: $className")
                        val instance = clazz.getDeclaredConstructor().newInstance()
                        if (instance is BasePlugin) {
                            return instance
                        }
                        if (looksLikePlugin(instance)) {
                            Log.d(TAG, "Using reflective wrapper for $className (non-standard base class)")
                            return ReflectivePluginWrapper(instance)
                        }
                        Log.w(TAG, "Annotated class $className has no plugin methods")
                    }
                } catch (_: ClassNotFoundException) {
                } catch (_: NoClassDefFoundError) {
                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting class $className: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.d(TAG, "DexFile scan fallback failed: ${e.message}")
        }

        return null
    }
}
