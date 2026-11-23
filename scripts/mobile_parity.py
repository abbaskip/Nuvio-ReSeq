"""Check ReSeq's mobile feature inventory and (after assembly) its launcher contract."""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
MOBILE_ID = "io.github.abbaskip.nuvioreseq.mobile"
MOBILE_ACTIVITY = "com.nuvio.tv.mobile.MobileActivity"


def check_inventory(show_gaps=False):
    screen = ROOT / "app/src/main/java/com/nuvio/tv/ui/navigation/Screen.kt"
    routes = set(re.findall(r"\bobject\s+(\w+)\s*:\s*Screen\s*\(", screen.read_text(encoding="utf-8")))
    for source in (ROOT / "app/src/main/java/com/nuvio/tv/ui").rglob("*.kt"):
        routes.update(re.findall(r"\bconst val ([A-Z_]+_ROUTE)\s*=", source.read_text(encoding="utf-8")))
    documented = set()
    rows = (ROOT / "docs/reseq-mobile.md").read_text(encoding="utf-8").splitlines()
    for row in rows:
        if not row.startswith("| "):
            continue
        group, status, note = [cell.strip() for cell in row.strip("|").split("|")]
        if status not in {"Partial", "Pending", "TV only", "Available"}:
            continue
        ids = set(re.findall(r"`(\w+)`", group))
        if documented & ids or not note:
            raise ValueError(f"Duplicate or incomplete inventory row: {group}")
        documented.update(ids)
        if show_gaps and status in {"Partial", "Pending"}:
            print(f"{status} | {', '.join(sorted(ids))} | {note}")
    if not routes or routes != documented:
        raise ValueError(f"Update docs/reseq-mobile.md. Missing {sorted(routes - documented)}, stale {sorted(documented - routes)}")


def check_mobile_manifest(path):
    manifest = ET.parse(path).getroot()
    if manifest.get("package") != MOBILE_ID:
        raise ValueError("Unexpected mobile package")
    touch = next((feature for feature in manifest.findall("uses-feature")
                  if feature.get(ANDROID + "name") == "android.hardware.touchscreen"), None)
    if touch is None or touch.get(ANDROID + "required", "true") != "true":
        raise ValueError("Mobile build must declare touchscreen support")
    forbidden = {"android.software.leanback", "android.permission.REQUEST_INSTALL_PACKAGES",
                 "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION", "android.intent.category.LEANBACK_LAUNCHER",
                 "com.android.providers.tv.permission.READ_EPG_DATA", "com.android.providers.tv.permission.WRITE_EPG_DATA"}
    names = {node.get(ANDROID + "name", "") for node in manifest.iter()}
    if names & forbidden or any(name.startswith(("com.nuvio.tv.launcher.", "com.nuvio.tv.core.sync.androidtv.",
                                                  "com.nuvio.tv.updater.")) for name in names):
        raise ValueError("TV components or install permissions leaked into the mobile package")
    launchers = []
    for activity in manifest.findall("./application/*"):
        for intent in activity.findall("intent-filter"):
            actions = {n.get(ANDROID + "name") for n in intent.findall("action")}
            categories = {n.get(ANDROID + "name") for n in intent.findall("category")}
            if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories:
                launchers.append(activity.get(ANDROID + "name"))
    if launchers != [MOBILE_ACTIVITY]:
        raise ValueError(f"Unexpected mobile launchers: {launchers}")
    for node in manifest.findall("./application/provider"):
        authorities = node.get(ANDROID + "authorities", "").split(";")
        if not all(a.startswith(MOBILE_ID + ".") for a in authorities):
            raise ValueError(f"Mobile provider could collide with TV: {authorities}")


def check_tv_manifest(path):
    manifest = ET.parse(path).getroot()
    if manifest.get("package") != "io.github.abbaskip.nuvioreseq.iptvdebug":
        raise ValueError("Unexpected TV debug package")
    activities = {a.get(ANDROID + "name"): a for a in manifest.findall("./application/activity")}
    default = activities.get("com.nuvio.tv.launcher.AppIconDefault")
    if default is None or MOBILE_ACTIVITY in activities:
        raise ValueError("TV build has the wrong launcher")
    categories = {n.get(ANDROID + "name") for n in default.findall("./intent-filter/category")}
    if not {"android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER"} <= categories:
        raise ValueError("TV launcher intent filters changed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--merged", action="store_true", help="also inspect the built mobile debug manifest")
    parser.add_argument("--tv", action="store_true", help="also inspect the built TV debug launcher")
    parser.add_argument("--gaps", action="store_true", help="list outstanding touch features and verification by TV screen")
    args = parser.parse_args()
    check_inventory(show_gaps=args.gaps)
    for name in ("LICENSE", "NOTICE-MOBILE.md"):
        packaged = ROOT / "app/src/mobile/assets/notices" / name
        if packaged.read_text(encoding="utf-8") != (ROOT / name).read_text(encoding="utf-8"):
            raise ValueError(f"Packaged mobile notice differs from {name}")
    if args.merged:
        path = ROOT / "app/build/intermediates/merged_manifests/mobileDebug/processMobileDebugManifest/universal/AndroidManifest.xml"
        check_mobile_manifest(path)
    if args.tv:
        check_tv_manifest(ROOT / "app/build/intermediates/merged_manifests/fullDebug/processFullDebugManifest/universal/AndroidManifest.xml")
    print("Mobile feature inventory and requested checks passed.")
