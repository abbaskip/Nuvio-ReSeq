package com.nuvio.tv.core.report

import java.net.URI
import java.net.URLDecoder

/** The report boundary. Never export URLs, credentials, account identifiers or free-form log messages. */
object ReportRedactor {
    private val url = Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s<>\"']+")
    private val email = Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val ipv4 = Regex("(?<![\\w])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?")
    private val mac = Regex("(?i)(?<![a-f0-9])(?:[a-f0-9]{2}[:-]){5}[a-f0-9]{2}(?![a-f0-9])")
    private val ipv6 = Regex("(?i)(?<![\\w])\\[?(?:[a-f0-9]{0,4}:){2,}[a-f0-9:.%]+\\]?(?::\\d+)?")
    private val secret = Regex("""(?i)(["']?(?:[a-z_]*(?:token|password|passwd|secret|api[_-]?key)|authorization|cookie|set-cookie|username|user|login|account(?:name)?|profile(?:name)?|email|mac|device_id|client_id)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;}&]+)""")
    private val bearer = Regex("(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+")
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b")

    fun host(value: String): String {
        val parsed = runCatching { URI(value).host }.getOrNull() ?: return "unavailable"
        return addresses(parsed).take(253)
    }

    private fun addresses(value: String) = value.replace(email, "[email]").replace(mac, "[MAC]")
        .replace(ipv4, "[IP]").replace(ipv6, "[IP]")

    fun clean(value: String): String {
        var text = value.take(600_000).replace("\\/", "/")
        repeat(2) {
            // Encoded playlist URLs/credentials are no safer than their decoded form.
            text = runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text)
        }
        text = text.replace(url) { host(it.value) }
        // Also remove paths/query strings when logs omit the scheme.
        text = text.replace(Regex("(?i)\\b((?:[a-z0-9-]+\\.)+[a-z]{2,})(?::\\d+)?[/\\?][^\\s<>\"']+"), "$1")
        return addresses(text.replace(bearer, "[authorization]").replace(jwt, "[token]").replace(secret, "$1[redacted]"))
    }

    /** Crash messages and arbitrary application logs can contain unlabelled titles/account names.
     * Keep stack locations and exception types; withhold free text instead of guessing its meaning. */
    fun crash(value: String): String = value.lineSequence().take(300).mapNotNull { line ->
        val trimmed = line.trim()
        when {
            trimmed.matches(Regex("at [A-Za-z_$][\\w.$]+\\((?:[\\w$.-]+\\.(?:java|kt):\\d+|Native Method|Unknown Source)\\)")) -> trimmed
            trimmed.substringBefore('(').trim() in exitReasons -> trimmed.substringBefore('(').trim()
            else -> exceptionType
                .find(trimmed)?.value
        }
    }.joinToString("\n").ifBlank { "No saved exception stack or exit reason." }

    private val exitReasons = setOf("APP FROZE", "APP CRASHED IN NATIVE CODE", "APP RAN OUT OF MEMORY",
        "APP USED TOO MUCH OF THE DEVICE", "APP FAILED TO START", "APP KILLED")

    private val warning = Regex("^\\s*(\\d+-\\d+ \\d+:\\d+:\\d+\\.\\d+)\\s+\\d+\\s+\\d+\\s+([WEF])\\s+([\\w.$-]+)\\s*:")
    private val exceptionType = Regex("\\b(?:[a-zA-Z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*(?:Exception|Error)\\b")

    fun logLine(line: String): String? {
        val match = warning.find(line) ?: return null
        val type = exceptionType.find(line)?.value
        return "${match.groupValues[1]} ${match.groupValues[2]} application: " +
            (type ?: "[message withheld: may contain account or viewing information]")
    }
}
