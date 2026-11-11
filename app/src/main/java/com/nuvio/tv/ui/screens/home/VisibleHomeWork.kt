package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Leaving Home cancels ongoing work and all its children, not merely future emissions. */
internal suspend fun runVisibleHomeWork(
    homeVisible: Flow<Boolean>,
    playerOpen: Flow<Boolean>,
    work: suspend CoroutineScope.() -> Unit
) {
    combine(homeVisible, playerOpen) { visible, playing -> visible && !playing }
        .distinctUntilChanged().collectLatest { allowed ->
            if (allowed) coroutineScope(work)
        }
}
