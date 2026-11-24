# Nuvio ReSeq Mobile Beta 3

Tap the video to show the player controls. The Cast button stays visible beside the title when playing a debrid stream, a saved download or a Usenet release. Choose your TV to move playback to it. If Google Cast cannot start, the phone explains the problem and lets you retry.

For direct movie and episode links, ReSeq first lets the receiver fetch the video. If delivery fails or playback never starts, it retries through the phone once. A receiver codec error lets you choose another source.

Live TV casting now distinguishes the provider stream from the phone’s rewind playlist. ReSeq buffers several seconds of a raw transport stream before offering it to the receiver. If the receiver keeps buffering for 30 seconds after the load request, the phone reports the failure and can resume the channel locally.

For downloads and Usenet, keep the phone on the receiver’s network. The phone serves the original file first and can prepare a compatible copy when the receiver rejects the format. The Cast controls support seeking and collapse to a mini-player while you browse.

ReSeq now checks for mobile updates when you open the app. You can also open **Settings → About and credits → Check for updates**. Tap **Download update** to download the matching APK inside ReSeq. The first time, Android may ask you to allow installs from ReSeq Mobile. Return to ReSeq and confirm **Update** in the Android installer.

To upgrade from Beta 2 or earlier, install the ARM64 APK on a recent Android phone or tablet, or choose universal for other architectures. Both update the existing signed mobile app in place.

The default Cast receiver on a physical Smart TV Pro played a live MPEG-TS channel, a saved MP4, a Usenet HTTP stream and a signed-link-style stream with a required header in a test using ReSeq’s relay. These checks used test media. Playback with individual providers still depends on their access rules and the receiver’s codec support.
