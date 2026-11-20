"""Measure one wildcard's marginal contribution with the release shrinker rewriting its profile."""
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

RULES = Path("app/src/main/baselineProfiles/reseq-packages.txt")
GOLDEN_DEX = [0xf5f7e219, 0x8f567992, 0x44b859d6, 0x158b26db, 0x80122bc4]
OUT = Path("build/profile-ablation")


def main():
    lines = RULES.read_text().splitlines()
    packages = [m[1] for line in lines if (m := re.fullmatch(r"L([^;*]+)/\*\*;", line))]
    plans = {name.replace("/", "."): [name] for name in packages}
    plans["all"] = []
    plans["no-wildcards"] = packages
    plans["without-settings-detail"] = [x for x in packages if x.endswith(("/settings", "/detail"))]
    OUT.mkdir(parents=True, exist_ok=True)
    shard = sys.argv[1]
    names = ["all"] if shard == "control" else [k for i, k in enumerate(k for k in plans if k != "all") if i % 4 == int(shard)]
    for name in names:
        omit = {line for line in lines for pkg in plans[name] if "L"+pkg+"/**;" in line}
        RULES.write_text("\n".join(line for line in lines if line not in omit) + "\n")
        subprocess.run(["./gradlew", ":app:assembleFullRelease", "--max-workers=2", "-PuseLocalFfmpegDecoder=false",
                        "-Pkotlin.compiler.execution.strategy=in-process"], check=True)
        apk, = Path("app/build/outputs/apk/full/release").glob("*-x86_64-release.apk")
        with zipfile.ZipFile(apk) as archive:
            names_dex = sorted(n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n))
            crc = [archive.getinfo(n).CRC for n in names_dex]
            assert crc == GOLDEN_DEX, f"DEX differs from shipped 6c80c01: {crc}"
            metadata = archive.read("assets/updater_dm/0.dm")
        with zipfile.ZipFile(io.BytesIO(metadata)) as dm:
            profile = dm.read("primary.prof")
        assert profile.startswith(b"pro\x00015\x00")
        print(name, len(profile), hashlib.sha256(profile).hexdigest(), flush=True)
        if name == "all":
            with zipfile.ZipFile(next(Path("build/profile-cost/apks").rglob("*-x86_64.apk"))) as original:
                with zipfile.ZipFile(io.BytesIO(original.read("assets/updater_dm/0.dm"))) as dm:
                    assert profile == dm.read("primary.prof"), "Control differs from the shipped binary profile"
        (OUT / (name + ".dm")).write_bytes(metadata)


if __name__ == "__main__":
    main()
