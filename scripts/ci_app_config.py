"""Prepare approved CI app services without writing personal TMDB credentials to build inputs."""
from __future__ import annotations

import argparse
import base64
import os
from pathlib import Path
import re


def decoded_config(encoded: str) -> str:
    return base64.b64decode("".join(encoded.split()), validate=True).decode("utf-8-sig")


def personal_tmdb_property(name: str) -> bool:
    return bool(re.search(r"TMDB.*(?:KEY|TOKEN)", name, re.IGNORECASE))


def excluded_tmdb_values(encoded: str) -> list[str]:
    values = []
    for line in decoded_config(encoded).splitlines():
        if "=" not in line or line.lstrip().startswith(("#", "!")):
            continue
        name, value = line.split("=", 1)
        if personal_tmdb_property(name) and value.strip():
            values.append(value.strip())
    return values


def sanitized_config(encoded: str) -> str:
    result = []
    for line in decoded_config(encoded).splitlines():
        # Comments are unnecessary in machine-generated build inputs.
        if not line.strip() or line.lstrip().startswith(("#", "!")):
            continue
        name = line.split("=", 1)[0].strip()
        if name in ("sdk.dir", "NUVIO_RELEASE_STORE_FILE") or personal_tmdb_property(name):
            continue
        result.append(line)
    return "\n".join(result) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(sanitized_config(os.environ["APP_CONFIG"]), encoding="utf-8")
    print("Approved app-service inputs prepared; personal TMDB credentials excluded; values withheld.")


if __name__ == "__main__":
    main()
