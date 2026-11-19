"""Compare two profiled, isolated, minified target APKs on the same device in balanced pairs."""
import argparse
import json
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import time
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]
PKG = "io.github.abbaskip.nuvioreseq.debug"
RUNNER = "com.nuvio.tv.delivery.runner"
parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--adb", default="adb")
parser.add_argument("--apks", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--abi", choices=["arm", "x86"], required=True)
parser.add_argument("--pairs", type=int, default=8)
args = parser.parse_args()
out = args.output

def adb(*cmd, timeout=120):
    completed = subprocess.run([args.adb, "-s", args.serial, *map(str,cmd)], stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if completed.returncode:
        raise RuntimeError(completed.stdout[-1200:])
    return completed.stdout.strip()

def available():
    users = re.findall(r"UserInfo\{(\d+):", adb("shell","pm","list","users"))
    for user in users:
        for name in (PKG,RUNNER):
            present = adb("shell","pm","list","packages","-u","--user",user,name).splitlines()
            assert "package:"+name not in present, "A test package already exists"

def idle():
    if args.abi == "arm" and re.search(r"state=(?:PLAYING\(3\)|3),", adb("shell","dumpsys","media_session")):
        raise RuntimeError("Device is playing media")

def summary(rows):
    result = {}
    for measure in ("eventMs","channelMs","cardMs","dialogMs","gameMs"):
        pairs = {}
        for row in rows:
            pairs.setdefault(row["pair"], {})[row["side"]] = row[measure]
        complete = [p for p in pairs.values() if len(p)==2]
        if not complete:
            continue
        values = {"published":[p["published"] for p in complete],
                  "integration":[p["integration"] for p in complete],
                  "pairedDelta":[p["integration"]-p["published"] for p in complete]}
        result[measure] = {key: {"median":statistics.median(v), "min":min(v), "max":max(v),
            "quartiles":statistics.quantiles(v,method="inclusive") if len(v)>1 else []} for key,v in values.items()}
    return result

def main():
    assert args.pairs > 0
    assert (args.abi=="x86" and adb("shell","getprop","ro.kernel.qemu")=="1") or (
        args.abi=="arm" and adb("shell","getprop","ro.product.device")=="jarvis2")
    available()
    idle()
    out.mkdir(parents=True,exist_ok=True)
    source=out/"samples.json"
    rows=json.loads(source.read_text()) if source.exists() else []
    done={(r["pair"],r["side"]) for r in rows}
    assert len(done)==len(rows)
    for port in (38791,38792,38793):
        assert "tcp:"+str(port) not in adb("reverse","--list"), "Fixture port already in use"
    with (out/"server.log").open("ab") as log:
        server = subprocess.Popen([sys.executable, str(ROOT/"scripts/stability_fixtures.py")],
            stdout=log,stderr=subprocess.STDOUT)
        reverse=[]
        owned=[]
        try:
            for _ in range(20):
                if server.poll() is not None: raise RuntimeError("Fixture server did not own its ports")
                try:
                    with urlopen("http://127.0.0.1:38791/control?tvdb=available&completed=false&slowImport=false",timeout=2) as response:
                        assert response.status==200
                    break
                except OSError: time.sleep(.5)
            else: raise RuntimeError("Local fixtures unavailable")
            for port in (38791,38792,38793):
                address="tcp:"+str(port)
                adb("reverse",address,address); reverse.append(address)
            adb("shell","input","keyevent","KEYCODE_WAKEUP")
            assert "Success" in adb("install","-t",args.apks/"integration"/"runner.apk")
            owned.append(RUNNER)
            for pair in range(1,args.pairs+1):
                order=("published","integration") if pair%2 else ("integration","published")
                for side in order:
                    if (pair,side) in done: continue
                    idle()
                    assert "Success" in adb("install","-t",args.apks/side/(args.abi+".apk"))
                    owned.append(PKG)
                    profile=adb("shell","am","broadcast","-a","androidx.profileinstaller.action.INSTALL_PROFILE",
                        "-n",PKG+"/androidx.profileinstaller.ProfileInstallReceiver")
                    assert re.search(r"result=(1|2)\b",profile), profile
                    assert "Success" in adb("shell","cmd","package","compile","-f","-m","speed-profile",PKG)
                    with urlopen("http://127.0.0.1:38791/control?tvdb=available&completed=false&slowImport=false",timeout=5) as response:
                        assert response.status==200
                    output=adb("shell","am","instrument","-w","-r","-e","class","com.nuvio.tv.baselineprofile.SportsDeliveryTest",
                        RUNNER+"/androidx.test.runner.AndroidJUnitRunner",timeout=620)
                    (out/f"{pair:02d}-{side}.txt").write_text(output,encoding="utf-8")
                    assert re.search(r"^OK \(1 test\)",output,re.M), output[-1600:]
                    observation=json.loads(re.search(r"^SPORTS_DELIVERY (\{.*\})$",output,re.M)[1])
                    observation.update(pair=pair,side=side,position=order.index(side)+1)
                    rows.append(observation)
                    source.write_text(json.dumps(rows,indent=2))
                    (out/"summary.json").write_text(json.dumps(summary(rows),indent=2))
                    print(json.dumps(observation),flush=True)
                    assert "Success" in adb("uninstall",PKG); owned.remove(PKG)
        finally:
            for name in reversed(owned): adb("uninstall",name)
            for address in reverse: adb("reverse","--remove",address)
            server.terminate(); server.wait(timeout=12)

if __name__=="__main__": main()
