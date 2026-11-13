#!/usr/bin/env bash
# radio-bundle.sh: the radio bundle as release.yml builds it, for the size job (docs/size.md, "What it
# measures"). The release's armeabi-v7a APK carries it (assets/radio-bundle/), so the size job builds
# that APK with it too.
#
#   tools/size/radio-bundle.sh <repo-root> <out-dir>      # -> <out-dir>/radio-bundle/
#
# The same inputs as release.yml's "Sign the radio bundle" step, from <repo-root>:
#   s1kiosk_radio.sh         tools/radio/s1kiosk_radio.sh
#   otbr-agent, ot-ctl       downloaded from kiln/assets.lock.json's URLs, checked against its SHA-256
#   sisdk/                   tools/radio/build-sisdk-runtime.sh's output, checked against
#                            tools/radio/sisdk-runtime.sha256 (SISDK_DIR: a copy to reuse, such as
#                            CI's cache; it is checked the same way and rebuilt if it doesn't match)
# Signed with a throwaway Ed25519 key made here, not the release key: the signature is 64 bytes
# whichever key makes it, so the APK's bundle is the shipped one but for manifest.sig and the
# manifest's version line. No secret is read. BUILD ONLY: never talks to a device. Needs docker
# (for the sisdk build), python3 and openssl.
#
# Keep it in step with release.yml: tools/size/test/test_apksize.py checks that both bundle the same
# files.
set -euo pipefail
[ $# -eq 2 ] || { echo "usage: $0 <repo-root> <out-dir>" >&2; exit 2; }
SRC="$(cd "$1" && pwd -P)"
OUT="$2"
W="$(mktemp -d)"
# Best effort: the sisdk build's container leaves root-owned files in its build directory.
trap 'rm -rf "$W" 2>/dev/null || true' EXIT

sisdk_ok() {   # the directory holds exactly the committed runtime
  [ -d "$1" ] || return 1
  diff -q <(cd "$1" && find . -type f | LC_ALL=C sort | xargs shasum -a 256) "$SRC/tools/radio/sisdk-runtime.sha256" >/dev/null
}
SISDK="${SISDK_DIR:-$W/sisdk}"
if sisdk_ok "$SISDK"; then
  echo "sisdk: $SISDK matches tools/radio/sisdk-runtime.sha256"
else
  rm -rf "$SISDK"
  SISDK_OUT="$SISDK" SISDK_BUILD="$W/sisdk-build" "$SRC/tools/radio/build-sisdk-runtime.sh"
fi
# A copy this user owns (the build's container writes as root on Linux), with the modes release.yml
# sets after its artifact (which doesn't keep them).
cp -R "$SISDK" "$W/sisdk-modes"
SISDK="$W/sisdk-modes"
find "$SISDK" -type f -exec chmod 0644 {} +
chmod 0755 "$SISDK/zigbeed.sh" "$SISDK/lib/ld-linux-armhf.so.3"

mkdir -p "$W/radio-in"
python3 - "$SRC/kiln/assets.lock.json" "$W/radio-in" <<'PY'
import hashlib, json, sys, urllib.request
lock, out = sys.argv[1:]
for a in json.load(open(lock))["assets"]:
    if a["name"] not in ("otbr-agent", "ot-ctl"):
        continue
    data = urllib.request.urlopen(a["url"], timeout=120).read()
    h = hashlib.sha256(data).hexdigest()
    if h != a["sha256"]:
        sys.exit(f"{a['name']}: sha256 {h}, expected {a['sha256']}")
    open(f"{out}/{a['name']}", "wb").write(data)
    print(a["name"], len(data), h)
PY

openssl genpkey -algorithm ed25519 -out "$W/throwaway.pem" 2>/dev/null
rm -rf "$OUT/radio-bundle"
python3 "$SRC/tools/radio/make-bundle.py" --version 1 --force --key "$W/throwaway.pem" --out "$OUT" \
  --file s1kiosk_radio.sh="$SRC/tools/radio/s1kiosk_radio.sh":0700 \
  --file "otbr-agent=$W/radio-in/otbr-agent:0700" \
  --file "ot-ctl=$W/radio-in/ot-ctl:0700" \
  --dir "sisdk=$SISDK"
du -sh "$OUT/radio-bundle"
