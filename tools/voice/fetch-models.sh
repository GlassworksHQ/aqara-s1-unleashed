#!/usr/bin/env bash
# Download the wake word models on the host, check them, and push them to the panel.
#
# The panel fetches the models itself on first use, so this is only for a panel without internet
# or for a model cache on the host (the JVM tests use it). Nothing is bundled with the app.
#
# - microWakeWord (the engine): the four official models (Apache-2.0) from
#   esphome/micro-wake-word-models at a pinned commit, each checked against its SHA-256 (the same
#   hashes as app/kiosk/.../voice/WakeWords.kt). Pushed to .../files/models/micro/.
# - openWakeWord (legacy, with --openwakeword): the v0.5.1 release models, CC BY-NC-SA 4.0 (the
#   code is Apache-2.0), checked to be TFLite flatbuffers. Pushed to .../files/models/.
#
# Both land in the kiosk's app-specific external storage, which needs no root:
#
#   /sdcard/Android/data/sh.edm.s1kiosk/files/models/
#
# Usage:
#   export ANDROID_SERIAL=<panel serial>     # optional if only one device is attached
#   tools/voice/fetch-models.sh              # download + verify + push
#   tools/voice/fetch-models.sh --no-push    # download + verify only
#   tools/voice/fetch-models.sh --openwakeword  # also the legacy openWakeWord models
#
# Env:
#   MODELS_CACHE  host cache root (default: ~/.cache/s1kiosk)
#   OWW_VERSION   openWakeWord release tag (default: v0.5.1)
set -euo pipefail

MWW_COMMIT="40ff33f57f8fc6ad71a75ef085abbf742495b225"
MWW_URL="https://raw.githubusercontent.com/esphome/micro-wake-word-models/${MWW_COMMIT}/models/v2"
MWW_FILES=(
  "okay_nabu.tflite 0689abe1912a95a3318a0d8cb2e67bad0cbcfe3e24dd6e050c75debddfb6f891"
  "hey_jarvis.tflite 21a7976add39ee24ec96c63d96b7aaa18e24d1d9824b963e451da8feb4b78b77"
  "alexa.tflite 9011a8155b04de858c48038529235cbc0e42e9fca05a55bf588cb80a653a723b"
  "hey_mycroft.tflite c2a9b6ed51182db72e014781d5a4ece1929dc232a40b5b4be384f0295f0e1571"
)

OWW_VERSION="${OWW_VERSION:-v0.5.1}"
OWW_URL="https://github.com/dscripka/openWakeWord/releases/download/${OWW_VERSION}"
OWW_FILES=(
  melspectrogram.tflite
  embedding_model.tflite
  hey_jarvis_v0.1.tflite
  alexa_v0.1.tflite
  hey_mycroft_v0.1.tflite
)

PKG="sh.edm.s1kiosk"
REMOTE_DIR="/sdcard/Android/data/${PKG}/files/models"
ROOT="${MODELS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/s1kiosk}"
MWW_CACHE="${ROOT}/microwakeword-${MWW_COMMIT}"
OWW_CACHE="${ROOT}/openwakeword-${OWW_VERSION}"

PUSH=1
OWW=0
for arg in "$@"; do
  case "$arg" in
    --no-push) PUSH=0 ;;
    --openwakeword) OWW=1 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

# A TFLite model is a FlatBuffer whose file identifier "TFL3" sits at byte offset 4.
is_tflite() {
  local f="$1"
  [[ -s "$f" ]] || return 1
  [[ "$(dd if="$f" bs=1 skip=4 count=4 2>/dev/null)" == "TFL3" ]]
}

# fetch URL DEST: download to DEST.part, then move it into place.
fetch() {
  local tmp="$2.part"
  rm -f "$tmp"
  if ! curl -fsSL --retry 3 -o "$tmp" "$1"; then
    rm -f "$tmp"
    echo "  FAILED       $(basename "$2")  ($1)" >&2
    exit 1
  fi
  mv "$tmp" "$2"
}

mkdir -p "$MWW_CACHE"
echo "Downloading the microWakeWord models (esphome/micro-wake-word-models@${MWW_COMMIT:0:7}) into ${MWW_CACHE}"
for entry in "${MWW_FILES[@]}"; do
  f="${entry% *}" want="${entry#* }" dest="${MWW_CACHE}/${f}"
  if [[ -f "$dest" && "$(sha256 "$dest")" == "$want" ]]; then
    echo "  ok (cached)  $f"
    continue
  fi
  fetch "${MWW_URL}/${f}" "$dest"
  got="$(sha256 "$dest")"
  if [[ "$got" != "$want" ]]; then
    rm -f "$dest"
    echo "  BAD SHA-256  $f  (got ${got})" >&2
    exit 1
  fi
  echo "  ok           $f"
done

if [[ "$OWW" -eq 1 ]]; then
  mkdir -p "$OWW_CACHE"
  echo "Downloading the legacy openWakeWord ${OWW_VERSION} models into ${OWW_CACHE}"
  for f in "${OWW_FILES[@]}"; do
    dest="${OWW_CACHE}/${f}"
    if is_tflite "$dest"; then
      echo "  ok (cached)  $f"
      continue
    fi
    fetch "${OWW_URL}/${f}" "$dest"
    if ! is_tflite "$dest"; then
      echo "  NOT TFLITE   $f  (got: $(head -c 64 "$dest" | tr -cd '[:print:]'))" >&2
      rm -f "$dest"
      exit 1
    fi
    echo "  ok           $f  ($(wc -c <"$dest" | tr -d ' ') bytes)"
  done
fi

if [[ "$PUSH" -eq 0 ]]; then
  echo "Skipping adb push (--no-push)."
  exit 0
fi

command -v adb >/dev/null || { echo "adb not found in PATH" >&2; exit 1; }
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  echo "Pushing to device ${ANDROID_SERIAL}:${REMOTE_DIR}"
else
  echo "Pushing to the default adb device (set ANDROID_SERIAL to choose one):${REMOTE_DIR}"
fi
# adb honours $ANDROID_SERIAL itself.
adb shell mkdir -p "$REMOTE_DIR/micro"
for entry in "${MWW_FILES[@]}"; do
  f="${entry% *}"
  adb push "${MWW_CACHE}/${f}" "${REMOTE_DIR}/micro/${f}" >/dev/null
  echo "  pushed micro/$f"
done
if [[ "$OWW" -eq 1 ]]; then
  for f in "${OWW_FILES[@]}"; do
    adb push "${OWW_CACHE}/${f}" "${REMOTE_DIR}/${f}" >/dev/null
    echo "  pushed $f"
  done
fi
adb shell ls -lR "$REMOTE_DIR"
echo "Done. Restart the voice satellite (or the app) to load the models."
