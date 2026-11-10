#!/usr/bin/env bash
# Never store service-bearing compiled outputs in GitHub's plaintext cache.
set -euo pipefail
test -n "${NUVIO_CACHE_KEY:-}" || exit 0
cache_home="${GRADLE_USER_HOME:-$HOME/.gradle}/caches"
transfer="$RUNNER_TEMP/nuvio-task-cache"
archive="$transfer/outputs.enc"
signature="$transfer/outputs.hmac"
authenticate() {
  python3 - "$1" "$archive" "$signature" <<'PY'
import hashlib, hmac, os, pathlib, sys
mode, archive, signature = sys.argv[1:]
key = hmac.digest(os.environ['NUVIO_CACHE_KEY'].encode(), b'nuvio-task-cache-v1', 'sha256')
digest = hmac.new(key, digestmod=hashlib.sha256)
with open(archive, 'rb') as data:
    for block in iter(lambda: data.read(1024 * 1024), b''):
        digest.update(block)
tag = pathlib.Path(signature)
if mode == 'save':
    tag.write_bytes(digest.digest())
elif not tag.is_file() or not hmac.compare_digest(digest.digest(), tag.read_bytes()):
    sys.exit(1)
PY
}
mkdir -p "$cache_home/build-cache-1"
case "${1:-}" in
  restore)
    if [[ -s "$archive" ]]; then
      # Fail closed on damaged or obsolete ciphertext, but allow a cold build.
      if authenticate restore && openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 -pass env:NUVIO_CACHE_KEY \
          -in "$archive" -out "$RUNNER_TEMP/nuvio-build-cache.tar" 2>/dev/null; then
        tar -xf "$RUNNER_TEMP/nuvio-build-cache.tar" -C "$cache_home"
        echo 'Restored encrypted Gradle task outputs.'
      else
        echo 'Encrypted cache unavailable; using a cold task cache.'
      fi
      rm -f "$RUNNER_TEMP/nuvio-build-cache.tar"
    fi
    ;;
  save)
    mkdir -p "$transfer"
    # The explicit cache save uploads this ciphertext, never the tar file.
    tar -cf - -C "$cache_home" build-cache-1 | openssl enc -aes-256-cbc -salt -pbkdf2 \
      -iter 100000 -pass env:NUVIO_CACHE_KEY -out "$archive"
    authenticate save
    echo 'Encrypted Gradle task outputs for cache reuse.'
    ;;
  *) echo 'Expected restore or save' >&2; exit 2 ;;
esac
