"""Verify the merged ART rules and the profiles in the final signed APKs."""
import argparse
import io
import re
import struct
import zipfile
import zlib
from pathlib import Path

from prepare_release_apks import ABIS

ROOT = Path(__file__).resolve().parents[1]
RULES = ROOT / "app/src/main/baselineProfiles/reseq-packages.txt"
ASSETS = "assets/dexopt/baseline."


def packages(rules):
    result = set()
    for line in rules:
        match = re.fullmatch(r"(?:L|HPL)([\w/]+)/\*\*;(?:->\*\*\(\*\*\)\*\*)?", line)
        if not match:
            raise ValueError(f"Unexpected rule in {RULES.name}: {line}")
        result.add(match[1] + "/")
    return result


def source_packages():
    result = set()
    for path in (ROOT / "app/src/main/java").rglob("*"):
        if path.suffix in {".kt", ".java"}:
            match = re.search(r"^package\s+([\w.]+)", path.read_text(encoding="utf-8-sig"), re.M)
            if match:
                result.add(match[1].replace(".", "/") + "/")
    for aar in (ROOT / "app/libs").glob("lib-*-release.aar"):
        with zipfile.ZipFile(aar) as archive:
            if "classes.jar" in archive.namelist():
                with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as jar:
                    result.update(p.rpartition("/")[0] + "/" for p in jar.namelist() if p.endswith(".class"))
    return result


def read_profile(data):
    # AGP writes Android P (010) for ProfileInstaller. Its header table is followed
    # by one hot-method region, delta-encoded class set and method bitmap per DEX.
    if data[:8] != b"pro\x00010\x00":
        raise ValueError("Unsupported or missing binary ART profile")
    count, size, compressed = struct.unpack_from("<BII", data, 8)
    body = zlib.decompress(data[17:])
    if len(body) != size or compressed != len(data) - 17 or not count:
        raise ValueError("Empty or truncated ART profile")
    headers = []
    offset = 0
    for _ in range(count):
        key_size, classes, hot_size, _, methods = struct.unpack_from("<HHIII", body, offset)
        offset += 16
        key = body[offset:offset + key_size].decode()
        offset += key_size
        dex = re.split(r"[!:]", key)[-1]
        headers.append((dex if dex.endswith(".dex") else "classes.dex", classes, hot_size, methods))
    result = {}
    for dex, classes, hot_size, methods in headers:
        offset += hot_size
        index = 0
        indices = []
        for (delta,) in struct.iter_unpack("<H", body[offset:offset + classes * 2]):
            index += delta
            indices.append(index)
        result[dex] = indices
        offset += classes * 2 + (methods * 2 + 7) // 8
    if offset != len(body) or not any(result.values()):
        raise ValueError("Invalid or empty ART class set")
    return result


def dex_classes(dex, indices):
    strings = struct.unpack_from("<I", dex, 60)[0]
    types = struct.unpack_from("<I", dex, 68)[0]
    for index in indices:
        string_id = struct.unpack_from("<I", dex, types + 4 * index)[0]
        start = struct.unpack_from("<I", dex, strings + 4 * string_id)[0]
        while dex[start] & 128:
            start += 1
        start += 1
        yield dex[start:dex.index(b"\x00", start)].decode("utf-8", errors="replace")


def verify(apk_dir, intermediates, mapping):
    rules = RULES.read_text().splitlines()
    required = packages(rules)
    merged = list((intermediates / "merged_art_profile/fullRelease").rglob("*.txt"))
    if not any(set(rules) <= set(p.read_text().splitlines()) for p in merged):
        raise ValueError("Release merge is missing hand-written rules")
    apks = list(apk_dir.glob("*.apk"))
    if not apks:
        raise ValueError("No release APKs")
    universal, = [p for p in apks if p.name.endswith("-universal.apk")]
    expected = {universal.name.replace("-universal.apk", f"-{abi}.apk") for abi in (*ABIS, "universal")}
    if {p.name for p in apks} != expected:
        raise ValueError("Expected the universal APK and all four ABI APKs")
    with zipfile.ZipFile(universal) as apk:
        prof, meta = (apk.read(ASSETS + ext) for ext in ("prof", "profm"))
        if not meta.startswith(b"prm\x00") or len(meta) <= 16:
            raise ValueError("Missing ART metadata")
        dexes = {n: apk.getinfo(n).CRC for n in apk.namelist() if re.fullmatch(r"classes\d*\.dex", n)}
        owners = {name for dex, ids in read_profile(prof).items() for name in dex_classes(apk.read(dex), ids)}
    covered = {p for p in required if any(name.startswith("L" + p) for name in owners)}
    # Resolve R8's residual class names back to source owners.
    with mapping.open(encoding="utf-8") as lines:
        for line in lines:
            if not line.endswith(":\n") or line.startswith((" ", "#")):
                continue
            original, residual = line.rstrip()[:-1].split(" -> ")
            if "L" + residual.replace(".", "/") + ";" in owners:
                prefix = original.replace(".", "/") + "/"
                covered.update(p for p in required - covered if prefix.startswith(p))
    if covered != required:
        raise ValueError(f"No compiled classes in {sorted(required - covered)}")
    for path in apks:
        with zipfile.ZipFile(path) as apk:
            if apk.read(ASSETS + "prof") != prof or apk.read(ASSETS + "profm") != meta:
                raise ValueError(f"Different or missing profile in {path.name}")
            if {n: apk.getinfo(n).CRC for n in apk.namelist() if re.fullmatch(r"classes\d*\.dex", n)} != dexes:
                raise ValueError(f"Profile DEX set differs in {path.name}")
    print(f"Verified {len(owners)} profiled class descriptors and {len(required)} packages across {len(apks)} APKs")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", type=Path, default=Path("build/release"))
    parser.add_argument("--intermediates", type=Path, default=Path("app/build/intermediates"))
    parser.add_argument("--mapping", type=Path, default=Path("app/build/outputs/mapping/fullRelease/mapping.txt"))
    args = parser.parse_args()
    verify(args.apk_dir, args.intermediates, args.mapping)
