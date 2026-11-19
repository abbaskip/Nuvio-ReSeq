package com.nuvio.tv.baselineprofile

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SportsFirstCardTest {
    @Test fun coldLaunchToRenderedFixture() {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val pkg = "io.github.abbaskip.nuvioreseq.perfcomparison"
        val component = "$pkg/com.nuvio.tv.profiling.ReSeqJourneyActivity"
        device.executeShellCommand("am start -W -S -n $component --es journey seed")
        check(device.wait(Until.hasObject(By.text("Fixture ready")), 70_000L)) { "Seed did not finish" }
        val config = Configurator.getInstance()
        val idle = config.getWaitForIdleTimeout()
        try {
            config.setWaitForIdleTimeout(0L)
            repeat(3) { trial ->
                device.executeShellCommand("am force-stop $pkg")
                check(device.executeShellCommand("pidof $pkg").trim().isEmpty())
                device.pressHome()
                val started = SystemClock.elapsedRealtime()
                device.executeShellCommand("am start -n $component --es journey sports")
                var card: UiObject2? = null
                while (card == null && SystemClock.elapsedRealtime() - started < 45_000L) {
                    card = device.findObjects(By.text("Fixture Falcons")).lastOrNull()
                    if (card == null) SystemClock.sleep(70)
                }
                val visible = SystemClock.elapsedRealtime() - started
                val target = checkNotNull(card) { "The first fixture card did not render" }
                target.click()
                check(device.wait(Until.hasObject(By.text("WATCH ON YOUR CHANNELS")), 35_000L))
                check(device.hasObject(By.text("Fixture Owls")) && device.hasObject(By.text("Fixture Falcons")))
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("stream", "\nRESEQ_FIRST_CARD $trial $visible\n")
                })
            }
        } finally {
            config.setWaitForIdleTimeout(idle)
        }
    }
}
