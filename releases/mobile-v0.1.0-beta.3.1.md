# Nuvio ReSeq Mobile Beta 3.1

Live TV now sends the provider’s channel URL directly to Chromecast when the playlist supplies no special request headers. This lets the TV fetch the stream itself, including on networks where it can reach the provider but cannot connect back to the phone.

If the TV rejects the direct stream or stays on the loading screen, ReSeq retries once through the phone. The phone can package a raw MPEG transport stream as HLS for that retry. Channels that specify a user agent, cookie or other required request header use the phone relay from the start.

ReSeq also checks the format of extensionless IPTV URLs with a header-only request first. It requests a small byte range if the provider needs a GET.

To update from mobile Beta 3, open **Settings → About and credits → Check for updates**, then tap **Download update**. For manual installation, choose the ARM64 APK for a recent Android phone or tablet, or the universal APK for other devices.
