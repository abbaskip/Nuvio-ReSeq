# Nuvio ReSeq Mobile Beta 3.2

## Changes

Use the Cast button in Live TV as usual. ReSeq first sends the channel URL to Chromecast when the provider allows it. If Chromecast rejects H.265 video carried in MPEG-TS, ReSeq can repackage the original video as fragmented MP4 and stream it from the phone. This works with raw TS and with HLS playlists containing TS segments. Channels that require special request headers go through the phone from the start.

The phone starts processing when the receiver requests the channel. It can convert Dolby Digital Plus audio to AAC and, if the receiver still rejects the repackaged stream, retry with H.264 video. The player reports whether the TV reached the phone and any available provider or playback error code.

To update from Mobile Beta 3.1, open **Settings → About and credits → Check for updates**, then tap **Download update**.

## Validation and limitations

All 24 mobile Cast unit tests passed. Three Android emulator playback tests rendered video with AAC audio through the app’s live relay. They covered raw TS and HLS inputs with H.265 and six-channel Dolby Digital Plus, plus the H.264 fallback. Live playback on a particular hotel Chromecast still depends on the receiver and whether the network lets it reach the phone.

## Copyright and content sources

ReSeq is an independent media player. Users supply their playback sources and are responsible for following provider terms, licensing requirements and applicable copyright law. The default Cinemeta and OpenSubtitles v3 endpoints provide metadata and subtitles. Third-party names identify compatibility and attribution. [Content sources and rights concerns](https://github.com/abbaskip/Nuvio-ReSeq#copyright-content-sources-and-independence)

## Credits and source

ReSeq builds on Nuvio by Tapframe and contributors and Nuvio + IPTV by homelessbrian and contributors. Stalker support incorporates MIT-licensed code from IPTVnator. The tagged source is available under GPLv3 with the upstream and mobile notices. [Nuvio](https://github.com/NuvioMedia/NuvioTV) · [Nuvio + IPTV](https://github.com/homelessbrian/NuvioPlusIPTV) · [IPTVnator](https://github.com/4gray/iptvnator) · [Mobile credits](https://github.com/abbaskip/Nuvio-ReSeq/blob/mobile-v0.1.0-beta.3.2/NOTICE-MOBILE.md) · [Licence](https://github.com/abbaskip/Nuvio-ReSeq/blob/mobile-v0.1.0-beta.3.2/LICENSE)
