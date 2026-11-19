package com.nuvio.tv.baselineprofile

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class SportsDeliveryTest {
    @Test fun measureColdSportsAndReminder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val pkg = "io.github.abbaskip.nuvioreseq.debug"
        val fixture = "$pkg/com.nuvio.tv.profiling.StabilityJourneyActivity"
        fun shell(command: String) = device.executeShellCommand(command).trim()
        fun now() = SystemClock.elapsedRealtime()
        fun journal(): List<JSONObject> {
            val pid = shell("pidof $pkg")
            check(pid.matches(Regex("[0-9]+"))) { "Fixture process unavailable" }
            return shell("logcat -d -v raw --pid=$pid -s NuvioStability:I").lineSequence()
                .filter { it.startsWith('{') }.map { JSONObject(it) }.toList()
        }
        fun event(kind: String, after: Long, wait: Long, condition: (JSONObject) -> Boolean = { true }): JSONObject {
            val deadline = now() + wait
            while (now() < deadline) {
                val entries = journal().filter { it.optLong("elapsedMs") >= after }
                check(entries.none { it.optString("kind") == "journey-failed" }) { "Fixture journey failed" }
                entries.firstOrNull { it.optString("kind") == kind && condition(it) }?.let { return it }
                SystemClock.sleep(400)
            }
            error("Missing $kind after $after")
        }
        fun stopTarget() {
            shell("am force-stop $pkg")
            check(shell("pidof $pkg").isEmpty())
            device.pressHome()
        }
        fun text(value: String) = By.text(Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE))
        val config = Configurator.getInstance()
        val oldIdle = config.waitForIdleTimeout
        try {
            config.waitForIdleTimeout = 0
            val seedStart = now()
            shell("am start -W -n $fixture --es journey seed")
            event("seed-complete", seedStart, 245_000) { it.getInt("channels") >= 2000 && it.getInt("movies") == 20_000 }
            stopTarget()
            val start = now()
            shell("am start -n $fixture --es journey sports")
            var firstTeams = ""
            val cardDeadline = now() + 100_000
            while (firstTeams.isEmpty() && now() < cardDeadline) {
                if (device.hasObject(text("Fixture Falcons")) && device.hasObject(text("Fixture Owls"))) firstTeams = "basketball"
                else if (device.hasObject(text("Australia")) && device.hasObject(text("England"))) firstTeams = "cricket"
                else SystemClock.sleep(90)
            }
            check(firstTeams.isNotEmpty()) { "Game card missing" }
            val rendered = now() - start
            val first = event("sports-progress", start, 90_000) { it.optInt("events") > 0 }
            val match = event("sports-progress", start, 120_000) { it.optBoolean("hasChannel") }
            val prep = now()
            shell("am start -W -n $fixture --es journey prepare-cold-reminder")
            val saved = event("cold-reminder-saved", prep, 100_000)
            check(saved.getString("eventId") == "espn:basketball/nba:80000")
            stopTarget()
            val reminderStart = now()
            shell("am start -n $pkg/com.nuvio.tv.MainActivity")
            val button = checkNotNull(device.wait(Until.findObject(text("Open in Sports")), 100_000))
            val dialog = now() - reminderStart
            button.click()
            check(device.wait(Until.hasObject(text("Watch on your channels")), 60_000))
            check(device.hasObject(text("Fixture Falcons")) && device.hasObject(text("Fixture Owls")))
            val result = JSONObject().put("eventMs", first.getLong("elapsedMs")-start)
                .put("channelMs", match.getLong("elapsedMs")-start).put("cardMs", rendered)
                .put("dialogMs", dialog).put("gameMs", now()-reminderStart)
                .put("firstCount", first.getInt("events")).put("firstTeams", firstTeams)
                .put("firstMatchedEvents", match.getInt("matches"))
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\nSPORTS_DELIVERY $result\n") })
        } finally {
            config.waitForIdleTimeout = oldIdle
        }
    }
}
