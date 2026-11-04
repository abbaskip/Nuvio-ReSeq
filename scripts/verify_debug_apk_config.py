"""Validate the finished debug APK's BuildConfig values without displaying credentials."""
import argparse
import json
from pathlib import Path
import struct
import zipfile
from verify_apk_services import dex_strings, properties, verify_backend


def uleb(data, position):
    value = shift = 0
    while True:
        byte = data[position]
        position += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, position
        shift += 7


def build_fields(data):
    strings = list(dex_strings(data))
    count, offset = struct.unpack_from("<II", data, 64)
    types = [strings[struct.unpack_from("<I", data, offset + i * 4)[0]] for i in range(count)]
    field_count, field_offset = struct.unpack_from("<II", data, 80)
    count, offset = struct.unpack_from("<II", data, 96)
    for index in range(count):
        values = struct.unpack_from("<8I", data, offset + index * 32)
        if types[values[0]] != "Lcom/nuvio/tv/BuildConfig;":
            continue
        static_count, pos = uleb(data, values[6])
        for _ in range(3):
            _, pos = uleb(data, pos)
        names = []
        field_index = 0
        for _ in range(static_count):
            diff, pos = uleb(data, pos)
            _, pos = uleb(data, pos)
            field_index += diff
            assert field_index < field_count
            name_index = struct.unpack_from("<I", data, field_offset + field_index * 8 + 4)[0]
            names.append(strings[name_index])
        count, pos = uleb(data, values[7])
        result = {}
        for name in names[:count]:
            header = data[pos]
            pos += 1
            kind, arg = header & 31, header >> 5
            if kind == 31:
                value = bool(arg)
            elif kind == 30:
                value = None
            else:
                width = arg + 1
                raw = int.from_bytes(data[pos:pos + width], "little")
                pos += width
                value = strings[raw] if kind == 23 else raw
            result[name] = value
        return result
    return None


def verify(apk, inputs):
    verify_backend([apk], inputs, [])
    found = None
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                found = build_fields(archive.read(name)) or found
    if found is None:
        raise ValueError("Debug APK BuildConfig is absent")
    if found.get("TMDB_API_KEY") != "":
        raise ValueError("Finished APK has a nonempty TMDB build credential")
    if found.get("SUPABASE_URL") != inputs.get("NUVIO_SUPABASE_URL") or found.get("SUPABASE_ANON_KEY") != inputs.get("NUVIO_SUPABASE_ANON_KEY"):
        raise ValueError("Finished APK backend fields do not match approved inputs")
    return {"apk": apk.name, "backend_url_configured": True, "backend_key_configured": True,
            "actual_tmdb_build_key_empty": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--properties", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.apk, properties(args.properties))))
