package com.nuvio.tv.updater

import android.content.Context
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import com.nuvio.tv.updater.model.AppUpdate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

class UpdateCheckFrequencyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun intervalsRespectTheExactBoundary() {
        val frequencies = mapOf(UpdateCheckFrequency.DAILY to 1, UpdateCheckFrequency.TWO_DAYS to 2,
            UpdateCheckFrequency.THREE_DAYS to 3, UpdateCheckFrequency.WEEKLY to 7)
        for ((frequency, days) in frequencies) {
            val interval = TimeUnit.DAYS.toMillis(days.toLong())
            assertEquals(interval, frequency.backgroundIntervalMs)
            assertFalse(frequency.isDue(100, 100 + interval - 1))
            assertTrue(frequency.isDue(100, 100 + interval))
            assertTrue(frequency.isDue(100, 99))
        }
        assertTrue(UpdateCheckFrequency.ALWAYS.isDue(100, 100))
        assertFalse(UpdateCheckFrequency.NEVER.isDue(0, 100))
        assertNull(UpdateCheckFrequency.NEVER.backgroundIntervalMs)
    }

    @Test fun manualAndAutomaticChecksShareTheSavedResultAndRespectNever() = runBlocking {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns temp.root
        val preferences = UpdatePreferences(context)
        val api = mockk<GitHubReleaseApi>()
        coEvery { api.getLatestRelease(any(), any()) } returns Response.success(GitHubReleaseDto())
        val repository = UpdateRepository(api, preferences)
        assertEquals(UpdateCheckFrequency.ALWAYS, preferences.checkFrequency.first())
        preferences.setUpdateBannerEnabled(false)
        assertEquals(UpdateCheckFrequency.NEVER, preferences.checkFrequency.first())
        preferences.setAutoInstallEnabled(true)
        assertEquals(UpdateCheckFrequency.ALWAYS, preferences.checkFrequency.first())
        preferences.setCheckFrequency(UpdateCheckFrequency.NEVER)
        assertNull(repository.getLatestUpdate())
        coVerify(exactly = 0) { api.getLatestRelease(any(), any()) }
        assertTrue(repository.getLatestUpdate(force = true)?.exceptionOrNull() is NoEligibleUpdateException)

        preferences.setCheckFrequency(UpdateCheckFrequency.WEEKLY)
        assertTrue(repository.getLatestUpdate()?.exceptionOrNull() is NoEligibleUpdateException)
        val release = AppUpdate("v2", "Update", "Release notes", null, "test.apk", "https://example.invalid/test.apk", null)
        preferences.recordCheck(release)
        assertEquals(release, repository.getLatestUpdate()?.getOrThrow())
        coVerify(exactly = 1) { api.getLatestRelease(any(), any()) }
        preferences.setCheckFrequency(UpdateCheckFrequency.ALWAYS)
        repository.getLatestUpdate()
        coVerify(exactly = 2) { api.getLatestRelease(any(), any()) }
    }
}
