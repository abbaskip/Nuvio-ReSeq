"""Collect a UI failure only from an empty, fixture-owned Actions emulator."""
import json
import os
from pathlib import Path
import re

from signed_rollback import PACKAGE, SERIAL, adb, run, tree


def capture(output: Path):
    assert os.environ.get("GITHUB_ACTIONS") == "true" and adb("shell","getprop","ro.kernel.qemu") == "1"
    output.mkdir(parents=True, exist_ok=True)
    (output/"failure.png").write_bytes(run("adb","-s",SERIAL,"exec-out","screencap","-p"))
    nodes = list(tree().iter("node"))
    crash = adb("logcat","-d","-b","crash","-t","100")
    summary = {"packages":sorted({n.get("package","") for n in nodes}), "nodes":len(nodes),
               "failures":re.findall(r"(?:java|android|kotlin)[\w.]+(?:Exception|Error)\b", crash)[-8:],
               "appHasProcess":PACKAGE in adb("shell","ps","-A","-o","NAME").splitlines()}
    (output/"failure.json").write_text(json.dumps(summary,indent=2))
