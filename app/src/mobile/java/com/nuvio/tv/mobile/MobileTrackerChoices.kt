package com.nuvio.tv.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRatings
import com.nuvio.tv.core.tracking.TrackingSourceController
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.LibrarySourceMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** Display a tap while the shared controller persists the selection and refreshes the provider. */
internal class MobileTrackerChoice<T>(
    private val scope: CoroutineScope,
    private val profile: StateFlow<Int>,
    stored: Flow<T>,
    initial: T,
    private val save: suspend (T) -> Unit
) {
    private class Request<T>(val value: T, val profileId: Int)
    private val pending = MutableStateFlow<Request<T>?>(null)
    private val failed = MutableStateFlow<Int?>(null)
    private val writes = Mutex()
    val selected = combine(stored, pending, profile) { saved, request, id ->
        if (request != null && request.profileId == id) request.value else saved
    }.stateIn(scope, SharingStarted.Eagerly, initial)
    val hasError = combine(failed, profile) { failedId, activeId -> failedId == activeId }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun select(value: T) {
        val request = Request(value, profile.value)
        failed.value = null
        pending.value = request
        scope.launch {
            try {
                writes.withLock {
                    if (pending.value === request && profile.value == request.profileId) save(value)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (pending.value === request) failed.value = request.profileId
            } finally {
                pending.compareAndSet(request, null)
            }
        }
    }
}

@HiltViewModel
internal class MobileTrackerChoices @Inject constructor(
    controller: TrackingSourceController, ratings: TrackingRatings, profiles: ProfileManager
) : ViewModel() {
    val progress = MobileTrackerChoice(viewModelScope, profiles.activeProfileId, controller.watchProgressSource,
        WatchProgressSource.NUVIO_SYNC, controller::selectWatchProgressSource)
    val library = MobileTrackerChoice(viewModelScope, profiles.activeProfileId, controller.librarySourceMode,
        LibrarySourceMode.LOCAL, controller::selectLibrarySourceMode)
    val rating = MobileTrackerChoice<TrackingProviderId?>(viewModelScope, profiles.activeProfileId, ratings.source,
        null, ratings::setSource)
}
