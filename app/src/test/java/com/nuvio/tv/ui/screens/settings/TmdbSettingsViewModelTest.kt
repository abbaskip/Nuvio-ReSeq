package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbGenresResponse
import com.nuvio.tv.domain.model.TmdbSettings
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class TmdbSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val profiles = MutableStateFlow(1)
    private val stored = mutableMapOf<Int, String?>()
    private val protected = mockk<ProtectedProfilePreferences> {
        every { read(any()) } answers { stored[firstArg()] }
        every { write(any(), any()) } answers { stored[firstArg()] = secondArg(); Unit }
    }
    private val credentials = TmdbCredentialStore(protected)
    private val api = mockk<TmdbApi>()
    private val key = "f".repeat(32)
    private val models = mutableListOf<TmdbSettingsViewModel>()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        runTest(dispatcher) {
            models.forEach { it.viewModelScope.cancel() }
            models.forEach { it.viewModelScope.coroutineContext[Job]?.join() }
        }
        Dispatchers.resetMain()
    }

    private fun viewModel() = TmdbSettingsViewModel(
        mockk<TmdbSettingsDataStore> { every { settings } returns MutableStateFlow(TmdbSettings()) },
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), credentials,
        mockk<ProfileManager> { every { activeProfileId } returns this@TmdbSettingsViewModelTest.profiles }, api
    ).also { models += it }

    @Test
    fun `valid key is checked then saved to the current profile without entering public UI state`() = runTest(dispatcher) {
        coEvery { api.getMovieGenres(any(), any()) } returns Response.success(TmdbGenresResponse(emptyList()))
        val vm = viewModel()
        runCurrent()
        var saved = false
        vm.saveApiKey(" $key ") { saved = true }
        vm.uiState.first { !it.savingApiKey }
        advanceUntilIdle()
        assertTrue(saved)
        assertEquals(key, credentials.access(1).apiKey)
        assertTrue(vm.uiState.value.hasApiKey)
        assertFalse(vm.uiState.value.toString().contains(key))
        assertFalse(vm.uiState.value.savingApiKey)
        coVerify(exactly = 1) { api.getMovieGenres(key, "en") }
    }

    @Test
    fun `rejected replacement leaves the existing key intact`() = runTest(dispatcher) {
        credentials.save(1, "a".repeat(32))
        coEvery { api.getMovieGenres(any(), any()) } returns Response.error(401, "rejected".toResponseBody())
        val vm = viewModel()
        runCurrent()
        var saved = false
        vm.saveApiKey(key) { saved = true }
        vm.uiState.first { !it.savingApiKey }
        advanceUntilIdle()
        assertFalse(saved)
        assertEquals("a".repeat(32), credentials.access(1).apiKey)
        assertEquals(TmdbApiKeyError.INVALID, vm.uiState.value.apiKeyError)
    }

    @Test
    fun `read token cannot accidentally be saved as a v3 key`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.saveApiKey("eyJ.synthetic.read-token") { error("Invalid key saved") }
        advanceUntilIdle()
        assertEquals(TmdbApiKeyError.INVALID, vm.uiState.value.apiKeyError)
        coVerify(exactly = 0) { api.getMovieGenres(any(), any()) }
    }

    @Test
    fun `stale dialog actions cannot save or clear another profiles key`() = runTest(dispatcher) {
        credentials.save(2, "a".repeat(32))
        val vm = viewModel()
        runCurrent()
        profiles.value = 2
        runCurrent()
        vm.saveApiKey(key, expectedProfileId = 1) { error("Stale key saved") }
        vm.clearApiKey(expectedProfileId = 1) { error("Other profile key cleared") }
        advanceUntilIdle()
        assertEquals("a".repeat(32), credentials.access(2).apiKey)
        assertTrue(credentials.access(1).apiKey.isBlank())
        coVerify(exactly = 0) { api.getMovieGenres(any(), any()) }
    }

    @Test
    fun `clear affects only this profile and does not require validation`() = runTest(dispatcher) {
        credentials.save(1, key)
        credentials.save(2, "a".repeat(32))
        val vm = viewModel()
        runCurrent()
        vm.clearApiKey {}
        vm.uiState.first { !it.savingApiKey }
        advanceUntilIdle()
        assertTrue(credentials.access(1).apiKey.isBlank())
        assertEquals("a".repeat(32), credentials.access(2).apiKey)
        assertFalse(vm.uiState.value.hasApiKey)
        coVerify(exactly = 0) { api.getMovieGenres(any(), any()) }
    }

    @Test
    fun `changing profile during validation cannot persist or display the old profile key`() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val responseReady = CompletableDeferred<Unit>()
        coEvery { api.getMovieGenres(any(), any()) } coAnswers {
            started.complete(Unit)
            withContext(NonCancellable) { responseReady.await() }
            Response.success(TmdbGenresResponse(emptyList()))
        }
        val vm = viewModel()
        vm.uiState.first { it.profileId == 1 }
        val scopeJob = vm.viewModelScope.coroutineContext[Job]!!
        val observers = scopeJob.children.toSet()
        var saved = false
        vm.saveApiKey(key) { saved = true }
        val validation = scopeJob.children.single { it !in observers }
        try {
            started.await()
            profiles.value = 2
            vm.uiState.first { it.profileId == 2 }
        } finally {
            responseReady.complete(Unit)
        }
        validation.join()
        assertFalse(saved)
        assertTrue(credentials.access(1).apiKey.isBlank())
        assertTrue(credentials.access(2).apiKey.isBlank())
        assertEquals(2, vm.uiState.value.profileId)
        assertFalse(vm.uiState.value.hasApiKey)
    }
}
