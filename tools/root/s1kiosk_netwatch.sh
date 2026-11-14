#!/system/bin/sh
# S1 Kiosk watchdog: the Wi-Fi link and Android's ActivityManager. Runs as root from the Magisk boot
# image (s1kiosk_netwatch.rc). It takes no input; it reads settings, routes, ARP and dumpsys.
#
# Network. Seen on the panel: the Wi-Fi link can wedge while Android still reports "connected": the
# gateway's ARP entry sits in PROBE, nothing gets through, and nothing recovers by itself (2026-11-09;
# cycling Wi-Fi fixed it at once). Every 15 s this pings the default gateway. It counts a failure only
# when the gateway also has no working ARP entry (FAILED/INCOMPLETE/PROBE): a router that just ignores
# ping is not a dead link. After 4 failures in a row (~1 min) it cycles Wi-Fi, at most once per 5 min.
# Status: sys.s1kiosk.net.status
#
# ActivityManager (#248, frames/aqara-s1-plus/research/install-crash-loop.md). Android 10's
# ProcessList.removeLruProcessLocked moves an LRU section start down when it removes the process at
# that start (`<=`; Android 11 uses `<`). On the S1 each kiosk force-stop costs one, and an install does
# two (2026-11-14: 14 -> -1 over 15 force-stops). Below 0, every new process fails to attach and
# ActivityManager starts it again at once (~13 a second) until system_server crashes on the same bug:
# 0.3 s to ~11 min of a crash-looping kiosk. Every 15 s this reads the starts from
#   dumpsys activity processes sh.edm.s1kiosk     (~35 ms)
#   "  Process LRU list (sorted by oom_adj, 27 total, non-act at 3, non-svc at 3):"
# (start = total - non-act, total - non-svc). If one is below 0 on 2 reads in a row, it kills
# system_server: what ends the loop by itself, only sooner. init restarts zygote and the framework;
# init services (this one, the radio helper) keep running. At most once per 10 min.
# Status: sys.s1kiosk.am.status, sys.s1kiosk.am.restarts ("<count> <uptime of the last>s")
# Dry run, by hand only (init passes no environment): S1N_AM_DRY=1 sh s1kiosk_netwatch.sh does all of
# this but the kill, and reports "would restart the framework".
INTERVAL=${S1N_SLEEP:-15}
NEEDED=4
COOLDOWN=300
AM_NEEDED=2
AM_COOLDOWN=600
fails=0
last_kick=0
am_bad=0
am_last=-$AM_COOLDOWN
am_count=0
loops=0
log() { echo "s1kiosk_netwatch: $*" > /dev/kmsg; }

# "<total> <activity start> <service start>", or nothing if dumpsys didn't answer.
lru_starts() {
  timeout 20 dumpsys activity processes sh.edm.s1kiosk 2>/dev/null |
    sed -n 's/.*LRU list (sorted by oom_adj, \([0-9]*\) total, non-act at \(-*[0-9]*\), non-svc at \(-*[0-9]*\)).*/\1 \2 \3/p' |
    head -n 1 | { read -r n a s && echo "$n $((n - a)) $((n - s))"; }
}

am_check() {
  set -- $(lru_starts)
  if [ $# != 3 ]; then am_bad=0; setprop sys.s1kiosk.am.status "no answer from dumpsys"; return; fi
  if [ "$2" -ge 0 ] && [ "$3" -ge 0 ]; then am_bad=0; setprop sys.s1kiosk.am.status "ok $2 $3 of $1"; return; fi
  am_bad=$((am_bad + 1))
  setprop sys.s1kiosk.am.status "broken: LRU starts $2 $3 of $1 ($am_bad/$AM_NEEDED)"
  [ "$am_bad" -ge "$AM_NEEDED" ] || return
  now=$(cut -d. -f1 /proc/uptime)
  if [ $((now - am_last)) -lt "$AM_COOLDOWN" ]; then
    setprop sys.s1kiosk.am.status "broken: LRU starts $2 $3 of $1; restarted at ${am_last}s, in the cooldown"
    return
  fi
  if [ -n "$S1N_AM_DRY" ]; then
    am_last=$now; am_bad=0
    log "LRU start below 0 (starts $2 $3 of $1): would restart the framework (dry run)"
    setprop sys.s1kiosk.am.status "would restart the framework at uptime ${now}s (dry run; LRU starts $2 $3 of $1)"
    return
  fi
  pid=$(pidof system_server)
  case "$pid" in ''|*[!0-9]*) log "LRU start below 0, but no single system_server pid ('$pid')"; return ;; esac
  log "LRU start below 0 (starts $2 $3 of $1) on $am_bad reads: restarting the framework (kill system_server $pid)"
  am_last=$now; am_bad=0; am_count=$((am_count + 1))
  setprop sys.s1kiosk.am.restarts "$am_count ${now}s"
  setprop sys.s1kiosk.am.status "restarted the framework at uptime ${now}s (LRU starts $2 $3 of $1)"
  kill -9 "$pid"
}

while [ -z "$S1N_ONCE" ] || [ "$loops" -lt "$S1N_ONCE" ]; do
  loops=$((loops + 1))
  sleep "$INTERVAL"
  am_check
  if [ "$(settings get global wifi_on 2>/dev/null)" != 1 ]; then fails=0; continue; fi
  gw=$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.* via \([0-9.]*\) .*dev wlan0.*/\1/p')
  if [ -z "$gw" ]; then fails=0; setprop sys.s1kiosk.net.status "no Wi-Fi gateway"; continue; fi
  if ping -c 1 -W 3 "$gw" >/dev/null 2>&1; then
    fails=0; setprop sys.s1kiosk.net.status "ok $gw"; continue
  fi
  case "$(ip neigh show "$gw" dev wlan0 2>/dev/null)" in
    *REACHABLE*|*STALE*|*DELAY*) fails=0; setprop sys.s1kiosk.net.status "ok $gw (no ping reply, ARP fine)"; continue ;;
  esac
  fails=$((fails + 1))
  setprop sys.s1kiosk.net.status "gateway $gw not answering ($fails/$NEEDED)"
  now=$(cut -d. -f1 /proc/uptime)
  if [ "$fails" -ge "$NEEDED" ] && [ $((now - last_kick)) -ge "$COOLDOWN" ]; then
    log "gateway $gw unreachable (ping and ARP) for ~$((fails * INTERVAL)) s while Wi-Fi is up: cycling Wi-Fi"
    svc wifi disable; sleep 3; svc wifi enable
    last_kick=$now; fails=0
    setprop sys.s1kiosk.net.status "cycled Wi-Fi at uptime ${now}s"
  fi
done
