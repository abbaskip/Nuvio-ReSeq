package com.nuvio.tv.core.report

import com.nuvio.tv.livetv.crash.ReportServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProblemReportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `redacts credentials URLs network addresses and encoded variants before display`() {
        val raw = """
            https://xtream-user:password-value@provider.example/live/xtream-user/password-value/123.ts?token=token-value
            provider.example/live/xtream-user/password-value/123.ts
            {"access_token":"access-value","api_key":"key-value","password":"secret-value","username":"user-value"}
            Authorization: Bearer bearer-value
            Basic YmFkOmNyZWRlbnRpYWxz
            email=person@example.com mac=00:1A:79:AA:BB:CC
            192.168.1.3:5555 [2001:db8::1234] https://10.0.0.1:8080/account
            https%3A%2F%2Fprovider.example%2Flive%2Fencoded-user%2Fencoded-password
            https:\/\/provider.example\/live\/escaped-user\/escaped-password
            video codec=video/avc coded size=1920x1080 frame rate=25.0
        """.trimIndent()
        val cleaned = ReportRedactor.clean(raw)
        listOf("xtream-user", "password-value", "token-value", "access-value", "key-value", "secret-value",
            "user-value", "bearer-value", "YmFkOmNyZWRlbnRpYWxz", "person@example.com", "00:1A:79:AA:BB:CC",
            "192.168.1.3", "2001:db8", "10.0.0.1", "encoded-user", "encoded-password", "escaped-user", "escaped-password").forEach {
            assertFalse("Leaked $it in $cleaned", cleaned.contains(it))
        }
        assertTrue(cleaned.contains("provider.example"))
        assertTrue(cleaned.contains("1920x1080"))
        assertTrue(cleaned.contains("video/avc"))
    }

    @Test fun `redaction is idempotent and leaves only provider host`() {
        val input = "https://name:secret@provider.example:8443/live/user/pass/4?apikey=secret"
        assertEquals("provider.example", ReportRedactor.clean(input))
        assertEquals("[IP]", ReportRedactor.host("http://192.168.1.3/live/user/pass"))
        assertEquals("[IP]", ReportRedactor.host("http://[2001:db8::1]/live/user/pass"))
        val once = ReportRedactor.clean("password=hidden Authorization: Bearer hidden email=foo@example.com")
        assertEquals(once, ReportRedactor.clean(once))
    }

    @Test fun `unlabelled account names and watched titles never enter warnings or saved crash text`() {
        val line = "11-18 10:25:26.123 123 456 W PrivateAccount: watched PrivateTitle using PrivateAccount"
        val warning = ReportRedactor.logLine(line)!!
        assertFalse(warning.contains("PrivateAccount")); assertFalse(warning.contains("PrivateTitle"))
        assertTrue(warning.contains("W application"))
        assertNull(ReportRedactor.logLine(line.replace(" W ", " I ")))
        val crash = ReportRedactor.crash("""
            APP PrivateAccount
            APP FROZE (PrivateTitle)
            java.lang.IllegalStateException: PrivateTitle PrivateAccount
                at com.nuvio.tv.Player.play(Player.kt:52)
                at com.nuvio.tv.Player.play(/accounts/PrivateAccount.kt:52)
        """.trimIndent())
        assertFalse(crash.contains("PrivateAccount")); assertFalse(crash.contains("PrivateTitle"))
        assertTrue(crash.contains("APP FROZE"))
        assertTrue(crash.contains("java.lang.IllegalStateException"))
        assertTrue(crash.contains("Player.kt:52"))
    }

    @Test fun `builder exports only technical fields and redacts every report section`() {
        val session = PlaybackReport("ExoPlayer", mapOf("coded size" to "1920x1080", "title" to "PrivateTitle",
            "provider host" to "https://provider.example/live/PrivateAccount/secret/123"), emptyList(), "PrivateSourceId")
        val report = ProblemReportBuilder.build(ProblemReportInput("0.1.0-beta.10", "Fixture TV",
            mapOf("memoryClass" to "256 MB", "account" to "PrivateAccount"), listOf("Multiview"), listOf("Trakt"),
            "TV guide: 12 MB", "java.lang.IllegalStateException: PrivateTitle", "password=hidden", listOf(session), session))
        listOf("PrivateTitle", "PrivateAccount", "PrivateSourceId", "hidden", "/live/").forEach { assertFalse(report.contains(it)) }
        assertTrue(report.contains("1920x1080")); assertTrue(report.contains("256 MB")); assertTrue(report.contains("Trakt"))
        assertTrue(report.contains("not reported by this engine"))
    }

    @Test fun `snapshot is immutable and recent sessions and events are bounded`() {
        val owner = Any()
        PlaybackReports.begin(owner, "ExoPlayer", "Live TV", "private-source")
        PlaybackReports.update(owner, mapOf("coded size" to "1920x1080", "title" to "PrivateTitle"))
        val snapshot = PlaybackReports.current(owner)!!
        PlaybackReports.update(owner, mapOf("coded size" to "720x576"))
        assertEquals("1920x1080", snapshot.fields["coded size"])
        assertNull(snapshot.fields["title"])
        repeat(100) { PlaybackReports.event(owner, "size event $it") }
        assertEquals(24, PlaybackReports.current(owner)!!.events.size)
        repeat(10) { PlaybackReports.begin(Any(), "MPV", "Nuvio player") }
        assertEquals(5, PlaybackReports.recent().size)
    }

    @Test fun `ring stays bounded and survives restart`() {
        val dir = temp.newFolder()
        val ring = ReportRing(dir, 1024)
        repeat(300) { ring.append(ReportRedactor.clean("event $it password=private-secret ${"x".repeat(90)}")) }
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 2048)
        val saved = ReportRing(dir, 1024).read()
        assertTrue(saved.contains("event 299")); assertFalse(saved.contains("private-secret"))
        assertEquals(ring.read(), saved)
    }

    @Test fun `phone page escapes content and offers explicit email and txt download only`() {
        val page = ReportServer.page("Report", "</textarea><script>bad()</script>",
            ReportServer.Contact("abbaskip_e@hotmail.com", "Nuvio ReSeq report beta.10 Fixture TV"))
        assertFalse(page.contains("</textarea><script>bad()"))
        assertTrue(page.contains("&lt;/textarea&gt;"))
        assertTrue(page.contains("abbaskip_e@hotmail.com"))
        assertTrue(page.contains("Nuvio-ReSeq-report.txt"))
        assertTrue(page.contains("navigator.share")); assertTrue(page.contains("mailto:"))
        assertFalse(page.contains("fetch(")); assertFalse(page.contains("XMLHttpRequest"))
        assertTrue(page.contains("Nothing is sent automatically"))
    }
}
