#!/bin/bash
# False-wake test (#175): about 30 min of speech with no wake word (some sentences hold near-misses
# like "Jarvis Street" and "Okay, so"), played from the Mac's speaker with macOS text-to-speech while
# the panel listens; counts the panel's detections in logcat.
# Touches the panel through adb only: it clears the panel's logcat buffer (`logcat -c`) and reads it.
# Nothing is installed or changed. Each detection starts an Assist run in Home Assistant, which then
# listens for about 15 s, so it isn't silent on a shared panel. Needs macOS (`say` and its voices).
# Pick the panel with ANDROID_SERIAL; ADB overrides the adb path. Results of the #175 runs are in
# docs/research/microwakeword.md.
# Usage: tools/voice/false-wake.sh [minutes]   prints every detection and the total.
A=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
MIN="${1:-30}"
VOICES=(Samantha Alex Daniel Karen Moira Tessa Fred Victoria Rishi Fiona)
TEXT=(
"The weather this afternoon is mild, with light winds from the west and a chance of showers later on."
"Remember to take the bins out on Thursday morning, and the recycling goes in the blue one."
"In the second half the home team pressed hard, but the keeper made three good saves in a row."
"Add olive oil, garlic and a pinch of salt, then let it simmer for about twenty minutes."
"The train to the city leaves at eight fifteen from platform two, calling at every station."
"She said the meeting moved to Tuesday, so we have a little more time to finish the slides."
"Turn left at the roundabout, keep going past the school, and the shop is on your right."
"Scientists say the comet will be visible just after sunset for most of next week."
"Can you pass me the remote? I want to see the end of the documentary about whales."
"The package was delivered to the front porch at half past three this afternoon."
"Jarvis Street is closed for repairs, so the bus will take the long way around today."
"Alex and Nabeel are coming over for dinner, and they are bringing dessert."
"My craft project this weekend is a small wooden bird feeder for the garden."
"Okay, so the plan is to leave early and stop for coffee on the way."
)
$A logcat -c
end=$(( $(date +%s) + MIN*60 )); i=0
while [ "$(date +%s)" -lt "$end" ]; do
  v=${VOICES[$(( i % ${#VOICES[@]} ))]}; t=${TEXT[$(( i % ${#TEXT[@]} ))]}
  say -v "$v" "$t" 2>/dev/null || say "$t"
  sleep 1; i=$((i+1))
done
sleep 5
n=$($A logcat -d | grep -c "detected (score")
$A logcat -d | grep "detected (score" | cut -c7-140
echo "RESULT false_wakes=$n in ${MIN} min, $i sentences"
