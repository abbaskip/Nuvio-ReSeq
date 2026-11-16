# Multiview Onn trial

The maintainer authorized an Onn-only trial of PR #27. The bedroom TV is excluded. Only Live TV, Multiview and Sports Hub are in scope; tracking-account mutations, movies/episodes, item removal and settings changes are excluded.

## Local.11 build checkpoint

- Product source: PR #27 head `b976152130cbe370a6f5bf2e081108938151b8bb`.
- Build: signed minified `assembleFullRelease -PlocalBetaNumber=11`, successful.
- Version: `0.1.0-beta.9.10.local.11`, versionCode 10057.
- Signing certificate matches published 9.10: SHA-256 `3e4b06eb03be89c12f50b21a809b857a3f3c90f8687664f5efc5ee0b910fc7fe`.
- Onn identity was confirmed by `adb devices -l` and model: `onn 4K Pro Streaming Device`, product/device `jarvis2`, ARMv7 ABI.
- The existing real app is published 9.10/code10057. Install timestamps were recorded privately before replacement. Only `adb install -r` is authorized.
- Read-only codec API probe: preferred hardware decoders `c2.amlogic.avc.decoder` and `c2.amlogic.hevc.decoder` each report **9** maximum supported instances. Software fallback counts were excluded.
- Local ARMv7 artifact SHA-256: `c60a6421846becfbabb316e2a108b87a2f3af03294e06ebed94027c00da3a4de`.

Device test results will be appended after the authorized trial. No bedroom-TV connection or APK publication is part of this work.
