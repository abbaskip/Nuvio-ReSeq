package com.nuvio.tv.ui.screens.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.TvHiltTestActivity
import com.nuvio.tv.core.tracking.watchedRatingRequest
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchedRatingPromptTest {
    @get:Rule val compose = createAndroidComposeRule<TvHiltTestActivity>()

    @Test
    fun pendingRequestCanResolveInjectedRatingsUnderTheNavigationSessionOwner() {
        val consumed = AtomicBoolean(false)
        val sessionOwner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val activity = compose.activity
        val request = watchedRatingRequest(-1, "tt0123456", "movie", "Movie", watched = true, hasError = false)
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides sessionOwner) {
                // A different profile must be discarded, even when the prompt setting is on.
                WatchedRatingPromptHost(request, activity, true) {
                    consumed.set(true)
                }
            }
        }
        compose.waitUntil(5_000) { consumed.get() }
    }
}
