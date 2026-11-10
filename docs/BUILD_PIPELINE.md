# Build and publication pipeline

ReSeq ships the `full` Android TV app through GitHub. The unused Play Store product flavour, manifest, feature-disabled stubs, benchmark package choices and compilation gate have been removed. `fullDebug`, `fullRelease`, the updater, existing application IDs and release signing identity remain.

## Measured bottlenecks

GitHub release [42758000307](https://github.com/abbaskip/Nuvio-ReSeq/actions/runs/42758000307), commit `f995e772e`, completed in 49m 02s on 10 November 2026. Its job API reports:

| Stage | Elapsed |
| --- | ---: |
| Full release unit tests and signed APK assembly | 19m 08s |
| Complete debug lint and released-source budget checks | 14m 44s |
| Additional debug and instrumentation APKs | 13m 08s |
| Signing, upgrade, native-library and service verification | 9s |

The other recent successful releases took 43m 53s and 58m 11s, with the same three dominant stages. The successful log explicitly says `Cache is read-only: will not save state for use in subsequent builds`; it restored Gradle state last saved for an older November 4 commit. No task-output cache hits appeared. Kotlin was compiled separately for release and debug. Lint also performed release-vital analysis during packaging. Publishing itself took 25 seconds; changing upload ordering would offer little benefit and could break older updaters.

The archived local full suite contained 508 suites and about 173 seconds of serial test execution. NNTP loopback, lease and import fixtures account for much of that time and exercise failure handling, so they are retained. Compilation, lint and unnecessary packaging are larger targets than deleting small logic tests.

## Lanes

- **Development:** Python pipeline safety tests, deterministic Sports feed fixtures and the complete `fullDebug` JVM suite. Pushes to `dev` warm the default-branch cache; PR runs are read-only cache consumers. Draft PRs wait until ready. Documentation-only default-branch changes do not rebuild the app. Debug APKs and complete lint can be requested through workflow-dispatch inputs. No temporary release-signing key is generated for ordinary debug checks.
- **Publication validation:** the complete `fullDebug` JVM suite and full debug lint share one Gradle invocation and compilation. The immutable released lint reference, shrinking source budget and required retirement of resolved allowances are still checked. Python tests run once. Optional debug/instrumentation APKs are built only when explicitly requested; building them is not an emulator test.
- **Signed packaging:** runs concurrently with validation, builds the optimised `fullRelease` APKs and checks all five architecture outputs, permanent signature, previous-release upgrade identity, native libraries, CloudStream runtime and actual packaged services. R8, resource shrinking, baseline/startup profiles and updater dex metadata remain enabled.
- **Publish:** waits for successful validation and packaging from the same workflow run, downloads the immutable verified artifact, checks its SHA-256 manifest, rechecks the publication hold and preserves the existing serial asset order. Only this job has repository write permission. `artifacts_only=true` skips this job entirely, including draft/tag creation. Artifact rehearsals on different branches do not queue behind publication.

JVM logic tests run once against debug code; the production release is still compiled, shrunk, permanently signed and verified as an actual APK. This removes the old release-test compilation followed by a second debug compilation for lint. Full lint still checks instrumentation source without having to dex and package unused test APKs. Upstream candidate validation also combines JVM tests, debug packaging and lint into one Gradle invocation.

## Caching and resource limits

Gradle task caching, configuration caching and project parallelism are enabled. CI caps Gradle at two workers. Dedicated runners with at least 14 GiB RAM retain the proven 6 GiB release heap and 1 GiB metaspace; smaller runners default to 4 GiB/768 MiB; JVM tests use one 1.5 GiB process, recycled after 100 classes to bound Robolectric retention. The Kotlin compiler stays in-process to avoid a second large compiler daemon. These limits can be overridden with `NUVIO_CI_WORKERS` and `NUVIO_CI_JVM_ARGS` for controlled benchmarks.

All lanes use the same Java 17 toolchain and Gradle cache action. Trusted branches save their own updated dependency/configuration state; GitHub permits branches to restore the default branch's entries, not arbitrary sibling-branch entries. The `dev` development lane seeds shared outputs. Cache restore fallbacks span version/build-script changes; Gradle's own content hashes decide which task outputs remain applicable.

Compiled outputs can contain approved app-service configuration. `setup-gradle` excludes the plaintext task cache. A separate AES-256/PBKDF2 encrypted, HMAC-SHA256 authenticated archive stores task outputs using the private `GRADLE_ENCRYPTION_KEY` repository secret. Configuration state uses the Gradle action's encrypted cache support. Fork builds receive no decryption key. Corrupted or obsolete task archives fall back to cold builds. Completed task outputs may be reused after a failed validation, but cache presence never satisfies the release gate. No keystore or properties file is archived as an ordinary artifact.

Tests requested with `NUVIO_PROFILE_LOOPBACK=true` or a temporary `NUVIO_TVDB_LIVE_TEST_KEY` bypass test-output reuse so cached skips cannot masquerade as live checks. Personal TMDB credentials continue to be removed from CI inputs and checked against actual APK DEX data.

On the archived 6,160-issue lint report, budget parsing fell from 17.2 seconds to 0.65 seconds. All 5,823 resulting finding records and ignored-finding counts matched the old parser exactly. The gate also parses once instead of twice.

A local configuration-only probe took 8 seconds cold and 0.6 seconds on reuse. Changing backend build inputs caused Gradle to invalidate the configuration and regenerate the backend fields correctly. This is a configuration benchmark, not a full-build speed claim.

## Test and gate review

Removed or consolidated:

- Play Store product/source variant and its repeated Kotlin compilation.
- Default generation of five debug APKs plus instrumentation APKs during publication; these were built but never executed by this workflow.
- Duplicate Python regression runs and duplicated `PYTHONPATH` assignment.
- Separate Gradle invocation and duplicate source-fingerprint reads for lint checks.
- The second lint parse and temporary edit of the committed budget: a single read-only `check --require-ratchet` enforces the same retirement requirement and can emit a reviewable reduced budget under `build/pipeline/`.
- A permanently ignored placeholder debrid test and two external example-URL probes that could pass without any assertion after a failed request. One deterministic local HTTP fixture replaces them and requires the correct head/tail Range headers, content length, MP4 markers and sparse offsets.

The hosted rehearsal exposed a Home retry fixture that asserted cache state before IO completion and counted requests for other titles. Its checks now join the actual focus job, count the intended title and assert that the initial transport failure remains eligible for retry. The related retry suite also observes job completion instead of fixed sleeps. Both suites passed three forced executions (30 test cases total) without reusing test results. No application logic changed.

Retained: TVDB resequencing and identity tests, Continue Watching/shuffle/TV Mix, playback/NNTP failure fixtures, Sports, Live TV ordering/import/storage, tracker reconciliation, profile/private-key boundaries, updater tests, upstream review/hold protections and complete release artifact validation. No historical test-count threshold was introduced. Genuine opt-in live TVDB and loopback profiling remain opt-in.

## Running and comparing

Use `python scripts/ci_pipeline.py development`, `validate`, or `release` after preparing approved build inputs. Add `--apk` or `--device-apks` to a validation/development lane only when those APKs are required. Signed packaging requires the existing permanent-signing environment variables. The script never installs an APK or publishes anything.

Each lane writes sanitized elapsed times under `build/pipeline/` and Gradle task profiles under `build/reports/profile/`. Hosted workflows upload those reports for 14 days. Compare a cold artifact-only rehearsal and a second rehearsal on the same branch with the same inputs to assess cache reuse. Do not treat an artifact-only run as on-device sign-in verification.
