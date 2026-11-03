package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.flow.filterNotNull

internal fun shouldRestoreCachedInProgress(
    hasLiveInProgress: Boolean,
    hasActiveTrackingProvider: Boolean,
    hasCachedInProgress: Boolean,
    hasProviderItems: Boolean,
    hasLoadedRemoteProgress: Boolean
): Boolean =
    !hasLiveInProgress &&
        hasActiveTrackingProvider &&
        hasCachedInProgress &&
        !hasProviderItems &&
        !hasLoadedRemoteProgress

/** Resume the latest snapshot on every player exit, even if its object identity is unchanged. */
internal fun <T : Any> kotlinx.coroutines.flow.Flow<T>.holdContinueWatchingWhilePlayerOpen(
    playerOpen: kotlinx.coroutines.flow.Flow<Boolean>
): kotlinx.coroutines.flow.Flow<T> = kotlinx.coroutines.flow.combine(this, playerOpen) { snapshot, open ->
    snapshot.takeUnless { open }
}.filterNotNull()
