"""Rebuild the single Java class changed in ReSeq's vendored ExoPlayer AAR.

Supply the original lib-exoplayer-release.aar from c779d76eb, an AndroidX
annotation-jvm JAR, and an output path. The checked-in Java source derives from
halibiram/media 987ad37165d9180c7bcd52e2cbcd5fbb009f1f69 under Apache-2.0.
Use javac 17 or 21 on PATH. The Android class-file target is Java 8.
"""
import argparse
import hashlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def replace_entry(archive, name, data):
    result = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(archive)) as source, zipfile.ZipFile(result, "w") as target:
        assert source.namelist().count(name) == 1
        for entry in source.infolist():
            target.writestr(entry, data if entry.filename == name else source.read(entry))
        target.comment = source.comment
    return result.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("original", type=Path)
    parser.add_argument("annotation", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--javac", default="javac")
    args = parser.parse_args()
    original = args.original.read_bytes()
    assert hashlib.sha256(original).hexdigest() == "d8c5a4357c4672c289164597d0eb1a5088daffdb1b1bdb94fcf5899fc4ad4a95"
    assert args.output.resolve() != args.original.resolve()
    here = Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory() as directory:
        work = Path(directory)
        with zipfile.ZipFile(io.BytesIO(original)) as aar:
            classes = aar.read("classes.jar")
        (work / "player.jar").write_bytes(classes)
        with zipfile.ZipFile(here.parents[1] / "app/libs/lib-common-release.aar") as common:
            (work / "common.jar").write_bytes(common.read("classes.jar"))
        classpath = os.pathsep.join(map(str, (work / "player.jar", work / "common.jar", args.annotation.resolve())))
        subprocess.run([args.javac, "--release", "8", "-g", "-proc:none", "-classpath", classpath,
                        "-d", str(work), str(here / "DefaultAllocator.java")], check=True)
        entry = "androidx/media3/exoplayer/upstream/DefaultAllocator.class"
        patched = replace_entry(classes, entry, (work / entry).read_bytes())
        args.output.write_bytes(replace_entry(original, "classes.jar", patched))


if __name__ == "__main__":
    main()
