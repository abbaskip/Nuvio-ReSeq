package com.nuvio.tv.livetv.player

/** Keep channel/stream identifiers useful while excluding account and session credentials. */
internal fun redactLiveStreamUrl(text: String): String = text
    .replace(Regex("""(/(?:timeshift|live|movie|series)/)[^/]+/[^/]+/"""), "$1***/***/")
    .replace(Regex("""(?i)([?&](?:username|password|pass|mac|sn|sn2|serial|device_id2?|signature|[a-z_]*token|authorization))=[^&#\s]*"""), "$1=***")
    .replace(Regex("""(?i)(https?://)[^/@\s]+:[^/@\s]+@"""), "$1***@")
    // Xtream links without "/live": http://host:port/user/pass/123.ts
    .replace(Regex("""^(https?://[^/]+)/(?!\*\*\*)[^/]+/[^/]+/(\d+(?:\.[A-Za-z0-9]+)?(?:\?.*)?)$"""), "$1/***/***/$2")
