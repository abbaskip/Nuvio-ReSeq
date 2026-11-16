package com.nuvio.tv.data.cw

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** No placeholder projection: unknown scope is loading, not an empty library. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun scopedCwProjections(
    profiles: Flow<Int>,
    sources: (Int) -> Flow<String>,
    load: suspend (Int, String) -> CwProjection,
    updates: StateFlow<CwProjection?>
): Flow<CwProjection> = profiles.distinctUntilChanged().flatMapLatest { profile ->
    sources(profile).distinctUntilChanged().flatMapLatest { source ->
        flow {
            emit(load(profile, source))
            emitAll(updates.filterNotNull().filter { it.profileId == profile && it.source == source })
        }
    }
}.distinctUntilChanged()
