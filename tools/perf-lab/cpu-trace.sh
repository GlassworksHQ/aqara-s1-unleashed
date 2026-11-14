#!/bin/bash
# Who uses the S1's four cores while a view opens, and at which clock and temperature (MIT).
# Read-only on the device: one adb shell runs a loop that reads /proc/stat, the CPU clock, the
# thermal zones, the cpufreq cooling state and the kiosk's processes (and their main and voice
# threads) about every 0.5 s with shell builtins, and writes nothing there. As root (--su) it also
# reads the cpufreq ceiling and governor, which `shell` can't. It costs the device one `sleep` per
# sample and one `ps` and `getprop` every 20, so a run traced with it is a diagnostic run, not a
# graded one. Stop it with Ctrl-C or by killing its PID; the device loop also ends by itself after
# --seconds (default 7200). Check afterwards: `adb shell ps -A | grep -c "sleep 0.25"` is 0.
# cpu-align.py lines it up with a Clarity run (#244).
#
#   ADB=... tools/perf-lab/cpu-trace.sh SERIAL OUT.trace [--seconds 7200] [--su 'su 0']
#
# --su: the command that runs a root shell reading its script from stdin. On the dev boot image
# (Magisk's su) that is `su 0` with nothing after it: Magisk hands any further argument to the shell
# as a script file. Check first: `adb shell 'echo id | su 0'` prints uid=0(root).
set -u
SERIAL=$1 OUT=$2; shift 2
SU="" SECONDS_MAX=7200
while [ $# -gt 0 ]; do
  case $1 in
    --su) SU=$2; shift 2 ;;
    --seconds) SECONDS_MAX=$2; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
read -r -d '' LOOP <<'SH'
CF=/sys/devices/system/cpu/cpu0/cpufreq
TZ=/sys/class/thermal
read -r up rest < /proc/uptime
end=$((${up%.*} + SECONDS_MAX))
i=0
while [ ${up%.*} -lt $end ]; do
  if [ $((i % 20)) -eq 0 ]; then
    echo "E $(date +%s.%N) $(cat /proc/uptime)"
    echo "G $(getprop sys.cpu_temp)"
    L=$(ps -A -o PID,NAME | grep -E "sh\.edm\.s1kiosk|otbr-agent|zigbeed")
    T=""
    m=$(echo "$L" | while read pid name; do [ "$name" = sh.edm.s1kiosk ] && echo $pid; done)
    if [ -n "$m" ]; then
      for t in /proc/$m/task/*; do
        read -r c < $t/comm 2>/dev/null
        case "$c" in voice-*) T="$T $m:${t##*/}:$c" ;; esac
      done
    fi
  fi
  i=$((i + 1))
  read -r up rest < /proc/uptime
  echo "U $up"
  n=0
  while read -r l && [ $n -lt 5 ]; do echo "C $l"; n=$((n + 1)); done < /proc/stat
  read -r f < $CF/scaling_cur_freq
  echo "F $f"
  read -r z0 < $TZ/thermal_zone0/temp; read -r z1 < $TZ/thermal_zone1/temp; read -r z2 < $TZ/thermal_zone2/temp
  read -r cs < $TZ/cooling_device0/cur_state
  echo "T $z0 $z1 $z2 $cs"
  if [ -r $CF/scaling_max_freq ]; then
    read -r mx < $CF/scaling_max_freq
    read -r gv < $CF/scaling_governor
    echo "X $mx $gv"
  fi
  echo "$L" | while read pid name; do
    read -r s < /proc/$pid/stat 2>/dev/null && echo "P $pid $name|$s"
    read -r s < /proc/$pid/task/$pid/stat 2>/dev/null && echo "M $pid $name|$s"
  done
  for x in $T; do
    p=${x%%:*}; r=${x#*:}; t=${r%%:*}; c=${r#*:}
    read -r s < /proc/$p/task/$t/stat 2>/dev/null && echo "H $t $c|$s"
  done
  sleep 0.25
done
SH
B64=$(printf 'SECONDS_MAX=%s\n%s\n' "$SECONDS_MAX" "$LOOP" | base64 | tr -d '\n')
exec "$ADB" -s "$SERIAL" shell "echo $B64 | base64 -d | ${SU:-sh}" > "$OUT"
