"""Create deterministic large imports for the emulator comparison. Serve only on loopback."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import subprocess
import sys

dest = Path(sys.argv[1])
dest.mkdir(parents=True, exist_ok=True)
base = "http://127.0.0.1:38791"
for name in ("large", "large-alt"):
    with (dest / (name + ".m3u")).open("w", encoding="utf8", newline="\n") as out:
        out.write("#EXTM3U\n")
        for n in range(1, 100001):
            live = n % 50 == 0
            i = n // 50 if live else n
            title = ("Channel " if live else "Film ") + str(i)
            if name == "large-alt" and live and i == 1:
                title = "Updated Channel 1"
            group = "Live" if live else "Films"
            link = f"{base}/channels/station{i}.ts" if live else f"{base}/files/{i}.mp4"
            out.write(f'#EXTINF:-1 tvg-id="channel{i}" tvg-name="{title}" group-title="{group}",{title}\n{link}\n')
start = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0) - timedelta(hours=1)
times = [(start + timedelta(minutes=30 * n)).strftime("%Y%m%d%H%M%S +0000") for n in range(37)]
with (dest / "large.xml").open("w", encoding="utf8", newline="\n") as out:
    out.write("<tv>")
    for i in range(1, 1001):
        out.write(f'<channel id="channel{i}"><display-name>Channel {i}</display-name></channel>')
    for i in range(1, 1001):
        for n, (begin, end) in enumerate(zip(times, times[1:])):
            out.write(f'<programme channel="channel{i}" start="{begin}" stop="{end}"><title>Show {n}</title></programme>')
    out.write("</tv>")
subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                "testsrc2=size=640x360:rate=25", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "100", "-c:v", "libx264", "-preset", "ultrafast", "-threads", "2", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-f", "mpegts", str(dest / "continuous.ts")], check=True)
