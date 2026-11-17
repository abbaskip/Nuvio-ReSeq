"""Generate the local A/V fixtures for the three-pane layout regression and 30-minute soak."""
import argparse
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=("layout", "soak"))
    parser.add_argument("output", type=Path)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def ffmpeg(*argv):
        subprocess.run([args.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", *argv], check=True)

    with TemporaryDirectory(dir=args.output.parent) as scratch:
        if args.scenario == "soak":
            clip = Path(scratch) / "pattern.mp4"
            ffmpeg("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15",
                   "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "6",
                   "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "veryfast",
                   "-g", "30", "-b:v", "120k", "-c:a", "aac", "-b:a", "32k", clip)
            ffmpeg("-stream_loop", "335", "-i", clip, "-t", "2000", "-c", "copy",
                   "-movflags", "+faststart", "-f", "mp4", "-y", args.output)
            return

        source = Path(scratch) / "source.ts"
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=480x270:rate=24",
               "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "38",
               "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-g", "48",
               "-b:v", "20M", "-minrate", "20M", "-maxrate", "20M", "-bufsize", "2M",
               "-x264-params", "nal-hrd=cbr", "-c:a", "aac", "-b:a", "64k", "-f", "mpegts", source)
        # The video burst exceeds one pane's byte target while it is waiting for audio.
        # Deliver every original audio packet in order, with its original timestamp.
        packets = source.stat().st_size // 188
        start, end = packets * 5 // 38, packets * 22 // 38
        held = bytearray()
        with source.open("rb") as src, args.output.open("wb") as dst:
            for index in range(packets):
                packet = src.read(188)
                assert packet[0] == 0x47
                pid = ((packet[1] & 31) << 8) | packet[2]
                if start < index < end and pid == 257:
                    held.extend(packet)
                else:
                    if index == end:
                        assert held
                        dst.write(held)
                    dst.write(packet)


if __name__ == "__main__":
    main()
