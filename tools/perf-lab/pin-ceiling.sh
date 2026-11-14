#!/bin/bash
# Hold the S1's CPU ceiling at one frequency for a while, as root (MIT). **It writes the device**:
# /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq once a second (RAM only: sysfs, gone at
# reboot), plus a log and a stop file in /data/local/tmp. The power HAL rewrites the ceiling on
# its scene changes (every app launch), hence the loop. The loop runs on the device by itself
# (nohup) and ends, writing back the value it read at its start, when the CPU zone passes MAX_C,
# after MINUTES, or when `stop` creates the stop file. Only the panel's operator runs this, and on
# the S1 only with the maintainer's OK (#244 plan C, frames/aqara-s1-plus/research/view-open.md).
# --su: a root shell that reads its script from stdin; `su 0` (Magisk) by default. Check:
# `adb shell 'echo id | su 0'` prints uid=0(root).
#
#   tools/perf-lab/pin-ceiling.sh SERIAL start KHZ [MINUTES=45] [MAX_C=65] [--su 'su 0']
#   tools/perf-lab/pin-ceiling.sh SERIAL stop [--su 'su 0']     # stop file, then prints the log
#   tools/perf-lab/pin-ceiling.sh SERIAL status [--su 'su 0']   # the log, the ceiling, the loop's count
set -u
SERIAL=$1 CMD=$2; shift 2
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
SU="su 0" KHZ="" MINUTES=45 MAX_C=65 POS=0
while [ $# -gt 0 ]; do
  case $1 in
    --su) SU=$2; shift 2 ;;
    *) POS=$((POS + 1)); case $POS in 1) KHZ=$1 ;; 2) MINUTES=$1 ;; 3) MAX_C=$1 ;; esac; shift ;;
  esac
done
D=/data/local/tmp
asroot() { "$ADB" -s "$SERIAL" shell "echo $(printf '%s\n' "$1" | base64 | tr -d '\n') | base64 -d | $SU"; }
case $CMD in
  start)
    case $KHZ in 1200000|1320000|1416000|1512000|1608000) ;; *) echo "KHZ must be one of the SoC's steps from 1200000 up" >&2; exit 2 ;; esac
    read -r -d '' LOOP <<SH
M=/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq
read -r was < \$M
end=\$((\$(date +%s) + $MINUTES * 60)); why=time
echo "start \$(date +%s) pin=$KHZ was=\$was max_c=$MAX_C" > $D/gw244-pin.log
while [ \$(date +%s) -lt \$end ]; do
  [ -e $D/gw244-pin.stop ] && { why=stopfile; break; }
  read -r t < /sys/class/thermal/thermal_zone0/temp
  [ \$t -gt $((MAX_C * 1000)) ] && { why=hot:\$t; break; }
  echo $KHZ > \$M
  sleep 1
done
echo \$was > \$M
read -r now < \$M
echo "end \$(date +%s) \$why restored=\$now" >> $D/gw244-pin.log
SH
    asroot "rm -f $D/gw244-pin.stop; echo $(printf '%s\n' "$LOOP" | base64 | tr -d '\n') | base64 -d > $D/gw244-pin.sh; nohup sh $D/gw244-pin.sh > /dev/null 2>&1 &"
    sleep 2
    asroot "cat $D/gw244-pin.log; cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
    ;;
  stop)
    asroot "touch $D/gw244-pin.stop"
    sleep 3
    asroot "cat $D/gw244-pin.log; echo ceiling \$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq); echo loops \$(ps -A -o ARGS | grep -c '[g]w244-pin.sh'); rm -f $D/gw244-pin.stop $D/gw244-pin.sh"
    ;;
  status)
    asroot "cat $D/gw244-pin.log; echo ceiling \$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq); echo loops \$(ps -A -o ARGS | grep -c '[g]w244-pin.sh')"
    ;;
  *) echo "start, stop or status" >&2; exit 2 ;;
esac
