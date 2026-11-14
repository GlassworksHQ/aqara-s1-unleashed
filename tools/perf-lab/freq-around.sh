#!/bin/bash
# The CPU clock around one action on the device, at 20 Hz (MIT). The sampling is read-only (sysfs
# and /proc/uptime, shell builtins plus one `sleep` per sample). The ACTION you pass runs on the
# device as given (an `input tap`, an `am start`), so only the panel's operator runs this. Used for
# #244: does a touch raise the power HAL's CPU ceiling (a touch boost) where Clarity's
# intent-driven view opens don't? (frames/aqara-s1-plus/research/view-open.md)
#
#   tools/perf-lab/freq-around.sh SERIAL LABEL 'ACTION' [BEFORE_S=2] [AFTER_S=4]
#
# Prints "LABEL <seconds from the action> <kHz>" per sample, then a count per frequency before and
# after. ACTION "" samples with nothing done (the control).
set -u
SERIAL=$1 LABEL=$2 ACTION=$3 BEFORE=${4:-2} AFTER=${5:-4}
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
"$ADB" -s "$SERIAL" shell "
F=/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq
s() { n=\$1; while [ \$n -gt 0 ]; do read -r u x < /proc/uptime; read -r f < \$F; echo \"S \$u \$f\"; sleep 0.05; n=\$((n - 1)); done; }
s $((BEFORE * 20))
read -r u x < /proc/uptime; echo \"A \$u\"
$ACTION
s $((AFTER * 20))
" | awk -v L="$LABEL" '
  $1 == "A" { a = $2; next }
  $1 == "S" { t[NR] = $2; f[NR] = $3; n = NR }
  END {
    for (i = 1; i <= n; i++) if (i in t) {
      d = (a == "" ? 0 : t[i] - a); printf "%s %+.2f %s\n", L, d, f[i]
      if (a != "" && t[i] < a) before[f[i]]++; else after[f[i]]++
    }
    printf "%s before:", L; for (k in before) printf " %s x%d", k, before[k]; printf "\n"
    printf "%s after:", L;  for (k in after)  printf " %s x%d", k, after[k];  printf "\n"
  }'
