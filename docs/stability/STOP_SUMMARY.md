# Stability programme — stopped after B0 controls

Stopped on request **12 November 2026, 16:49 Sydney**, before G1. Base:
**9.6.2 / 10052 / 6b1999648**. **12 of 15 unchanged controls finished.**

## Finished runs

Peaks are **KiB**; fresh installs, frozen APKs, unchanged harness. Launch passes
do not mean the known Continue Watching invariants passed.

| Device / tier | Build | Repetition | Cricket Dalvik peak | Soak PSS peak | Launch |
| --- | --- | --- | ---: | ---: | --- |
| Onn | 10051 | 1 | 193,942 | 380,242 | Pass |
| Onn | 10051 | 2 | 184,437 | 348,741 | Pass |
| Onn | 10051 | 3, retry 2 | 170,617 | 368,741 | Pass |
| Onn | 10052 | 1 | 165,081 | 421,138 | Pass |
| Onn | 10052 | 2 | 189,769 | 379,061 | Pass |
| Onn | 10052 | 3 | 184,875 | 378,847 | Pass |
| Emulator 2,048 MB | 10052 | 1 | 120,717 | 288,560 | Pass |
| Emulator 2,048 MB | 10052 | 2 | 124,986 | 269,543 | Pass |
| Emulator 2,048 MB | 10052 | 3 | 117,849 | 278,028 | Pass |
| Emulator 3,072 MB | 10052 | 1 | 145,861 | 303,712 | Pass |
| Emulator 3,072 MB | 10052 | 2 | 124,752 | 268,658 | Pass |
| Emulator 3,072 MB | 10052 | 3, retry 1 | 156,818 | 266,735 | Pass |
| Onn, separate profiling diagnostic | 10051 | 1 | —* | —* | Pass |
| Onn, separate profiling diagnostic | 10052 | 1 | —* | —* | Pass |

*Diagnostics finished; profiled peaks were not aggregated before stopping and
are excluded from controls. No further analysis started. Heap classes: Onn **256/384 MB**;
emulators **192/512 MB** (2 GB), **192/576 MB** (3/4 GB).

## Failed, interrupted and unstarted work

- Onn 10051 r3 and emulator 3 GB 10052 r3: ADB connection failures during PID
  checks; immediate rechecks found the original PIDs alive.
- Onn 10051 r3 retry 1: `am start` failed because shared ADB was unavailable.
- Initial Onn 10051 diagnostic: Perfetto configuration permission failure before launch.
- Emulator 4 GB 10052 r1: stopped on request during TVDB-unavailable, after
  launch/soak/cricket; **incomplete**. Repetitions 2–3 never started.

All interrupted attempts are retained separately and restored successfully.

## Onn memory verdict and original 3 GB failure

**A 9.6.2-specific memory increase is not established; the small soak difference
is inconclusive.** Median cricket Dalvik peak was 184,437 → 184,875 KiB (+0.24%);
median soak PSS peak was 368,741 → 379,061 KiB (+2.80%), with overlapping ranges.
The large original cricket increase did not reproduce. Completed diagnostics
found nearly identical reachable Java after soak and the same dominant Sports
JSON allocation path. This does not establish equivalence or a calibrated gate pass.

The original B0 3 GB launch failure is a **PID-observation failure / false
process-death report**: PID 4370 survived into post-assertion graphics/activity
collection, then cleanup killed it. Its exact command response was not retained.
Later ADB failures demonstrate the ambiguity, not the missing original response.
The original attempt remains failed.

Owned emulators/helpers/monitors are stopped. Onn restoration was independently
verified: settings/volume restored, production 10051 unchanged, test package,
reverses and trace files removed. Observed Awake/logical display ON; physical TV
power unknown. Private ADB stopped; shared ADB untouched. Evidence:
`build/stability/repeated-controls/`. No app changes or publication.
