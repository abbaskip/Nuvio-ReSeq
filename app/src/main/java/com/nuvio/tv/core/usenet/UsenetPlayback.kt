package com.nuvio.tv.core.usenet

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object UsenetPlayback {
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

    suspend fun resolve(stream: Stream): Stream = suspendCancellableCoroutine { continuation ->
        val preparation = UsenetPreparation()
        continuation.invokeOnCancellation { preparation.close() }
        Dispatchers.IO.asExecutor().execute {
            try {
                val prepared = prepare(stream, preparation) { continuation.context.ensureActive() }
                continuation.resume(prepared, onCancellation = { _, value, _ -> value.url?.let(::release) })
            } catch (error: Exception) {
                preparation.close()
                val failure = if (error is CancellationException || error is UsenetFailure) error
                    else UsenetFailure("Could not open this Usenet stream.")
                continuation.resumeWith(Result.failure(failure))
            }
        }
    }

    private fun prepare(stream: Stream, preparation: UsenetPreparation, checkActive: () -> Unit): Stream {
        val source = requireNotNull(stream.usenet)
        val files = NzbDownload.fetch(source.nzbUrl, stream.behaviorHints?.proxyHeaders?.request.orEmpty(), preparation)
        checkActive()
        val articles = NewsArticles(source.servers)
        preparation.own(articles)
        var preparing = true
        val cache = ArticleCache { id, next ->
            if (preparing) checkActive()
            articles.fetch(id, next)
        }
        val file = UsenetSelection.select(files, cache, stream.fileIdx, source.filters, checkActive)
        checkActive()
        preparing = false
        val session = UsenetHttpSession(file, articles)
        preparation.own(java.io.Closeable { cleanup.execute { session.close() } })
        session.start()
        synchronized(sessions) {
            if (sessions.size >= 2) release(sessions.keys.first())
            sessions[session.url] = session
        }
        try {
            checkActive()
            preparation.handoff()
        } catch (error: Exception) {
            release(session.url)
            throw error
        }
        cleanup
        val hints = stream.behaviorHints ?: StreamBehaviorHints(
            notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null
        )
        return stream.copy(url = session.url, usenet = null,
            behaviorHints = hints.copy(filename = file.name, videoSize = file.size, proxyHeaders = null))
    }
}
