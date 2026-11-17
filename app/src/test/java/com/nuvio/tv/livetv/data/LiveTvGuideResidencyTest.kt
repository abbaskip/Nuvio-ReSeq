package com.nuvio.tv.livetv.data

import android.app.Application
import com.nuvio.tv.livetv.model.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28],application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiveTvGuideResidencyTest {
    @Test fun `opening a populated fifty thousand channel guide reads bounded windows and far rows still load`() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val settings=LiveTvSettings()
        val prefs=mockk<LiveTvPreferences>(relaxed=true)
        coEvery { prefs.currentPlaylists() } returns emptyList()
        coEvery { prefs.currentSettings() } returns settings
        every { prefs.userState } returns flowOf(LiveUserState())
        val db=mockk<EpgDatabase>(relaxed=true)
        val repo=LiveTvRepository(context,prefs,db,mockk(relaxed=true))
        val scopeField=LiveTvRepository::class.java.getDeclaredField("scope").apply { isAccessible=true }
        try {
            val channels=(0 until 50_001).map { n -> LiveChannel("c$n","fixture","Fixture","Long channel $n",null,null,null,"g","Group",n,"https://example.invalid") }
            val field=LiveTvRepository::class.java.getDeclaredField("_channels").apply { isAccessible=true }
            @Suppress("UNCHECKED_CAST")
            (field.get(repo) as MutableStateFlow<List<LiveChannel>>).value=channels
            val functions=LiveTvRepository::class.declaredMemberFunctions
            val fingerprint=functions.single { it.name=="guideFingerprint" }.apply { isAccessible=true }
                .callSuspend(repo,settings,emptyList<Any>()) as String
            every { db.fingerprint() } returns fingerprint
            every { db.autoMatches() } returns channels.associate { it.key to EpgAssignment("guide",it.key) }
            val now=System.currentTimeMillis()
            val programme=EpgProgram(now-1000,now+60_000,"Long pathological title".repeat(8))
            every { db.range(any(),any(),any(),any(),any()) } answers {
                val keys=arg<Collection<String>?>(4)
                check(keys!=null && keys.size<=600) { "Unbounded guide payload read: ${keys?.size}" }
                keys.associateWith { listOf(programme) }
            }
            val loaded=functions.single { it.name=="loadGuideFromDb" }.apply { isAccessible=true }
                .callSuspend(repo,settings,emptyList<Any>(),null)
            assertEquals(true,loaded)
            withTimeout(5_000) { repo.programs.first { it.size==600 } }
            assertEquals(50_001,repo.channels.value.size)
            val far=channels.last().key
            repo.loadChannelsNow(listOf(far))
            withTimeout(5_000) { repo.programs.first { far in it } }
            assertEquals(600,repo.programs.value.size)
            assertEquals(listOf(programme),repo.programs.value[far])
            verify(exactly=0) { db.range(any(),any(),any(),any(),isNull()) }
        } finally { (scopeField.get(repo) as CoroutineScope).cancel() }
    }
}
