package com.nuvio.tv.baselineprofile

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.benchmark.macro.ArtMetric
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val ACTIVITY = "com.nuvio.tv.profiling.ReSeqJourneyActivity"
private const val TIMEOUT = 30_000L

private fun target(): String {
    assumeTrue(instrumentationArg("reseqFixtures") == "true")
    check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
    return resolveTargetPackage(uiDevice(), DEFAULT_TARGET_PACKAGE)
}

private fun seed(pkg: String) {
    val device = uiDevice()
    device.executeShellCommand("am start -W -S -n $pkg/$ACTIVITY --es journey seed")
    check(device.wait(Until.hasObject(By.text("Fixture ready")), 70_000)) { "Local provider import failed" }
    device.executeShellCommand("am force-stop $pkg")
}

private fun MacrobenchmarkScope.launchRoute(pkg: String, route: String) {
    startActivityAndWait(Intent().setComponent(ComponentName(pkg, ACTIVITY))
        .putExtra("journey", route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
}

private fun visible(selector: BySelector) {
    check(uiDevice().wait(Until.hasObject(selector), TIMEOUT)) { "Missing fixture content: $selector" }
}

private fun MacrobenchmarkScope.exercise(pkg: String, route: String) {
    val device = uiDevice()
    launchRoute(pkg, route)
    when (route) {
        "guide" -> {
            visible(By.text("Channel 001"))
            repeat(22) { device.pressDPadDown() }
            visible(By.text("Channel 023"))
        }
        "sports" -> {
            visible(By.text("Fixture Falcons"))
            device.findObjects(By.text("Fixture Falcons")).last().click()
            visible(By.text("SHOWING IT"))
        }
        "event" -> {
            visible(By.text("WATCH ON YOUR CHANNELS"))
            visible(By.text("SHOWING IT"))
        }
        "vod" -> {
            visible(By.text("Fixture movies"))
            repeat(8) { device.pressDPadRight() }
            visible(By.text("Fixture Movie 009"))
        }
        "usenet" -> visible(By.desc("Local article rendered"))
        else -> error("Unsupported profile journey: $route")
    }
}

@RunWith(AndroidJUnit4::class)
class ReSeqProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun collectFeatures() {
        val pkg = target()
        seed(pkg)
        rule.collect(packageName = pkg, includeInStartupProfile = false, maxIterations = 3, stableIterations = 2,
            filterPredicate = { rule ->
                val owner = rule.substringBefore("->").substringAfter('L')
                owner.startsWith("com/nuvio/tv/livetv/ui/") || owner.startsWith("com/nuvio/tv/livetv/data/") ||
                    owner.startsWith("com/nuvio/tv/livetv/model/") || owner.startsWith("com/nuvio/tv/livetv/sports/")
            }) {
            for (route in listOf("guide", "sports", "event", "vod", "usenet")) exercise(pkg, route)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class ReSeqProfileBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    private fun measure(route: String) {
        val pkg = target()
        seed(pkg)
        rule.measureRepeated(packageName = pkg,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric(), ArtMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.COLD, iterations = 7,
            setupBlock = { pressHome() }) { exercise(pkg, route) }
    }

    @Test fun liveGuide() = measure("guide")
    @Test fun sportsEvent() = measure("sports")
    @Test fun onDemand() = measure("vod")
    @Test fun nntpFirstFrame() = measure("usenet")
}
