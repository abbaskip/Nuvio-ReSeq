# Upstream NNTP review and targeted ReSeq adaptations

## Baseline and scope

ReSeq baseline is `173b4fe6d00e8daff586803cadf480a23437cf79`, following the original working preview `1cefd33`. The user reported successful playback and seeking on the Onn, including NZBGeek results through AIOStreams/Stremio NNTP/Newznab with Eweka. These are baseline observations supplied by the user.

Reviewed PR [NuvioMedia/NuvioTV #4426](https://github.com/NuvioMedia/NuvioTV/pull/4426) at `8f451a88bcce7ad5e91f5e997dda46fa381315a3`. Its source archive is retained in the ignored `build/upstream-nntp/` directory. Findings below come from source and tests, with upstream benchmark/device claims treated as upstream claims. No hardware or discovery commands are part of this review. Upstream Go suites were reviewed statically rather than executed. No upstream native binaries were downloaded or executed.

## Capability comparison

| Area | ReSeq baseline | Upstream implementation and assessment |
| --- | --- | --- |
| Architecture | Kotlin/JVM engine, NanoHTTPD loopback video endpoint, existing player integrations | Go executable packaged per ABI, control/session APIs, Android process manager, native SIMD dependencies. More throughput mechanisms, with a substantially larger build and maintenance surface. |
| Stremio and AIOStreams | Maps NZB URL, NNTP server list, explicit file index, string or array filename hints, direct URL precedence | Adds episode-aware selection, NZB header forwarding, password hints and ECMAScript selectors. ReSeq's RE2/J engine deliberately rejects unsupported backreferences/lookarounds instead of permitting unbounded regex backtracking. |
| NZB parsing | Bounded SAX parsing, reordered segments, stable file slots for incomplete files, strict article IDs | Streaming XML file decoding, 64 MiB input and 500k-segment limits, declared character sets, gzip detection and password metadata. ReSeq keeps its 12 MiB/150k-segment limits for TV memory protection. |
| Reconstruction | Verified yEnc part ranges and optional checksums, binary lookup of learned offsets, 64-bit seeking | Progressive shared article buffers, slab reuse, provider-aware retries, sparse damaged file handling. Larger articles supported, while ReSeq caps each part at 4 MiB. |
| RAR | Stored RAR4/RAR5, header CRCs, normal multipart naming and checked continuation chains | Lazy extent inference, header and payload encryption, inconsistent/shuffled volume naming, nested archives. This is a substantial compatibility and startup advantage for difficult releases. It needs its own controlled parser/crypto work in Kotlin. |
| 7z and disc images | No built-in 7z, ISO, BDMV or nested archive support | Stored 7z with compressed/encrypted headers, nested archives, UDF/Blu-ray feature clips. Compressed video payloads still fail. Worth a future compatibility milestone rather than adding a native stack now. |
| Obfuscated posts | Bounded yEnc filename recovery when subjects lack recognized media/archive names | PAR2 filename recovery by matching first-16KiB hashes, plus archive-signature discovery and RAR5 volume ordering. PAR2 name recovery is distinct from repairing missing article data. |
| Startup and buffering | Probes file ends and every archive volume before playback, one-article pipelining | Lazy archive discovery, configurable read-ahead, MKV head/tail startup pinning, parallel provider work, optional result prefetch. Source supports the expected reduction in round trips, but this review makes no measured performance claim. |
| Cache and memory | 8 MiB retained decoded cache per session, serialized reads, at most two published sessions | 32/64/128 MiB profiles, bounded slabs and pending flights, startup pin budgets and demand reserve. Upstream is better at accounting for in-flight memory and concurrent work. ReSeq's smaller retained cache is valuable on low-memory devices, though parsing and decoding allocations are extra. |
| Seeking | HTTP single ranges, HEAD, suffix ranges, verified article offsets and stored archive extents | Progressive streams, demand-prioritized reads and pinned head/tail data across Cues seeks. HTTP/player buffering still affects seek latency in both. |
| Missing articles | Provider fallback, but repeated requests could redownload known-missing articles or reconnect unnecessarily | Distinguishes definitive absence from network faults, remembers missing IDs and tracks providers that broke mid-article. Adapted definitive-missing handling here. |
| Connections | One reused NNTP connection per session and one pipelined next article, up to eight provider URLs | Shared per-account pools across sessions, tier priorities, global limits and per-provider allowances. Strong benefit for source handoff and throughput. ReSeq can overlap two per-session connections and currently ignores the server URL's connection allowance beyond using one connection. A shared pool remains a justified future change with concurrency tests. |
| Cancellation | Previously waited for blocking reads and synchronized shutdown, with session expiry as a fallback | Context cancellation, reference-owned flights, draining safeguards and explicit player handoff ownership. Adapted immediate socket/call cancellation and the pending-session ownership helper. |
| Error reporting | Sanitized connection errors, generic preparation fallback for unexpected faults | Structured startup/cache metrics, quota/authentication distinctions, release-health labels and optional next-release fallback. Diagnostics are stronger upstream. Automatic substitution of another release needs separate UX and tracking review. |
| Security | Validates NNTPS certificates/hostname, credential-free player URLs, local random-token endpoint, transient provider settings | Also guards resolved network destinations, requires opt-in for local NNTP servers, prevents HTTPS downgrade and strips headers across redirects. Header forwarding and redirect protections adapted here. Blocking existing self-hosted endpoints would change supported behavior and is deferred for an explicit setting. |
| Built-in Newznab | Search and account configuration stay with add-ons | Capability caching, ID-based seasonal search, quotas, ranking and encrypted profile-local credentials. Useful for an add-on-free setup, with limited benefit for this user's AIOStreams workflow. Reimplementing ranking and title matching would add ongoing work. |

Relevant pinned source includes [provider pools](https://github.com/haaihond/NuvioTV/blob/8f451a88bcce7ad5e91f5e997dda46fa381315a3/native/usenet/internal/engine/pools.go), [article store](https://github.com/haaihond/NuvioTV/blob/8f451a88bcce7ad5e91f5e997dda46fa381315a3/native/usenet/internal/engine/store.go), [NZB parser](https://github.com/haaihond/NuvioTV/blob/8f451a88bcce7ad5e91f5e997dda46fa381315a3/native/usenet/internal/engine/nzb.go), [archive selection](https://github.com/haaihond/NuvioTV/blob/8f451a88bcce7ad5e91f5e997dda46fa381315a3/native/usenet/internal/engine/select.go), [PAR2 recovery](https://github.com/haaihond/NuvioTV/blob/8f451a88bcce7ad5e91f5e997dda46fa381315a3/native/usenet/internal/engine/par2.go), [network protections](https://github.com/haaihond/NuvioTV/blob/8f451a88bcce7ad5e91f5e997dda46fa381315a3/native/usenet/internal/engine/network.go), and [startup pinning](https://github.com/haaihond/NuvioTV/blob/8f451a88bcce7ad5e91f5e997dda46fa381315a3/native/usenet/internal/engine/startup.go).

## Integrated changes

- `NzbDownload` fetches HTTP(S) NZBs with existing add-on request headers, preserves signed query strings, understands gzip file bodies as well as OkHttp's normal content decoding, and bounds both compressed and decoded documents. Redirects strip add-on headers on origin changes, reject HTTPS downgrades and reject credential-bearing redirect destinations.
- `NzbManifest` preserves declared text encodings, handles UTF-16 and BOMs, rejects non-NZB roots and retains the entity-declaration restriction after decoding.
- `NewsArticles` distinguishes missing responses from transient transport errors, tries other providers before declaring permanent absence, and avoids reconnecting to the same provider for definitive missing replies.
- `ArticleCache` remembers up to 4096 definitively missing IDs per session. Timeout, TLS, checksum and connection failures stay retryable.
- Preparation registers the HTTP call and active provider socket before blocking. Cancelling the coroutine closes those resources immediately, including during connect, greeting, TLS or login. OS DNS resolution can finish after cancellation, but the cancelled preparation refuses subsequent socket registration. HTTP session shutdown runs on the cleanup executor after its sockets are closed.
- Pending session ownership follows internal handoff, autoplay, replacement, view-model disposal, successful external launch and return while the results-screen view model is retained. External autoplay can pop that screen, so process-wide external-return ownership remains a follow-up; the two-hour idle expiry is still the fallback for that route. A cancelled continuation releases a result that could otherwise be stranded after preparation.

The Kotlin engine, 8 MiB cache, existing decoder/player paths and release baseline are retained. Changes are limited to Usenet internals and stream-screen handoff hooks. TVDB episode-context attachment still completes before navigation, then the prepared URL is handed to the player.

## Upstream complexity and exclusions

The upstream PR is a broad platform feature, including provider/indexer administration, a LAN configuration server, release ranking, automatic fallback, archive cryptography, native process management and ABI artifact delivery. Its prebuilt manifest pins hashes and source/toolchain metadata, which mitigates asset replacement risk. It still adds external binary availability, compiler/NDK/Go version management and vendored dependency maintenance.

Its default 64 MiB article budget is eight times ReSeq's retained payload cache. This buys shared progressive buffering and larger windows, but should be measured on constrained TVs before adoption. Optional prefetch spends provider connections and indexer grabs on releases the user might never play. Its 256 MiB/14-day NZB disk cache can reduce repeated grabs but persists the NZB document, including any embedded archive-password metadata. ReSeq retains transient NZB processing.

Optional hole filling synthesizes zero data under bounded conditions. It can salvage some damaged videos, but also delivers altered media bytes. ReSeq retains explicit failure for unavailable data. PAR2 filename recovery would be a separate worthwhile compatibility addition, without implying parity repair.

The exact ECMAScript regex engine has useful selector compatibility but also requires time, stack and allocation controls. RE2/J is retained pending a real incompatible add-on selector. A strict file index or filter continues to fail rather than silently selecting a different episode.

Shared pools, lazy archive indexing, progressive article decoding, 7z/crypto, disc images, automatic fallback and full diagnostics were left outside this adaptation because they cross substantial parser, concurrency or playback-state boundaries. These are documented remaining gaps, not claims of ReSeq parity. Built-in Newznab is excluded in line with the user's preference for AIOStreams.

## Licensing

`PendingUsenetPlayback.kt` and its companion test are adapted from the pinned PR under the project's GPL-3.0 license, with provenance in their headers. The remaining changes are original Kotlin adaptations of reviewed behavior. No Go, C/C++, SIMD, archive-library code or binaries were copied. Upstream's own dependency provenance is documented in its `native/usenet/NOTICE.md`.

## Manual validation still needed

The new code requires a user-run check with the existing AIOStreams/Newznab/Eweka setup. Compare the same NZBGeek releases for startup, long buffering periods, seeking and episode selection. Exercise rapid source switching, cancelling preparation, backing out before launching a player and returning from an external player. Confirm provider connections close after cancellation and external return.

Use known multipart archives and difficult obfuscated releases to identify the remaining archive gaps. Measure startup time and peak app memory before choosing shared pooling, lazy archive discovery or a larger cache as the next milestone. No physical-device playback or performance improvement is claimed for this patch.

## Completed host validation

The final run passed `:app:testFullDebugUnitTest :app:compilePlaystoreDebugKotlin --offline -PuseLocalFfmpegDecoder=false`. Both full-debug and playstore-debug Kotlin compilation completed.

JUnit XML reports 3,273 tests, 3,271 passed, two skipped, zero failures and zero errors across the full host suite. All 27 Usenet tests passed, including 14 new/adapted reliability and ownership tests. The suite also includes existing HTTP/player, debrid, TVDB, tracking and IPTV tests. Logs remain in the ignored `build/usenet-upstream-final.log`, with the standard XML reports under `app/build/test-results/testFullDebugUnitTest/`.

Specific regressions cover interrupted provider greetings, cancellation of pending NZB HTTP requests, missing-article negative caching versus transient retries, avoiding redundant reconnects on a known missing reply, gzip bodies, gzip expansion limits, signed query preservation, add-on download headers, cross-origin credential stripping, UTF-16 parsing, entity rejection, invalid XML roots and pending/internal/external session ownership.

Implementation is committed on the local `feature/usenet-upstream-adaptations` branch. The original `feature/usenet-playback` branch remains at the working baseline. No APK installation, physical-device inspection, release publication or upstream engine replacement was performed.
