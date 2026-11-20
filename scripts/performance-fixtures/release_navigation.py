"""Collect the same 24 remote actions from an already provisioned, signed TV app."""
import argparse
import json
from pathlib import Path
import re
from statistics import median
import subprocess
import time
from xml.etree import ElementTree

RESEQ = "io.github.abbaskip.nuvioreseq"
UPSTREAM = "com.nuvio.livetv"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--user", type=int, required=True)
    parser.add_argument("--package", required=True, choices=[RESEQ, UPSTREAM])
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    package = args.package
    app = package + "/com.nuvio.tv.launcher.AppIconDefault"

    def adb(*cmd):
        return subprocess.check_output([args.adb,"-s",args.serial,*map(str,cmd)], text=True,
                                       encoding="utf-8",errors="replace",timeout=75).strip()

    assert args.user > 0 and int(adb("shell","am","get-current-user")) == args.user
    assert re.search(r"UserInfo\{" + str(args.user) + r":ReSeqPlaybackQA:",adb("shell","pm","list","users"))
    assert adb("shell","pm","path","--user",args.user,package).startswith("package:")
    assert not re.search(r"state=(?:PLAYING\(3\)|3),",adb("shell","dumpsys","media_session"))
    assert not args.output.exists()
    result = {"package":package,"model":adb("shell","getprop","ro.product.model"),"cold":[],"traces":{}}

    def record():
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(result,indent=2))

    def ui():
        doc = "/data/local/tmp/reseq-perf-navigation.xml"
        adb("shell","uiautomator","dump",doc)
        return list(ElementTree.fromstring(adb("exec-out","cat",doc)).iter("node"))

    def xy(node):
        x,y,r,b = map(int,re.findall(r"-?\d+",node.get("bounds")))
        return (x+r)//2,(y+b)//2

    def label(node):
        return node.get("text") or node.get("content-desc") or ""

    def remote(key):
        adb("shell","input","keyevent","KEYCODE_"+key)

    def cold():
        return adb("shell","am","start","--user",args.user,"-S","-W","-n",app)

    def rail(names, activate=True):
        for _ in range(24):
            nodes = ui()
            focused = next((n for n in nodes if n.get("focused") == "true"),None)
            targets = [n for n in nodes if label(n) in names and xy(n)[0] < 300]
            if focused is not None and xy(focused)[0] < 210 and any(label(n) in names for n in focused.iter()):
                if activate:
                    remote("DPAD_CENTER")
                return
            if focused is None or xy(focused)[0] > 210 or not targets:
                direction = "LEFT"
            else:
                direction = "DOWN" if xy(focused)[1] < xy(targets[-1])[1] else "UP"
            remote("DPAD_"+direction)
            time.sleep(.15)
        raise RuntimeError("Unreachable navigation item "+"/".join(names))

    def trace(name, first, second):
        adb("shell","dumpsys","gfxinfo",package,"reset")
        for direction in [first]*12 + [second]*12:
            remote("DPAD_"+direction)
            time.sleep(.15)
        time.sleep(1)
        gfx = adb("shell","dumpsys","gfxinfo",package)
        metrics = {key:int(re.search(pattern,gfx)[1]) for key,pattern in {
            "frames":r"Total frames rendered:\s*(\d+)", "p50Ms":r"50th percentile:\s*(\d+)ms",
            "p95Ms":r"95th percentile:\s*(\d+)ms","p99Ms":r"99th percentile:\s*(\d+)ms"}.items()}
        assert metrics["frames"]>0
        result["traces"].setdefault(name,[]).append(metrics)
        record()

    for _ in range(5):
        before = float(adb("shell","date","+%s.%3N"))
        launch = cold()
        first = int(re.search(r"TotalTime:\s*(\d+)", launch)[1])
        time.sleep(12)
        logs = adb("logcat","-d","-v","epoch","-s","ActivityTaskManager:I")
        drawn = [line.rsplit(": +",1)[-1].strip() for line in logs.splitlines()
                 if re.match(r"^\s*\d+\.\d+",line) and float(line.split()[0]) >= before
                 and "Fully drawn "+package in line and ": +" in line]
        value = re.fullmatch(r"(?:(\d+)s)?(?:(\d+)ms)?", drawn[-1]) if drawn else None
        result["cold"].append({"initialMs":first,"fullyDrawnMs":int(value[1] or 0)*1000+int(value[2] or 0) if value else None})
        record()
    # Both apps must have received the same controlled Home catalogue and guide
    # in this disposable OS profile. No accounts, identities or visible titles are logged.
    for _ in range(3):
        cold()
        time.sleep(6)
        home = ui()
        assert any("Fixture" in label(n) or "Guest" in label(n) for n in home), "Missing shared Home fixture"
        trace("home","RIGHT","LEFT")
        for destination, names, ready in (
                ("guide",("Live TV",),("Guide display","All channels","All Channels")),
                ("sports",("Sports Hub","Sports"),("My Sports","All Events","TV Now")),
                ("settings",("Settings",),("Profiles","Backup & Sync","Appearance"))):
            rail(names,activate=False)
            started = time.monotonic()
            remote("DPAD_CENTER")
            until = started+25
            while time.monotonic()<until:
                items = ui()
                if any(label(n) in ready for n in items):
                    break
            else:
                raise AssertionError("Destination did not render: "+destination)
            elapsed = round((time.monotonic()-started)*1000)
            result.setdefault("entryUpperBoundsMs",{}).setdefault(destination,[]).append(elapsed)
            if destination=="sports":
                all_events = next((n for n in items if label(n)=="All Events"),None)
                if all_events is not None:
                    x,y = xy(all_events)
                    adb("shell","input","tap",x,y)
                remote("DPAD_DOWN")
            trace(destination,"DOWN","UP")
            cold()
            time.sleep(5)
    result["medianInitialMs"] = median(s["initialMs"] for s in result["cold"])
    record()


if __name__ == "__main__":
    main()
