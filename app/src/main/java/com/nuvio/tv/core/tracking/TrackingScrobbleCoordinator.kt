package com.nuvio.tv.core.tracking

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

data class TrackingScrobbleFailure(
    val providerId: TrackingProviderId,
    val cause: Throwable
)

@Singleton
class TrackingScrobbleCoordinator @Inject constructor(
    private val providerRegistry: TrackingProviderRegistry,
    private val tvdb: com.nuvio.tv.data.tvdb.TvdbTrackingResolver? = null
) {
    suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> {
        val scrobblers = providerRegistry.connectedScrobblers()
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "coordinator dispatch action=${action.wireValue} providers=" +
                scrobblers.joinToString(prefix = "[", postfix = "]") { it.providerId.storageId } +
                " ${event.scrobbleDiagnosticSummary()}"
        )
        return dispatch(
            scrobblers = scrobblers,
            action = action,
            event = event
        ).also { failures ->
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "coordinator complete action=${action.wireValue} failures=" +
                    failures.joinToString(prefix = "[", postfix = "]") { it.providerId.storageId } +
                    " ${event.scrobbleDiagnosticSummary()}"
            )
        }
    }

    suspend fun scrobbleSeek(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> = dispatchSeek(
        scrobblers = providerRegistry.connectedScrobblers(),
        action = action,
        event = event
    )

    /** Called only when the player's existing tick crosses a verified content boundary. */
    suspend fun scrobbleBoundary(previous: TrackingScrobbleEvent, current: TrackingScrobbleEvent): List<TrackingScrobbleFailure> {
        val resolver = tvdb ?: return emptyList()
        val beforeCatalog = previous.media.catalog ?: return emptyList()
        val afterCatalog = current.media.catalog ?: return emptyList()
        val context = beforeCatalog.tvdbContext ?: return emptyList()
        if (afterCatalog.tvdbContext != context || beforeCatalog.contentId != afterCatalog.contentId ||
            !beforeCatalog.tvdbCoverageVerified || !afterCatalog.tvdbCoverageVerified) return emptyList()
        return supervisorScope {
            providerRegistry.connectedScrobblers().map { delegate -> async {
                try {
                    val before = resolver.scrobbleEvents(delegate.providerId, TrackingScrobbleAction.PAUSE, previous, progressOnly = true)
                    val after = resolver.scrobbleEvents(delegate.providerId, TrackingScrobbleAction.START, current, progressOnly = true)
                    val old = before?.singleOrNull() ?: return@async null
                    val next = after?.singleOrNull() ?: return@async null
                    if (old.second.media == next.second.media) return@async null
                    delegate.scrobble(old.first, old.second)
                    delegate.scrobble(next.first, next.second)
                    null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { TrackingScrobbleFailure(delegate.providerId, error) }
            } }.awaitAll().filterNotNull()
        }
    }

    private suspend fun dispatch(
        scrobblers: Collection<TrackingScrobbler>,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> = dispatchTrackingScrobble(resolved(scrobblers), action, event)
        .also { failures -> failures.log(action, false) }

    private suspend fun dispatchSeek(
        scrobblers: Collection<TrackingScrobbler>,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> = dispatchTrackingSeekScrobble(resolved(scrobblers), action, event)
        .also { failures -> failures.log(action, true) }

    private fun resolved(scrobblers: Collection<TrackingScrobbler>) = if (tvdb == null) scrobblers else scrobblers.map { delegate ->
        object : TrackingScrobbler by delegate {
            override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
                val events = tvdb.scrobbleEvents(delegate.providerId, action, event)
                    ?: throw IllegalStateException("TVDB episode mapping unresolved; remote scrobble withheld")
                events.forEach { (mappedAction, mappedEvent) -> delegate.scrobble(mappedAction, mappedEvent) }
            }
        }
    }

    private fun List<TrackingScrobbleFailure>.log(
        action: TrackingScrobbleAction,
        seek: Boolean
    ) {
        forEach { failure ->
            Log.w(
                "TrackingScrobble",
                "${failure.providerId.storageId} ${if (seek) "seek " else ""}scrobble ${action.wireValue} failed",
                failure.cause
            )
        }
    }
}

suspend fun dispatchTrackingScrobble(
    scrobblers: Collection<TrackingScrobbler>,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent
): List<TrackingScrobbleFailure> = supervisorScope {
    scrobblers.map { scrobbler ->
        async {
            try {
                scrobbler.scrobble(action, event)
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                TrackingScrobbleFailure(scrobbler.providerId, error)
            }
        }
    }.awaitAll().filterNotNull()
}

suspend fun dispatchTrackingSeekScrobble(
    scrobblers: Collection<TrackingScrobbler>,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent
): List<TrackingScrobbleFailure> = dispatchTrackingScrobble(
    scrobblers = scrobblers.filter { it.seekScrobblePolicy == TrackingSeekScrobblePolicy.STOP_AND_RESTART },
    action = action,
    event = event
)
