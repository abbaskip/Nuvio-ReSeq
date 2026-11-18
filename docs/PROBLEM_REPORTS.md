# Problem reports

Open Settings → About → Send a report to the developer, or choose Report a problem with this video in a player menu. The latter copies the technical playback snapshot at the moment of selection, before opening another screen pauses/releases playback.

The consent screen lists the data. Continue builds a cleaned snapshot and displays it on the TV; Cancel does neither. A random local-network link and QR code exist only while that screen is visible. There is no upload endpoint or automatic email. With no network the cleaned report remains readable on the TV.

The phone page previews the same snapshot, offers a text-file download and uses the Web Share API where supported. Web Share cannot preselect an email recipient, and most browsers disable it on LAN HTTP pages. The fallback opens an email to abbaskip_e@hotmail.com with the report subject and asks the user to attach the downloaded file. Both paths require a user action.

Collection uses format, decoder, size, surface and buffering callbacks from existing players. Unsupported engine fields are explicitly marked unavailable. The last five technical sessions and 24 events per session are held in memory. A 500 KiB two-segment on-disk ring retains cleaned technical events and warnings/errors from this process. There is no new playback polling loop. The only persistent workers are the bounded log reader/writer.

No titles, watch positions, media identifiers, account/profile names or raw settings are exported. Arbitrary log messages and exception messages are withheld because they may contain unlabelled account or viewing information; exception types, safe source locations and known exit reasons remain. URL paths, credentials, tokens, API keys, usernames, MACs, emails and IP addresses are redacted before persistence/display/sharing. Provider information is limited to playlist type and host. The support email and LAN address are instructions outside the report, not collected user data.

JVM coverage exercises redaction, encoded credentials, safe crash/log projection, report field allowlists, immutable playback snapshots, bounded retention and HTML escaping/manual sharing. Existing tracker and CW tests remain unchanged.
