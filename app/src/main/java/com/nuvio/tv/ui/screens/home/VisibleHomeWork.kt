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
    var previousChange = System.nanoTime()
    combine(homeVisible, playerOpen) { visible, playing -> visible && !playing }
        .distinctUntilChanged().collectLatest { allowed ->
            if (com.nuvio.tv.core.diagnostics.StabilityDiagnostics.enabled) {
                val now = System.nanoTime()
                com.nuvio.tv.core.diagnostics.StabilityDiagnostics.event("gate-state", mapOf("gate" to "visible-home", "allowed" to allowed,
                    "previousStateMs" to (now - previousChange) / 1_000_000))
                previousChange = now
            }
            if (allowed) coroutineScope(work)
        }
}
