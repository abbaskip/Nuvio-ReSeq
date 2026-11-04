package com.nuvio.tv.livetv.player

import org.junit.Assert.*
import org.junit.Test

class LiveStreamDiagnosticsTest {
    @Test fun `Stalker reports retain stream IDs but redact account and token values`() {
        val url = "http://provider.test/play/live.php?mac=synthetic-mac&stream=42&extension=ts&play_token=synthetic-token&sn2=synthetic-serial"
        val report = redactLiveStreamUrl(url)
        for (secret in listOf("synthetic-mac", "synthetic-token", "synthetic-serial")) assertFalse(report.contains(secret))
        assertTrue(report.contains("stream=42&extension=ts"))
        assertTrue(report.contains("play_token=***"))
    }

    @Test fun `exception text and Xtream paths also redact credentials`() {
        val text = "Failed http://user:secret@provider.test/live/account/password/42.ts?Token=synthetic-token&USERNAME=synthetic-user"
        val report = redactLiveStreamUrl(text)
        for (secret in listOf("user:secret", "account/password", "synthetic-token", "synthetic-user")) assertFalse(report.contains(secret))
        assertEquals("https://provider.test/***/***/42.ts", redactLiveStreamUrl("https://provider.test/account/password/42.ts"))
    }
}
