package com.nuvio.tv.mobile

import android.os.Build
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.ServerConfiguration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

internal data class MobileLinkStatus(val code: String? = null, val address: String? = null,
    val message: String = "", val busy: Boolean = false)

@HiltViewModel
internal class MobileAccountLink @Inject constructor(
    private val auth: AuthManager,
    private val config: ServerConfiguration,
    private val sync: StartupSyncService,
    private val saved: SavedStateHandle
) : ViewModel() {
    private val status = MutableStateFlow(MobileLinkStatus())
    val ui = status.asStateFlow()
    private val visible = MutableStateFlow(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    init {
        saved.get<Bundle>(SESSION)?.let { pending ->
            if (pending.getString("server") == config.backendUrl && auth.authState.value !is AuthState.FullAccount) {
                job = viewModelScope.launch {
                    val initial = auth.authState.first { it !is AuthState.Loading }
                    if (initial is AuthState.FullAccount) finish() else awaitApproval(pending)
                }
            } else saved.remove<Bundle>(SESSION)
        }
    }

    fun foreground(active: Boolean) {
        visible.value = active
        if (active) checkNow()
    }

    fun checkNow() { wake.trySend(Unit) }

    fun start() {
        if (job?.isActive == true || auth.authState.value is AuthState.FullAccount) return
        status.value = MobileLinkStatus(message = "Requesting a sign-in code…", busy = true)
        job = viewModelScope.launch {
            val nonce = UUID.randomUUID().toString()
            val result = auth.startDeviceLoginSession(nonce, Build.MODEL, "mobile",
                config.deviceLoginWebBaseUrl.orEmpty(), config.tvLoginWebBaseUrl.orEmpty())
            currentCoroutineContext().ensureActive()
            val session = result.getOrNull()
            if (session == null || session.deviceCode.isBlank() || session.userCode.isBlank() ||
                session.verificationUriComplete.isBlank()) {
                finish("Could not start Nuvio sign-in. Request a new code to try again.")
                return@launch
            }
            val expiry = runCatching { Instant.parse(session.expiresAt).toEpochMilli() }.getOrNull()
                ?: runCatching { java.time.OffsetDateTime.parse(session.expiresAt).toInstant().toEpochMilli() }.getOrNull()
            val pending = Bundle().apply {
                putString("server", config.backendUrl)
                putString("nonce", nonce)
                putString("device", session.deviceCode)
                putString("user", session.userCode)
                putString("url", session.verificationUriComplete)
                putLong("expires", expiry ?: (System.currentTimeMillis() + 10 * 60_000L))
                putInt("interval", session.pollIntervalSeconds)
            }
            saved[SESSION] = pending
            awaitApproval(pending)
        }
    }

    private suspend fun awaitApproval(pending: Bundle) {
        val device = pending.getString("device") ?: return finish("Request a new sign-in code.")
        val nonce = pending.getString("nonce") ?: return finish("Request a new sign-in code.")
        val waiting = MobileLinkStatus(pending.getString("user"), pending.getString("url"), "Waiting for Nuvio approval…")
        status.value = waiting
        var interval = pending.getInt("interval", 3).coerceIn(2, 30)
        while (true) {
            visible.first { it }
            wake.tryReceive()
            if (auth.authState.value is AuthState.FullAccount) return finish()
            val remaining = pending.getLong("expires") - System.currentTimeMillis()
            if (remaining <= 0) return finish("This sign-in code expired. Request a new code.")
            val result = auth.pollTvLoginSession(device, nonce)
            currentCoroutineContext().ensureActive()
            val response = result.getOrNull()
            if (response == null) {
                status.value = waiting.copy(message = "Could not check approval. ReSeq will retry.")
            } else {
                interval = (response.pollIntervalSeconds ?: interval).coerceIn(2, 30)
                when (response.status.trim().lowercase()) {
                    "pending" -> status.value = waiting
                    "approved" -> {
                        status.value = MobileLinkStatus(message = "Approved. Connecting your account…", busy = true)
                        val exchange = auth.exchangeTvLoginSession(device, nonce)
                        currentCoroutineContext().ensureActive()
                        if (exchange.isFailure) return finish("Nuvio approved the code, but sign-in failed. Request a new code.")
                        val account = withTimeoutOrNull(30_000) { auth.authState.first { it is AuthState.FullAccount } }
                        return if (account != null) finish() else finish("Could not confirm the account session. Request a new code.")
                    }
                    "expired", "used", "cancelled", "denied", "rejected" ->
                        return finish("This sign-in code is no longer active. Request a new code.")
                    else -> return finish("Nuvio could not complete this sign-in. Request a new code.")
                }
            }
            withTimeoutOrNull(minOf(remaining, interval * 1000L)) { wake.receive() }
        }
    }

    fun cancel() {
        if (status.value.busy) return
        job?.cancel()
        job = null
        finish()
    }

    fun syncNow() {
        if (auth.authState.value is AuthState.FullAccount) sync.requestSyncNow()
    }

    private fun finish(message: String = "") {
        saved.remove<Bundle>(SESSION)
        status.value = MobileLinkStatus(message = message)
    }

    private companion object { const val SESSION = "pendingNuvioMobileLink" }
}
