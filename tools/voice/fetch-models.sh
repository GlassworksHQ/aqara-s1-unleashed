#!/usr/bin/env bash
# Download the openWakeWord TFLite models on the host and push them to the panel.
#
# The models are NOT bundled with the app: openWakeWord's pre-trained models are
# licensed CC BY-NC-SA 4.0 (the code is Apache-2.0). This script fetches them from the
# official openWakeWord v0.5.1 GitHub release, checks that each file really is a TFLite
# flatbuffer (and not an HTML error page), then `adb push`es them into the kiosk's
# app-specific external storage, which needs no root:
#
#   /sdcard/Android/data/sh.edm.s1kiosk/files/models/
#
# Usage:
#   export ANDROID_SERIAL=<panel serial>     # optional if only one device is attached
#   tools/voice/fetch-models.sh              # download + verify + push
#   tools/voice/fetch-models.sh --no-push    # download + verify only
#
# Env:
#   MODELS_CACHE  host download dir (default: ~/.cache/s1kiosk/openwakeword-<tag>)
#   OWW_VERSION   openWakeWord release tag (default: v0.5.1)
set -euo pipefail

OWW_VERSION="${OWW_VERSION:-v0.5.1}"
BASE_URL="https://github.com/dscripka/openWakeWord/releases/download/${OWW_VERSION}"
PKG="sh.edm.s1kiosk"
REMOTE_DIR="/sdcard/Android/data/${PKG}/files/models"
CACHE="${MODELS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/s1kiosk/openwakeword-${OWW_VERSION}}"

FILES=(
  melspectrogram.tflite
  embedding_model.tflite
  hey_jarvis_v0.1.tflite
  alexa_v0.1.tflite
  hey_mycroft_v0.1.tflite
)

PUSH=1
for arg in "$@"; do
  case "$arg" in
    --no-push) PUSH=0 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# A TFLite model is a FlatBuffer whose file identifier "TFL3" sits at byte offset 4.
is_tflite() {
  local f="$1"
  [[ -s "$f" ]] || return 1
  [[ "$(dd if="$f" bs=1 skip=4 count=4 2>/dev/null)" == "TFL3" ]]
}

mkdir -p "$CACHE"
echo "Downloading openWakeWord ${OWW_VERSION} models into ${CACHE}"
for f in "${FILES[@]}"; do
  dest="${CACHE}/${f}"
  if is_tflite "$dest"; then
    echo "  ok (cached)  $f"
    continue
  fi
  tmp="${dest}.part"
  rm -f "$tmp"
  if ! curl -fsSL --retry 3 -o "$tmp" "${BASE_URL}/${f}"; then
    rm -f "$tmp"
    echo "  FAILED       $f  (${BASE_URL}/${f})" >&2
    exit 1
  fi
  if ! is_tflite "$tmp"; then
    echo "  NOT TFLITE   $f  (got: $(head -c 64 "$tmp" | tr -cd '[:print:]'))" >&2
    rm -f "$tmp"
    exit 1
  fi
  mv "$tmp" "$dest"
  echo "  ok           $f  ($(wc -c <"$dest" | tr -d ' ') bytes)"
done

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
adb shell mkdir -p "$REMOTE_DIR"
for f in "${FILES[@]}"; do
  adb push "${CACHE}/${f}" "${REMOTE_DIR}/${f}" >/dev/null
  echo "  pushed $f"
done
adb shell ls -l "$REMOTE_DIR"
echo "Done. Restart the voice satellite (or the app) to load the models."
