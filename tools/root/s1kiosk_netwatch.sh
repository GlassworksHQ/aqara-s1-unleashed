#!/system/bin/sh
# S1 Kiosk network watchdog. Runs as root from the Magisk boot image (s1kiosk_netwatch.rc).
#
# Seen on the panel: the Wi-Fi link can wedge while Android still reports "connected": the gateway's
# ARP entry sits in PROBE, nothing gets through, and nothing recovers by itself (2026-11-09; cycling
# Wi-Fi fixed it at once). Every 15 s this pings the default gateway. It counts a failure only when
# the gateway also has no working ARP entry (FAILED/INCOMPLETE/PROBE): a router that just ignores
# ping is not a dead link. After 4 failures in a row (~1 min) it cycles Wi-Fi, at most once per 5 min.
# Status: sys.s1kiosk.net.status
INTERVAL=15
NEEDED=4
COOLDOWN=300
fails=0
last_kick=0
log() { echo "s1kiosk_netwatch: $*" > /dev/kmsg; }

while true; do
  sleep "$INTERVAL"
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
