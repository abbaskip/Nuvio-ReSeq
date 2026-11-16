package com.nuvio.tv.updater

import android.app.Application
import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UpdateRelaunchReceiverTest {
    @Test fun cancellationClearsTheOneShotRequest() {
        val app = RuntimeEnvironment.getApplication()
        UpdateRelaunchReceiver.requestReopen(app)
        UpdateRelaunchReceiver.cancelReopen(app)
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } answers { app.getSharedPreferences(firstArg(), secondArg()) }
        UpdateRelaunchReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test fun anUnrequestedSideloadDoesNotOpenTheApp() {
        val app = RuntimeEnvironment.getApplication()
        UpdateRelaunchReceiver.cancelReopen(app)
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } answers { app.getSharedPreferences(firstArg(), secondArg()) }
        UpdateRelaunchReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        verify(exactly = 0) { context.startActivity(any()) }
    }
}
