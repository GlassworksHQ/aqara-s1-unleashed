#!/bin/bash
# Wake word trial with macOS text-to-speech (#175): says the phrase from the Mac's speaker with 10
# voices in turn, 22 s apart, and counts the panel's detections in logcat.
# Touches the panel through adb only: it clears the panel's logcat buffer (`logcat -c`) and reads it.
# Nothing is installed or changed. Each detection starts an Assist run in Home Assistant, which then
# listens for about 15 s, so it isn't silent on a shared panel. Needs macOS (`say` and its voices).
# Pick the panel with ANDROID_SERIAL; ADB overrides the adb path. Results of the #175 runs are in
# docs/research/microwakeword.md.
# Usage: tools/voice/tts-trial.sh "<phrase>" [count]   prints one line per try, then the hit count.
A=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
PHRASE="$1"; N="${2:-10}"
VOICES=(Samantha Alex Daniel Karen Moira Tessa Fred Victoria Rishi Fiona)
hits=0
for i in $(seq 1 "$N"); do
  v=${VOICES[$(( (i-1) % ${#VOICES[@]} ))]}
  $A logcat -c
  say -v "$v" "$PHRASE" 2>/dev/null || say "$PHRASE"
  sleep 3
  line=$($A logcat -d | grep -m1 "detected (score" | sed -E 's/.*detected \(score ([0-9.]+)\).*/\1/')
  if [ -n "$line" ]; then hits=$((hits+1)); echo "try $i voice=$v HIT score=$line"; else echo "try $i voice=$v miss"; fi
  sleep 19   # a run lasts ~15 s (STT gives up on silence); wait it out before the next try
done
echo "RESULT phrase='$PHRASE' hits=$hits/$N"
