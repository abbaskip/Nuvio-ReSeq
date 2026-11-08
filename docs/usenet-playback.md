# Experimental Usenet playback

The local `feature/usenet-playback` branch starts at beta 8.3 (`ef39ff2`). Its full debug APK is named **ReSeq Usenet Test** and uses the package `io.github.abbaskip.nuvioreseq.usenettest` so it can coexist with the release and IPTV debug apps. It has its own Android app data. Development and automated validation take place on the host.

Further work lives on `feature/usenet-upstream-adaptations`. The downloadable original preview remains at `173b4fe`.

## Playback path

1. The stream mapper reads Stremio's `nzbUrl`, `servers`, `fileIdx`, and `fileMustInclude`. The selector accepts a string or an array of filename hints, including slash-delimited regular expressions with optional case-insensitivity. An existing direct video URL takes precedence.
2. Selecting a Usenet result fetches its HTTP(S) NZB and orders the article IDs by segment number. The engine connects to an add-on-supplied NNTP or NNTPS provider. NNTPS validates certificate trust and hostname before authentication. The separate, shared add-on discovery client still accepts self-signed certificates for existing installations. Its add-on HTTP breadcrumbs retain the host and omit configured URL paths and query strings.
3. The reader decodes yEnc articles, validates supplied part checksums and locates requested bytes from the yEnc ranges. When NZB subjects lack usable filenames, a bounded first-article probe can recover video and volume names from yEnc headers. NZB byte counts describe posted data and serve only as file-size estimates. The decoded-article cache retains up to 8 MiB per session. Overlapping sessions can share authenticated provider sockets within the add-on’s connection allowance, with at most two sockets per account and one pipelined article per reader. Missing articles trigger fallback to the other supplied providers.
4. For stored RAR4 and RAR5 files, the reader examines the volume headers, checks header CRCs, joins split file extents and maps video offsets back to the corresponding articles. It supports `.partNN.rar` and `.rar/.r00` naming. Stored RAR5 payloads and headers can be decrypted on demand when the NZB supplies the password and the archive includes a password check. PAR2 file descriptions can restore hidden filenames. Single-member RAR5 volume chains can also be ordered by their internal volume numbers.
5. The reader also handles stored 7z members with plain or LZMA/LZMA2-compressed indexes, split `.7z.001` archives, numbered video fragments and a single stored archive inside another supported archive. The XZ library only decodes the bounded archive index.
6. The player receives a random, session-specific HTTP URL bound to IPv4 loopback. It supports HEAD, GET and single byte ranges. Source and episode switching use the same resolver as the initial selection. The last-link cache skips these temporary URLs. Provider login data stays out of the player URL.

## Host validation

Run `gradlew.bat :app:testFullDebugUnitTest --tests "com.nuvio.tv.core.usenet.*" -PuseLocalFfmpegDecoder=false` with the project JDK and Android SDK. The tests start temporary loopback servers with synthetic logins. The TLS test creates its own short-lived certificate using the JDK's keytool.

The test resources contain a four-second 96×64 MP4 made from generated colour frames. WinRAR 7.22 produced the two 4 KiB-target RAR5 volumes and the compressed and encrypted rejection samples. The two legacy RAR4 fixtures use stored file blocks wrapping the same MP4. The newer RAR5 password fixtures use the synthetic password `FixtureOnly5`. The 7z fixtures wrap the same media using libarchive’s Copy method, including a two-episode pack with independently compressed LZMA and LZMA2 indexes. Host tests compare bytes returned by Nuvio with the original MP4, including requests near EOF and across article and volume boundaries.

To rebuild the preview, supply the approved project values for `NUVIO_SUPABASE_URL` and `NUVIO_SUPABASE_ANON_KEY` in an ignored local properties file and run `gradlew.bat :app:assembleFullDebug -PuseLocalFfmpegDecoder=false`. The universal APK is under `app/build/outputs/apk/full/debug/`. The project's `scripts/verify_apk_services.py --backend-only` checks compiled APKs against the configured backend inputs.

## Manual test

1. Open **ReSeq Usenet Test** and configure the Stremio-compatible add-on and Usenet service you intend to use. An add-on that returns `nzbUrl` plus `servers` exercises this feature. A debrid or WebDAV result that already points at playable HTTPS video exercises the standard player path.
2. Play a directly posted video, then try a stored multipart RAR. Seek forward, back, and near the end. Check picture, audio, and subtitle selection.
3. Try a season pack. Check the episode opened from the stream list, then switch episodes from the player. Try a second Usenet result and an ordinary HTTPS result afterward.
4. Record time to first picture, seek delay, and any displayed error. A source description and archive type are sufficient for reporting. Omit provider URLs, NZB download URLs, manifest URLs, and login details.

## Further work

- Measure time to first frame, memory use and sustained throughput with real providers and large multi-volume releases. Archive preparation currently probes volume headers before handing playback to the player. A reader fetches one article at a time. Compare source handoff and seeking with the add-on set to one provider connection.
- Expand coverage for obfuscated filenames, ambiguous or damaged season packs, and capture real-world selection differences before changing default ordering. Explicit indexes use NZB order with archive entries expanded in place. Episode selection uses the add-on request coordinates and filename markers. Ambiguous packs can require an explicit add-on selector.
- Validate Android player handoff, source switching, long pauses and exit on the user's chosen device during manual testing. Session links expire after two hours without reads or when the internal player releases them.
- FTP(S) NZB retrieval, compressed video payloads, encrypted RAR4 and 7z archives, PAR2 repair, disc images and standalone Stremio `rarUrls` sources are outside the implemented path. An add-on-supplied NNTP configuration is required.
- Evaluate upstream's larger Go-based implementation when extending archive or high-throughput support. It also includes independent indexer and provider settings. [Upstream proposal](https://github.com/NuvioMedia/NuvioTV/pull/4426)

Protocol references include the [Stremio stream format](https://stremio.github.io/stremio-addon-sdk/api/responses/stream.html), [NNTP specification](https://datatracker.ietf.org/doc/html/rfc3977) and [RAR5 format](https://www.rarlab.com/technote.htm).
