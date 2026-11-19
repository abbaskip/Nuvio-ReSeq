"""Compile matched human-readable ART profiles for the exact signed APK, then measure its oat files."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile

ROOT = Path.cwd()
OUT = ROOT/"build/profile-cost"
DM = OUT/"metadata"
APK = OUT/"base.apk"
PACKAGE = "io.github.abbaskip.nuvioreseq"

def call(*args):
    return subprocess.check_output(list(map(str,args)), text=True, encoding="utf8", errors="replace").strip()

def prepare():
    from shutil import copyfile
    groups = (ROOT/"app/src/main/baselineProfiles/reseq-packages.txt").read_text().splitlines()
    rules = {m[1].replace("/","."): {line for line in groups if ("L"+m[1]+"/**;") in line}
             for line in groups if (m := re.fullmatch(r"L([^;*]+)/\*\*;",line))}
    files = list((ROOT/"app/build/intermediates/merged_art_profile").rglob("baseline-prof.txt"))
    merged, = [p for p in files if "fullrelease" in str(p).lower()]
    lines = merged.read_text().splitlines()
    assert set.union(*rules.values()) <= set(lines)
    mapping, = (OUT/"mapping").rglob("mapping.txt")
    input_apk, = (OUT/"apks").rglob("*-x86_64.apk")
    copyfile(input_apk, APK)
    assert hashlib.sha256(APK.read_bytes()).hexdigest()=="85536b91337ac7b87fef53d9a3fd120da9e427e508cedefd5bbec47642b0b6f2"
    tool, = [p for p in (Path(os.environ["ANDROID_HOME"])/"cmdline-tools/latest/bin").glob("profgen") if p.is_file()]
    DM.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(APK) as z:
        for name in z.namelist():
            if name.startswith("assets/updater_dm/") and name.endswith(".dm"):
                import io
                blob=z.read(name)
                with zipfile.ZipFile(io.BytesIO(blob)) as archive:
                    if archive.read("primary.prof")[:8]==b"pro\x00015\x00":
                        (DM/"shipped.dm").write_bytes(blob)
    assert (DM/"shipped.dm").exists()
    plans={"all":set(),"no-wildcards":set.union(*rules.values())}
    plans.update({key:val for key,val in rules.items()})
    plans["without-settings-detail"]=rules["com.nuvio.tv.ui.screens.settings"]|rules["com.nuvio.tv.ui.screens.detail"]
    (OUT/"groups.json").write_text(json.dumps({k:sorted(v) for k,v in plans.items()},indent=2))
    for name,omit in plans.items():
        human=OUT/"variant.txt"; binary=OUT/"primary.prof"
        human.write_text("\n".join(line for line in lines if line not in omit)+"\n")
        call(tool,"bin",human,"--apk",APK,"--map",mapping,"--profile-format","V0_1_5_S","--output",binary)
        assert binary.read_bytes()[:8]==b"pro\x00015\x00"
        with zipfile.ZipFile(DM/(name+".dm"),"w",compression=zipfile.ZIP_STORED) as z:
            z.write(binary,"primary.prof")
        print("Prepared",name,binary.stat().st_size,flush=True)

def measure():
    from shutil import copyfile
    def adb(*args): return call("adb","-s","emulator-5554",*args)
    assert os.environ.get("GITHUB_ACTIONS")=="true" and adb("shell","getprop","ro.kernel.qemu")=="1"
    adb("root"); adb("wait-for-device")
    assert adb("shell","id","-u")=="0" and PACKAGE not in adb("shell","pm","list","packages",PACKAGE)
    results={}
    for item in sorted(DM.glob("*.dm")):
        copyfile(item,OUT/"base.dm")
        assert "Success" in adb("install-multiple",APK,OUT/"base.dm")
        try:
            at_install = adb("shell","pm","art","dump",PACKAGE)
            assert "Success" in adb("shell","cmd","package","compile","-f","-m","speed-profile",PACKAGE)
            state=adb("shell","pm","art","dump",PACKAGE)
            assert re.search(r"status[=:]speed-profile",state),state[:800]
            apkpath=adb("shell","pm","path",PACKAGE).split("package:")[1]
            folder=apkpath.rsplit("/",1)[0]+"/oat/x86_64"
            sizes={name:int(adb("shell","stat","-c","%s",folder+"/"+name)) for name in adb("shell","ls","-1",folder).splitlines()
                   if name.endswith((".odex",".vdex",".art"))}
            assert sizes.get("base.odex",0)>0
            results[item.stem]={"bytes":sizes,"speedProfileAtInstall":"status=speed-profile" in at_install,
                                "metadataBytes":item.stat().st_size}
            (OUT/"odex.json").write_text(json.dumps(results,indent=2))
            print(item.stem,sizes,flush=True)
        finally:
            assert "Success" in adb("uninstall",PACKAGE)

if __name__=="__main__":
    {"prepare":prepare,"measure":measure}[sys.argv[1]]()
