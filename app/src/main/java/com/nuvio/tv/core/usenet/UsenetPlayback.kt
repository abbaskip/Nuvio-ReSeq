package com.nuvio.tv.core.usenet

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal object UsenetPlayback {
    private val client by lazy { OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build() }
    private val sessions = LinkedHashMap<String, UsenetHttpSession>()
    private val cleanup by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Usenet session cleanup").apply { isDaemon = true } }.apply {
            scheduleWithFixedDelay({
                val idle = synchronized(sessions) { sessions.values.filter { it.isIdle() }.map { it.url } }
                idle.forEach(::release)
            }, 1, 1, TimeUnit.MINUTES)
        }
    }

    fun release(url: String) {
        val session = synchronized(sessions) { sessions.remove(url) } ?: return
        cleanup.execute { session.close() }
    }

    suspend fun resolve(stream: Stream): Stream {
        val source = requireNotNull(stream.usenet)
        var opened: UsenetHttpSession? = null
        try {
            return withContext(Dispatchers.IO) {
                val request = Request.Builder().url(source.nzbUrl).build()
                val files = client.newCall(request).execute().use { response ->
                    ensureUsenet(response.isSuccessful, "Could not download the NZB.")
                    val body = response.body?.source() ?: throw UsenetFailure("Empty NZB response.")
                    ensureUsenet(!body.request(NzbManifest.MAX_BYTES + 1L), "NZB exceeds the size limit.")
                    NzbManifest.parse(body.readUtf8().removePrefix("\uFEFF"))
                }
                coroutineContext.ensureActive()
                val articles = NewsArticles(source.servers)
                val context = coroutineContext
                try {
                    var preparing: Job? = context[Job]
                    val cache = ArticleCache { id, next ->
                        preparing?.ensureActive()
                        articles.fetch(id, next)
                    }
                    val file = UsenetSelection.select(files, cache, stream.fileIdx, source.filters) {
                        context.ensureActive()
                    }
                    context.ensureActive()
                    preparing = null
                    val session = UsenetHttpSession(file, articles)
                    opened = session
                    session.start()
                    synchronized(sessions) {
                        // Retain the outgoing player during source handoff.
                        if (sessions.size >= 2) release(sessions.keys.first())
                        sessions[session.url] = session
                    }
                    cleanup
                    val hints = stream.behaviorHints ?: StreamBehaviorHints(
                        notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null
                    )
                    stream.copy(
                        url = session.url, usenet = null,
                        behaviorHints = hints.copy(filename = file.name, videoSize = file.size, proxyHeaders = null)
                    )
                } catch (e: Exception) {
                    articles.close()
                    throw e
                }
            }
        } catch (e: Exception) {
            opened?.let { session ->
                synchronized(sessions) { sessions.values.remove(session) }
                cleanup.execute { session.close() }
            }
            if (e is CancellationException || e is UsenetFailure) throw e
            throw UsenetFailure("Could not open this Usenet stream.")
        }
    }
}
