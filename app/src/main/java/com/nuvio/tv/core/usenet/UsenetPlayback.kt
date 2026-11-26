package com.nuvio.tv.core.usenet

import android.content.Context
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
    private val originalStreams = LinkedHashMap<String, Stream>()
    private val reportedDirectPlayback = mutableSetOf<String>()

    // Cast may continue reading after the results screen or local player releases its ownership.
    private val readers = mutableMapOf<String, Int>()
    private val released = mutableSetOf<String>()

    fun retain(url: String): java.io.Closeable? = synchronized(sessions) {
        if (url !in sessions) return null
        readers[url] = readers.getOrDefault(url, 0) + 1
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        java.io.Closeable {
            synchronized(sessions) {
                if (closed.compareAndSet(false, true)) {
                    val remaining = readers.getValue(url) - 1
                    if (remaining > 0) readers[url] = remaining else {
                        readers.remove(url)
                        if (released.remove(url)) release(url)
                    }
                }
            }
        }
    }

    fun originalStream(url: String): Stream? = synchronized(sessions) { originalStreams[url] }
    fun hasSession(url: String): Boolean = synchronized(sessions) { sessions.containsKey(url) }
    private val cleanup by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Usenet session cleanup").apply { isDaemon = true } }.apply {
            scheduleWithFixedDelay({
                val idle = synchronized(sessions) { sessions.values.filter { it.isIdle() && it.url !in readers }.map { it.url } }
                idle.forEach(::release)
            }, 1, 1, TimeUnit.MINUTES)
        }
    }

    fun release(url: String) {
        val session = synchronized(sessions) {
            if (url in readers) {
                released.add(url)
                return
            }
            originalStreams.remove(url)
            reportedDirectPlayback.remove(url)
            sessions.remove(url)
        } ?: return
        cleanup.execute { session.close() }
    }

    fun onFirstFrame(url: String, context: Context) {
        val release = synchronized(sessions) {
            originalStreams[url]?.takeIf { it.isDirectUsenet && reportedDirectPlayback.add(url) }
        } ?: return
        cleanup.execute {
            runCatching {
                UnplayableReleases.get(context).recordPlayed(release)
                UsenetSourceStats.get(context).played(release.usenet?.nzbUrl)
                UsenetSourceStats.get(context).flush()
            }
        }
    }

    // Prefer the episode coordinates sent to the add-on. TVDB display and tracking order can differ.
    internal fun requestEpisode(videoId: String?, season: Int?, episode: Int?): Pair<Int?, Int?> {
        if (episode == null) return null to null
        val parts = videoId.orEmpty().split(':')
        val prefixSize = if (parts.first().startsWith("tt") || parts.first().toLongOrNull() != null) 1 else 2
        if (parts.size >= prefixSize + 2) {
            val s = parts[parts.lastIndex - 1].toIntOrNull()
            val e = parts.last().toIntOrNull()
            if (s != null && s >= 0 && e != null && e > 0) return s to e
        }
        return season to episode
    }

    suspend fun resolve(
        stream: Stream, videoId: String? = null, season: Int? = null, episode: Int? = null, context: Context? = null
    ): Stream {
        val trace = UsenetStartupTrace()
        var success = false
        val (requestedSeason, requestedEpisode) = requestEpisode(videoId, season, episode)
        val (engineSeason, engineEpisode) = engineEpisode(stream, requestedSeason, requestedEpisode)
        var awaitingHandoff: Stream? = null
        try {
            val result = kotlinx.coroutines.withTimeoutOrNull(120_000L) {
                resolveWithinDeadline(stream, engineSeason, engineEpisode, trace, context).also { awaitingHandoff = it }
            } ?: throw UsenetFailure("Timed out preparing this Usenet release.")
            awaitingHandoff = null
            success = true
            return result
        } catch (error: MissingUsenetArticle) {
            if (context != null && stream.isDirectUsenet) cleanup.execute {
                runCatching {
                    UnplayableReleases.get(context).record(stream, "missing-article")
                    UsenetSourceStats.get(context).unavailable(stream.usenet?.nzbUrl)
                    UsenetSourceStats.get(context).flush()
                }
            }
            throw error
        } finally {
            trace.finish(success)
            awaitingHandoff?.url?.let(::release)
        }
    }

    private suspend fun resolveWithinDeadline(stream: Stream, season: Int?, episode: Int?, trace: UsenetStartupTrace, context: Context?): Stream = suspendCancellableCoroutine { continuation ->
        val preparation = UsenetPreparation()
        continuation.invokeOnCancellation { preparation.close() }
        Dispatchers.IO.asExecutor().execute {
            try {
                val prepared = prepare(stream, season, episode, preparation, trace, context) { continuation.context.ensureActive() }
                continuation.resume(prepared, onCancellation = { _, value, _ -> value.url?.let(::release) })
            } catch (error: Exception) {
                preparation.close()
                val failure = if (error is CancellationException || error is UsenetFailure) error
                    else UsenetFailure("Could not open this Usenet stream.")
                continuation.resumeWith(Result.failure(failure))
            }
        }
    }

    private fun prepare(stream: Stream, season: Int?, episode: Int?, preparation: UsenetPreparation, trace: UsenetStartupTrace, context: Context?, checkActive: () -> Unit): Stream {
        val source = requireNotNull(stream.usenet)
        val profile = UsenetMemoryProfile.current
        val directSettings = context?.takeIf { stream.isDirectUsenet }?.let { UsenetSettings.read(it) }
        val articles = NewsArticles(source.servers, trace = trace,
            allowPrivateNetwork = directSettings?.allowPrivateNetwork ?: source.allowPrivateNetwork)
        preparation.own(articles)
        articles.warm(minOf(2, profile.window + 1))
        trace.mark("nzb_request")
        val document = NzbDownload.fetch(source.nzbUrl, stream.behaviorHints?.proxyHeaders?.request.orEmpty(), preparation, trace)
        if (stream.isDirectUsenet && context != null) {
            runCatching { UsenetSourceStats.get(context).grabbed(source.nzbUrl) }
        }
        trace.mark("nzb_received")
        trace.add("files", document.files.size.toLong())
        checkActive()
        var preparationCheck: (() -> Unit)? = checkActive
        val cache = ArticleCache(
            fetchAhead = if (articles.canPrefetch) articles::fetchAhead else null,
            profile = profile, trace = trace
        ) { id, next ->
            preparationCheck?.invoke()
            articles.fetch(id, next)
        }
        trace.mark("selection_started")
        val file = UsenetSelection.select(document.files, cache, stream.fileIdx, source.filters, season, episode, document.password) {
            preparationCheck?.invoke()
        }
        trace.mark("selection_done")
        checkActive()
        preparationCheck = null
        val session = UsenetHttpSession(file, articles, trace)
        preparation.own(java.io.Closeable { cleanup.execute { session.close() } })
        session.start()
        trace.mark("session_started")
        synchronized(sessions) {
            if (sessions.size >= 2) {
                val oldest = sessions.keys.firstOrNull { it !in readers }
                    ?: throw UsenetFailure("All Usenet sessions are in use.")
                release(oldest)
            }
            sessions[session.url] = session
            originalStreams[session.url] = stream
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
