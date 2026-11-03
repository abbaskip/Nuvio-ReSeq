"""Check application service configuration in finished APKs without logging values."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
from urllib.parse import urlparse
import zipfile

REQUIRED = (
    "NUVIO_SUPABASE_URL", "NUVIO_SUPABASE_ANON_KEY", "TV_LOGIN_WEB_BASE_URL",
    "DEVICE_LOGIN_WEB_BASE_URL", "TRAKT_CLIENT_ID", "TRAKT_REDIRECT_URI",
    "SIMKL_CLIENT_ID", "SIMKL_APP_NAME", "MDBLIST_CLIENT_ID",
    "GOOGLE_DRIVE_CLIENT_ID", "GOOGLE_DRIVE_CLIENT_SECRET",
)
PLACEHOLDERS = ("placeholder", "changeme", "replace-me", "your-project", "yourproject",
                "your-supabase", "project-ref", "your-key", "your-anon")


def dex_strings(data: bytes):
    if not data.startswith(b"dex\n"):
        raise ValueError("Invalid APK DEX payload")
    count, offset = struct.unpack_from("<II", data, 56)
    for index in range(count):
        pos = struct.unpack_from("<I", data, offset + 4 * index)[0]
        while data[pos] & 128:
            pos += 1
        pos += 1
        yield data[pos:data.index(b"\0", pos)].decode("utf-8", errors="replace")


def properties(path: Path) -> dict[str, str]:
    values = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if not line.strip() or line.lstrip().startswith(("#", "!")) or "=" not in line:
            continue
        name, value = line.split("=", 1)
        value = value.strip()
        for escaped, literal in ((r"\:", ":"), (r"\/", "/"), (r"\n", "\n"), (r"\r", "\r"), (r"\\", "\\")):
            value = value.replace(escaped, literal)
        values[name.strip()] = value
    return values


def verify(apks: list[Path], values: dict[str, str], forbidden: str | list[str] = "") -> list[dict]:
    forbidden_values = [forbidden] if isinstance(forbidden, str) else forbidden
    for name in REQUIRED:
        value = values.get(name, "").strip()
        normalized = value.lower().replace("_", "-").replace(" ", "-")
        if not value or any(word in normalized for word in PLACEHOLDERS):
            raise ValueError(f"Required application service is missing or a placeholder: {name}")
    for name in ("NUVIO_SUPABASE_URL", "TV_LOGIN_WEB_BASE_URL", "DEVICE_LOGIN_WEB_BASE_URL"):
        url = urlparse(values[name])
        if (url.scheme not in ("https", "http") or not url.hostname or url.username or
                url.hostname in ("localhost", "127.0.0.1", "::1") or
                url.hostname.endswith((".invalid", ".example")) or "example." in url.hostname):
            raise ValueError(f"Application service URL is invalid: {name}")
    if len(values["NUVIO_SUPABASE_ANON_KEY"].strip()) < 20:
        raise ValueError("Backend API key is missing or a placeholder")
    if values.get("TMDB_API_KEY", "").strip():
        raise ValueError("Personal TMDB credentials must not be supplied to APK builds")
    if len(apks) != 5:
        raise ValueError("Expected all five APK variants")
    results = []
    reference = None
    for apk in apks:
        with zipfile.ZipFile(apk) as archive:
            dex = {name: archive.read(name) for name in archive.namelist()
                   if name.startswith("classes") and name.endswith(".dex")}
        if not dex:
            raise ValueError(f"APK contains no DEX payload: {apk.name}")
        literals = {value for data in dex.values() for value in dex_strings(data)}
        for name in REQUIRED:
            if values[name] not in literals:
                raise ValueError(f"Actual APK is missing configured service {name}: {apk.name}")
        if any(value and value in literals for value in forbidden_values):
            raise ValueError("Excluded personal TMDB credential found in actual APK")
        fingerprints = {name: hashlib.sha256(data).hexdigest() for name, data in dex.items()}
        if reference is None:
            reference = fingerprints
        elif reference != fingerprints:
            raise ValueError("APK DEX payloads differ between architecture variants")
        results.append({"apk": apk.name, "backend_url_configured": True, "backend_key_configured": True,
                        "trackers_configured": True, "google_drive_configured": True,
                        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()})
    return results


def verify_backend(apks: list[Path], values: dict[str, str], forbidden: list[str]) -> list[dict]:
    """Instrumentation harnesses carry the same backend without unrelated app services."""
    if not apks:
        raise ValueError("No APKs found")
    url = urlparse(values.get("NUVIO_SUPABASE_URL", ""))
    key = values.get("NUVIO_SUPABASE_ANON_KEY", "")
    if (url.scheme not in ("http", "https") or not url.hostname or url.username or
            url.hostname in ("localhost", "127.0.0.1", "::1") or
            url.hostname.endswith((".invalid", ".example")) or "example." in url.hostname or len(key) < 20 or
            any(word in key.lower().replace("_", "-") for word in PLACEHOLDERS)):
        raise ValueError("Backend configuration is missing or invalid")
    result = []
    for apk in apks:
        with zipfile.ZipFile(apk) as archive:
            literals = {value for name in archive.namelist() if name.startswith("classes") and name.endswith(".dex")
                        for value in dex_strings(archive.read(name))}
        if values["NUVIO_SUPABASE_URL"] not in literals or key not in literals:
            raise ValueError("Actual test APK is missing configured backend services")
        if any(value and value in literals for value in forbidden):
            raise ValueError("Excluded personal TMDB credential found in actual APK")
        result.append({"apk": apk.name, "backend_url_configured": True, "backend_key_configured": True,
                       "personal_tmdb_excluded": True, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()})
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--properties", type=Path, required=True)
    parser.add_argument("--apk-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--backend-only", action="store_true")
    args = parser.parse_args()
    forbidden = [os.environ.get("RESEQ_FORBIDDEN_TMDB_KEY", "")]
    if os.environ.get("APP_CONFIG"):
        from ci_app_config import excluded_tmdb_values
        forbidden.extend(excluded_tmdb_values(os.environ["APP_CONFIG"]))
    apks = sorted(args.apk_dir.glob("*.apk"))
    values = properties(args.properties)
    results = verify_backend(apks, values, forbidden) if args.backend_only else verify(apks, values, forbidden)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print("Actual APK service configuration verified; values withheld.")
