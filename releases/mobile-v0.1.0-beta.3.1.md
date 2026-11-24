# Nuvio ReSeq Mobile Beta 3.1

## Changes

Live TV now sends the provider’s channel URL directly to Chromecast when the playlist supplies no special request headers. This lets the TV fetch the stream itself, including on networks where it can reach the provider but cannot connect back to the phone.

If the TV rejects the direct stream or stays on the loading screen, ReSeq retries once through the phone. The phone can package a raw MPEG transport stream as HLS for that retry. Channels that specify a user agent, cookie or other required request header use the phone relay from the start.

ReSeq also checks the format of extensionless IPTV URLs with a header-only request first. It requests a small byte range if the provider needs a GET.

To update from mobile Beta 3, open **Settings → About and credits → Check for updates**, then tap **Download update**. For manual installation, choose the ARM64 APK for a recent Android phone or tablet, or the universal APK for other devices.

## Validation and limitations

Twenty automated mobile Cast tests passed. They cover the URL sent to the receiver, required headers, extensionless links, direct-load failures, timeouts and transport stream packaging. Playback of a specific channel depends on the provider and the codecs supported by the TV. Phone-relayed playback also requires a network that lets the receiver connect to the phone.

## Copyright and content sources

ReSeq is an independent media player. Users supply their own playback sources and must follow their providers’ terms, licensing requirements and applicable copyright law. ReSeq distributes no broadcasts, IPTV subscriptions, playlists or playback addons. The default Cinemeta and OpenSubtitles v3 endpoints provide metadata and subtitles. Third-party names identify compatibility and attribution. [Content sources and rights concerns](https://github.com/abbaskip/Nuvio-ReSeq#copyright-content-sources-and-independence)

## Credits and source

ReSeq builds on Nuvio by Tapframe and contributors and Nuvio + IPTV by homelessbrian and contributors. Stalker support incorporates MIT-licensed code from IPTVnator. The tagged source is available under GPLv3 with the upstream and mobile notices. [Nuvio](https://github.com/NuvioMedia/NuvioTV) · [Nuvio + IPTV](https://github.com/homelessbrian/NuvioPlusIPTV) · [IPTVnator](https://github.com/4gray/iptvnator) · [Mobile credits](https://github.com/abbaskip/Nuvio-ReSeq/blob/mobile-v0.1.0-beta.3.1/NOTICE-MOBILE.md) · [Licence](https://github.com/abbaskip/Nuvio-ReSeq/blob/mobile-v0.1.0-beta.3.1/LICENSE)
