# Usenet implementation and upstream provenance

This document describes the implementation on `dev` as of November 11, 2026. The startup/performance series builds on ReSeq commit `967cd57fa` and uses upstream [PR #4426](https://github.com/NuvioMedia/NuvioTV/pull/4426) at `bc6decd434c5718d4cb98e1ffddd9f1708bdd8b6` as its reference. Host tests establish correctness and fetch counts; no TV performance improvement has been measured for this series.

## Current implementation

| Area | ReSeq behavior |
| --- | --- |
| Engine and player | Kotlin/JVM reader with a random-token IPv4 loopback HTTP endpoint. HEAD, GET and single byte ranges are supported; provider credentials never enter player URLs. |
| Selection | Strict direct episode matches are considered first, then named matching archive sets. Lazy archive entry iteration avoids unrelated sets. Explicit indexes retain expanded NZB order. A single unmarked video can be a fallback; a filename filter cannot override a conflicting episode marker. RE2/J bounds selector regex execution. |
| RAR startup | Stored RAR4/RAR5 continuation blocks stop without reading each volume's trailer. Eligible regular sets open from first, second and ending volume headers. Intermediate headers and decoded sizes are verified before their bytes are served. Mismatches rebuild a checked layout or fail explicitly. Verified ranges remain readable during other layout verification. |
| RAR compatibility | RAR4 high unpacked sizes, RAR5 integer-width growth and padded sizes, Quick Open locators, stored RAR5 payload/header encryption with checked passwords, duplicate volume names, numeric sets and legacy `.r100` names are supported. Encrypted headers and unfamiliar main-header layouts use checked scanning. |
| Obfuscated posts | Signature/yEnc-name recognition, PAR2 filename recovery, eight-head probe batches, up to 4096 files, internal RAR5 volume ordering and NZB-order anonymous RAR4 chains. A separate LRU retains up to 512 first-16-KiB slices (at most 8 MiB) after full articles are evicted. Shuffled anonymous sets still inventory headers to establish their order. |
| 7z and split files | Stored 7z members with plain or LZMA/LZMA2-compressed indexes, split 7z/video files, and one sole stored archive wrapped inside another. Split files initially open first/last volumes and check predicted middle-volume sizes on use. |
| Connections | Shared authenticated account pools honour `/N` up to 12, default to two without an allowance, and cap the process at 24 sockets. Up to two preferred-provider logins overlap NZB retrieval. Speculation never waits for or evicts a pool slot and leaves demand capacity. One-connection providers retain pipelining. |
| Article memory and read-ahead | Low-memory devices use a 16 MiB article LRU, six-article window and estimated 8 MiB window cap; normal devices use 32 MiB, twelve articles and 16 MiB. The separate header LRU is additional. Speculative admission reserves 4 MiB per flight against three quarters of the article budget, so actual concurrency can be lower. Boundary reads prime the next volume; socket input buffers are 64 KiB. |
| NZB retrieval/cache | Bounded HTTP(S), gzip/encoding support, forwarded add-on headers, 12 MiB/150k-segment parsing limits, and stable slots for incomplete files. Parsed documents stay only in memory for 30 minutes, keyed by URL and headers, with at most four entries and a combined 12 MiB decoded-source budget. |
| Missing data | Supplied providers are tried before definitive absence. Up to 4096 missing IDs are remembered per session; transient failures remain retryable. No missing bytes are synthesized. |
| Cancellation/security | Preparation owns HTTP calls and provider sockets before blocking, including warm-up. Cancellation closes them; late registrations are rejected. NNTPS validates trust and hostname before authentication. Cross-origin redirects strip add-on headers and HTTPS downgrades are rejected. Pending-session ownership and expiry protect player handoff. |
| Diagnostics | `UsenetStartup` logs one resolve summary with monotonic stage offsets, the preferred provider's clamped allowance/source (`conns=5 (addon)` or `conns=2 (default)`), kind/counts and cache/download counters, plus the first GET's prefix wait. The allowance updates after successful provider fallback. No source URLs, hostnames, credentials, filenames, article IDs or exception text are logged. |

## Remaining limits and deliberate exclusions

Fully lazy discovery of shuffled anonymous sets, progressive delivery of an article before its complete decode, MKV Cues parsing/pinning, header-encrypted RAR prediction and wrapped multipart inner archives remain outside this implementation. Direct-post tail verification and mapped RAR ending volumes already support tail seeks; that is distinct from parsing Cues.

Compressed video payloads, encrypted RAR4/7z, PAR2 repair, disc images/BDMV, FTP(S) NZB retrieval and standalone `rarUrls` are unsupported. PAR2 filename recovery is implemented and does not perform parity repair. Provider/indexer setup stays with add-ons such as AIOStreams; built-in Newznab, automatic release substitution and stream-list prefetch are not added.

The upstream Go/native engine, progressive-buffer allocator and disk NZB cache are not imported. ReSeq uses conservative JVM memory profiles and a memory-only NZB cache. Larger connection/window limits still need real-provider and low-memory TV measurement.

## Provenance and licensing

The two upstream commits identify different generations of work; they are not interchangeable:

| ReSeq code or fixture | Upstream source and pinned commit |
| --- | --- |
| `LazyRar.kt`, lazy selection behavior in `UsenetSelection.kt`, RAR template/Quick Open handling in `StoredRar.kt` | [rar.go](https://github.com/haaihond/NuvioTV/blob/bc6decd434c5718d4cb98e1ffddd9f1708bdd8b6/native/usenet/internal/engine/rar.go), [select.go](https://github.com/haaihond/NuvioTV/blob/bc6decd434c5718d4cb98e1ffddd9f1708bdd8b6/native/usenet/internal/engine/select.go), `bc6decd434c5718d4cb98e1ffddd9f1708bdd8b6` |
| Synthetic helper in `UsenetPerformanceTest.kt` and fourteen-volume `rar_widthmismatch` fixture | [rar_seek_test.go](https://github.com/haaihond/NuvioTV/blob/bc6decd434c5718d4cb98e1ffddd9f1708bdd8b6/native/usenet/internal/engine/rar_seek_test.go), [fixture directory](https://github.com/haaihond/NuvioTV/tree/bc6decd434c5718d4cb98e1ffddd9f1708bdd8b6/native/usenet/testdata/rar_widthmismatch), same `bc6decd` commit |
| Existing `PendingUsenetPlayback.kt` adaptation and `RarEncryption.kt` key schedule | Earlier upstream revision `8f451a88bcce7ad5e91f5e997dda46fa381315a3`; their unchanged historical attribution remains in place. |

New adapted headers point at the full `bc6decd` hash. GitHub's commit API confirmed that exact upstream revision during the provenance check. These adaptations and fixtures use GPL-3.0, matching the project. The fixture README records its source and known payload hash. Other new transport/cache/diagnostic code is an original Kotlin implementation of the reviewed behavior. No upstream executable or native engine is included.

The original ReSeq preview was `173b4fe6d00e8daff586803cadf480a23437cf79`; the earlier upstream review used `8f451a8`. Those are historical origins, not descriptions of current capabilities. Historical implementations and reports remain available in Git history.

## Commit series for TV comparison

The implementation is split into ordered, tested revisions. These intermediate revisions are comparison points; current settings are described in the table above. The archive revision retains the preceding transport limits, so comparing it with the next revision isolates the buffering changes. The parser, selection and header-retention changes share one archive commit because they depend on each other.

| Commit | Change group | Brief items | Targeted validation |
| --- | --- | --- | --- |
| [`fdc03183d`](https://github.com/abbaskip/Nuvio-ReSeq/commit/fdc03183db13d8b9e7c91059bf0dfb1c6ee27ee9) | Startup, diagnostics and memory-only NZB cache | 2, 3, 8c | 55 tests: 54 passed, one skipped; zero failures/errors |
| [`2b87718be`](https://github.com/abbaskip/Nuvio-ReSeq/commit/2b87718be7a380c0cc09c56468d72f859066656d) | Lazy archives, retained headers and volume compatibility | 1, 4-6, 8a-b | 69 tests: 68 passed, one skipped; zero failures/errors |
| [`9ae9760e7`](https://github.com/abbaskip/Nuvio-ReSeq/commit/9ae9760e7293022ae0c920dd78eda6d424860753) | Connection allowances and bounded parallel read-ahead | 7 | 72 tests: 71 passed, one skipped; zero failures/errors |

The documentation/provenance correction is a separate follow-up commit. Logs for each implementation revision are in `build/usenet-review/commit-startup-tests.log`, `commit-archive-tests.log` and `connection-allowance-tests.log`.

## Validation and TV checks

The completed implementation run passed 72 Usenet tests (71 passed, one opt-in profiler skipped) and the full 3,456-test suite (3,454 passed, two skipped), with zero failures/errors. Run:

```powershell
.\gradlew.bat :app:testFullDebugUnitTest --tests "com.nuvio.tv.core.usenet.*" -PuseLocalFfmpegDecoder=false --offline
.\gradlew.bat :app:testFullDebugUnitTest -PuseLocalFfmpegDecoder=false --offline
```

The series validation logs are `build/usenet-review/connection-allowance-tests.log` and `build/usenet-review/connection-allowance-full.log`; JUnit XML is under `app/build/test-results/testFullDebugUnitTest/`. The checkout defines only the full distribution, so the historical Play Store compilation command is not applicable.

Coverage includes three-head startup for ten-volume RAR4/RAR5 and the real padded-width fixture, full-byte and random-seek equality, deferred layout repair, independent verified reads, missing-data failures, encrypted fixtures, episode/filter correctness, seventy shuffled obfuscated volumes, duplicate/numeric/long volume names, NZB cache isolation/expiry, supplied/default/above-cap connection diagnostics and preferred-provider fallback without private identifiers, overlapped login, cancellation and six concurrent speculative bodies with cold-seek capacity. Existing TLS, redirect, article checksum, ownership and non-Usenet tests remain in the suite.

On the user's AIOStreams/Newznab/Eweka setup, compare identical direct, named RAR, season-pack, obfuscated and high-bitrate releases using `UsenetStartup`. Measure startup, sustained buffering, seek recovery and memory; exercise source switching, cancellation and internal/external player return. Confirm connections close after exit. See [playback instructions](usenet-playback.md). No TV installation or device benchmark is claimed by host validation.
