"""Check the CS3 host classes required by external DEX after release shrinking."""
import argparse
from pathlib import Path
import struct
import zipfile

from verify_apk_services import dex_strings


# Names are part of the binary interface used by CineStream 487 and StreamPlay 694.
REQUIRED = {
    "com/google/gson/Gson",
    "com/google/gson/JsonParser",
    "com/google/gson/annotations/SerializedName",
    "kotlinx/serialization/KSerializer",
    "kotlinx/serialization/SerializersKt",
    "kotlinx/serialization/json/Json",
    "kotlinx/serialization/modules/SerializersModule",
    "com/fleeksoft/ksoup/Ksoup",
    "com/fleeksoft/ksoup/nodes/Document",
    "com/fleeksoft/ksoup/nodes/Element",
    "com/fleeksoft/ksoup/select/Elements",
    "com/lagradost/cloudstream3/plugins/PluginData",
}


def defined_classes(dex):
    strings = list(dex_strings(dex))
    _, types = struct.unpack_from("<II", dex, 64)
    count, definitions = struct.unpack_from("<II", dex, 96)
    for index in range(count):
        type_index, = struct.unpack_from("<I", dex, definitions + index * 32)
        string_index, = struct.unpack_from("<I", dex, types + type_index * 4)
        yield strings[string_index]


def verify(apk):
    missing = {f"L{name};" for name in REQUIRED}
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.namelist():
            if entry.startswith("classes") and entry.endswith(".dex"):
                missing.difference_update(defined_classes(archive.read(entry)))
    if missing:
        raise ValueError(f"Missing CS3 runtime classes in {apk.name}: {', '.join(sorted(missing))}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    verify(parser.parse_args().apk)
    print("Verified CS3 runtime class definitions in the finished APK.")
