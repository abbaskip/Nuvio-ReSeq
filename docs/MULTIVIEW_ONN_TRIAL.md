# Multiview Onn trial

PR #27 remains an isolated M1 draft from dev at a4d01d7de. The port derives from homelessbrian/NuvioPlusIPTV e3fbccb; M2 and Calendar are excluded. The maintainer authorized only the Onn for this follow-up. The bedroom TV was not contacted.

## Builds and update preservation

Local.11 rebuilt PR #27's product head b97615213 with `assembleFullRelease -PlocalBetaNumber=11`. The Onn was confirmed using `adb devices -l`, model `onn 4K Pro Streaming Device`, product/device jarvis2, ABI armeabi-v7a. It was updated using only `adb install -r`. Local.12 contains the M1 corrections below and uses the same update method.

Both beta numbers retain versionCode10057. Install checks preserved the existing firstInstallTime records. Both ARM artifacts use the published9.10 signing certificate, SHA-256 `3e4b06eb03be89c12f50b21a809b857a3f3c90f8687664f5efc5ee0b910fc7fe`. APKs and detailed device evidence remain local; no APK was published.

## M1 corrections

- The measured 3GB Onn has a384MiB heap: classify it as three panes while retaining two-pane low-RAM/2GB limits. Keep buffers heap-based.
- Preserve Multiview's contribution to the shared playback flag through the guide's delayed single-player detach.
- Consume that flag in the Sports bulk scheduling gate. Sports matching algorithms are unchanged.
- Consume the same signal in ambient/screensaver eligibility. The maintainer observed the in-app screensaver covering active panes; the single-player-only exemption missed Multiview. No screensaver preference is changed.
- Honor known M3U account maxima and share reservations across pasted Xtream M3U/login aliases. Compare current reservations with maxConnections; cached activeConnections may include our released preview or panes and cannot safely be added again.
- Persist only a boolean guide-return marker and consume it in the active navigation host. Save no channel selections or stream links.

## Device results

| Check | Evidence / limit |
|---|---|
| Automatic cap and layouts | Three panes: preferred hardware AVC/HEVC decoders each advertise9 instances; measured physical RAM is about2.9GiB and heap384MiB. `min(4,9-1,3)=3`. Exercised all six offered layouts through three panes. |
| Real streams | AVC playback included1920x1080 and1280x720 sources. Free-provider feeds repeatedly supplied a1472x720 starting-soon slate and lost the connection. Labelled HEVC/UHD attempts returned HTTP404; HEVC playback remains unverified. Real DRM/caption presentation was not verified. |
| Sound / zoom | Pane sound labels followed selection; one active app AudioTrack was observed. Zoom and Back worked. Direct listening and reliable frame-drop counts remain receiver-side checks. |
| Account limit | A real maximum-one account blocked its second pane before allocation, showed the allowance message and offered Backup. Backup selected another playlist, whose provider supplied a slate. |
| Lifecycle / recovery | Stop and Home removed every app decoder client. Foreground recreated all three players at live; cold reopen returned to the guide without restoring panes. |
| Background work | A running EPG refresh showed “Guide updating · slowed during playback” alongside a rendering live pane. Sports bulk cancellation/resume has a shared-signal JVM regression; no independent on-device network-work counter was available. |
| Memory | Final ambient-fix run: 333.4 seconds, 12 samples, three decoder reservations at every sample, same PID. PSS 324.0→337.6 MiB (range 324.0–338.2); Java allocation 159.7→154.3 MiB; native PSS 66.4→69.1 MiB. No observed OOM, ANR, restart or screensaver. This short run cannot establish long-term leak freedom or quantify dropped frames. |

Final validation passed: 3,804 JVM tests across574 suites, zero failures/errors, two existing opt-in skips;61 Multiview tests. Signed minified `assembleFullRelease -PlocalBetaNumber=12` passed. The final installed version is `0.1.0-beta.9.10.local.12`, versionCode10057, with both firstInstallTime records unchanged at `2026-10-29 17:23:34`. Stop and cold guide recovery passed again. The latest beta was left installed and the Onn disconnected. Initial emulator/fixture acceptance and the a4d01d7de/8bd4f83b2 regressions are documented in MULTIVIEW_M1.md. No real-account tracking regression actions were authorized for this follow-up.


The stable final three-stream run used Austreams Free `[2] Sky Sport 1 NZ` (1920×1080 AVC), Austreams ABC News Live and B1G Sky Sports News. B1G Fox Sports had ended during an earlier run; its automatic backup was denied because the other account was occupied. Timestamp-discontinuity warnings occurred in provider streams. Visual snapshots confirm rendering, but direct listening, exact drop counts, available HEVC/DRM/captions and a longer receiver-side soak remain maintainer checks. The no-input run also verifies the app screensaver fix past its timeout.

After that run, ABC News stalled during the layout-reduction checks; Media3 reported `Playback stuck buffering and not loading`. Another pane also briefly buffered. The cause is unresolved: this trial does not establish uninterrupted stream stability, and the later failure is not attributed to the provider or layout code without evidence. All offered layouts were exercised, but smooth playback through every transition remains a receiver-side check. Total PSS increased13.6MiB during the final short run while allocated Java heap fell5.4MiB; flag the PSS rise for a longer soak rather than claiming leak freedom.
