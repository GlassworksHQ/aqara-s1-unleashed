#!/system/bin/sh
# s1kiosk_radio: root helper for the S1 Kiosk radio modes (docs/radio.md), contract v1.
#
# Runs as an init service (s1kiosk_radio.rc: root, u:r:su:s0, started at boot, never exits).
# Reads persist.s1kiosk.radio_mode, brings Aqara's radio services to the state that mode needs,
# starts/supervises our own processes (EZSP TCP bridge, otbr-agent) and reports in
# sys.s1kiosk.radio.status as "<applying|ok|error> <mode>[: detail]".
# The app changes mode with: setprop persist.s1kiosk.radio_mode <id>; setprop ctl.restart s1kiosk_radio
# (init kills the whole process group on restart, so our children go with it.)
#
# Mode ids: off (default) | zigbee_tcp | zigbee_router | thread | thread_router
#   experimental, not offered by the app: zigbee_tcp_thread (Zigbee + Thread on one multi-PAN radio)
# There is no stock mode: Aqara's services are init services, so removing this helper (or the app,
# tools/install.sh --undo) and rebooting brings the stock stack back.
#
# Optional root-owned overrides in $DIR/radio.conf (sourced):
#   ZIGBEE_PORT=6638  ZIGBEE_RANGE=192.168.1.0/24  BACKBONE=wlan0  OTBR_ARGS="..."  RADIO_URL=...
# thread_router joins with the active dataset (hex TLVs, as exported from HA) in $DIR/thread-dataset
# (root 0600; a secret: never log it). Without that file the mode reports an error and joins nothing.
# otbr-agent/ot-ctl come from tools/radio/build-otbr.sh; build them with OTBR_RUNTIME_DIR=$DIR/run
# (socket dir, compiled in). The proof build has border routing/REST/mDNS off: set BACKBONE only
# with a border-routing build.

DIR=${S1R_DIR:-/data/s1kiosk/radio}     # otbr-agent, ot-ctl, optional zigbee-router, radio.conf
ZIGBEE_PORT=6638
ZIGBEE_RANGE=""                          # e.g. 192.168.1.0/24 (socat range=); empty = any
BACKBONE=""                              # e.g. wlan0 (-B); needs a border-routing otbr build
RADIO_URL='spinel+cpc://cpcd_0?iid=2&iid-list=0'  # zigbeed holds iid=1
OTBR_ARGS=""
EZSP_PTY=/dev/ttyZigbeeNCP               # host side of Aqara's socat pty pair (zigbeed is on /tmp/ttyZigbeeNCP)
CPC_SOCK=/dev/shm/cpcd/cpcd_0/ctrl.cpcd.sock
[ -r "$DIR/radio.conf" ] && . "$DIR/radio.conf"

MODE=${S1R_MODE:-$(getprop persist.s1kiosk.radio_mode)}  # S1R_MODE: manual test without touching the persist prop
[ -n "$MODE" ] || MODE=off

say() { log -t s1kiosk_radio "$*" 2>/dev/null; }
status() { # state [detail]
  if [ -n "$2" ]; then setprop sys.s1kiosk.radio.status "$1 $MODE: $2"; else setprop sys.s1kiosk.radio.status "$1 $MODE"; fi
  say "$1 $MODE $2"
}
setprop sys.s1kiosk.radio.version 1

# --- Aqara's services --------------------------------------------------------------------------
# Consumers first (ha_matter = Aqara's Matter bridge for its Zigbee devices). Aqara's own consumers
# (Z3Gateway, zigbee_agent, ha_matter) never run in any of our modes.
STOP_ORDER="ha_matter zigbee_agent zigbee_host zigbee_zigbeed zigbee_socat zigbee_cpcd"
START_ORDER="zigbee_cpcd zigbee_socat zigbee_zigbeed"

case "$MODE" in
  off)                                        WANT="" ;;
  zigbee_tcp|zigbee_router|zigbee_tcp_thread) WANT="zigbee_cpcd zigbee_socat zigbee_zigbeed" ;;
  thread|thread_router)                       WANT="zigbee_cpcd" ;;
  *) status error "unknown mode"; exec sleep 2147483647 ;;
esac

wanted() { case " $WANT " in *" $1 "*) return 0 ;; esac; return 1; }

svc_state() { getprop init.svc.$1; }

svc() { # name running|stopped -> waits up to 5 s
  s=$(svc_state $1)
  if [ "$2" = running ]; then
    [ "$s" = running ] && return 0
    setprop ctl.start $1
  else
    { [ "$s" = stopped ] || [ -z "$s" ]; } && return 0
    setprop ctl.stop $1
  fi
  i=0
  while [ $i -lt 50 ]; do
    s=$(svc_state $1)
    if [ "$2" = running ]; then
      [ "$s" = running ] && return 0
    else
      [ "$s" != running ] && [ "$s" != restarting ] && return 0
    fi
    sleep 0.1; i=$((i+1))
  done
  return 1
}

# Brings Aqara's stack to $WANT. Consumers are stopped first: the socat/zigbeed/Z3Gateway wrappers
# run `setprop sys.gateway.config 11` (= restart the whole stack) when their binary exits by itself.
enforce() {
  bad=""
  for s in $STOP_ORDER; do wanted $s || svc $s stopped || bad="$bad $s"; done
  for s in $START_ORDER; do if wanted $s; then svc $s running || bad="$bad $s"; fi; done
  [ -z "$bad" ]
}

wait_for() { # path seconds
  i=0; while [ ! -e "$1" ] && [ $i -lt $(($2*10)) ]; do sleep 0.1; i=$((i+1)); done; [ -e "$1" ]
}

# --- our processes ---------------------------------------------------------------------------
BRIDGE_PID=""; OTBR_PID=""; ROUTER_PID=""; FAILS=0

start_bridge() {
  wait_for $EZSP_PTY 30 || { status error "$EZSP_PTY missing (zigbee_socat not up)"; return 1; }
  r=""; [ -n "$ZIGBEE_RANGE" ] && r=",range=$ZIGBEE_RANGE"
  # fork + max-children=1: one EZSP host at a time; each connection reopens the pty, so a new
  # client works even after Aqara's watchdog recreated the pty pair.
  /vendor/bin/socat TCP-LISTEN:$ZIGBEE_PORT,reuseaddr,fork,max-children=1$r FILE:$EZSP_PTY,raw,echo=0 &
  BRIDGE_PID=$!
}

start_otbr() {
  [ -x "$DIR/otbr-agent" ] || { status error "$DIR/otbr-agent not installed"; return 1; }
  wait_for $CPC_SOCK 30 || { status error "cpcd socket missing"; return 1; }
  # cpcd needs ~4 s to reach the secondary after a (re)start, and endpoint 12 closes asynchronously:
  # an early start fails ("CPC init failed -2" / "CPC endpoint open failed"); the loop retries.
  sleep 5
  mkdir -p "$DIR/run" "$DIR/thread"  # TUN is /dev/tun (compiled in by build-otbr.sh)
  b=""; [ -n "$BACKBONE" ] && b="-B $BACKBONE"
  # shellcheck disable=SC2086
  "$DIR/otbr-agent" -s -I wpan0 $b --data-path "$DIR/thread" $OTBR_ARGS "$RADIO_URL" &
  OTBR_PID=$!
}

# thread_router: same agent without backbone (the proof build: no border routing/REST/mDNS), then
# join the user's existing network with the dataset from HA. Never forms a network.
start_thread_router() {
  [ -s "$DIR/thread-dataset" ] || { status error "no Thread dataset ($DIR/thread-dataset)"; return 1; }
  BACKBONE=""
  start_otbr || return 1
  i=0; while ! "$DIR/ot-ctl" state >/dev/null 2>&1 && [ $i -lt 30 ]; do sleep 1; i=$((i+1)); done
  "$DIR/ot-ctl" dataset set active "$(cat "$DIR/thread-dataset")" >/dev/null 2>&1 &&
    "$DIR/ot-ctl" ifconfig up >/dev/null 2>&1 &&
    "$DIR/ot-ctl" thread start >/dev/null 2>&1 ||
    { status error "ot-ctl join steps failed"; kill "$OTBR_PID" 2>/dev/null; return 1; }
}

start_router() {
  # Router mode needs an EZSP host that joins as EMBER_ROUTER (docs/radio.md "Zigbee router").
  [ -x "$DIR/zigbee-router" ] || { status error "zigbee router host not installed ($DIR/zigbee-router)"; return 1; }
  wait_for $EZSP_PTY 30 || { status error "$EZSP_PTY missing"; return 1; }
  "$DIR/zigbee-router" "$EZSP_PTY" &
  ROUTER_PID=$!
}

alive() { [ -n "$1" ] && kill -0 "$1" 2>/dev/null; }

children_ok() {
  case "$MODE" in
    zigbee_tcp)        alive "$BRIDGE_PID" || start_bridge || return 1 ;;
    zigbee_router)     alive "$ROUTER_PID" || start_router || return 1 ;;
    thread)            alive "$OTBR_PID"   || start_otbr   || return 1 ;;
    thread_router)     alive "$OTBR_PID"   || start_thread_router || return 1 ;;
    zigbee_tcp_thread) alive "$BRIDGE_PID" || start_bridge || return 1
                       alive "$OTBR_PID"   || start_otbr   || return 1 ;;
  esac
  return 0
}

detail() {
  case "$MODE" in
    off) echo "Aqara radio services stopped, radio idle" ;;
    zigbee_tcp) echo "EZSP on tcp:$ZIGBEE_PORT" ;;
    zigbee_router) echo "router host running" ;;
    thread) echo "otbr-agent on wpan0${BACKBONE:+ (backbone $BACKBONE)}" ;;
    thread_router) echo "Thread router on wpan0 ($("$DIR/ot-ctl" state 2>/dev/null | head -1))" ;;
    zigbee_tcp_thread) echo "EZSP on tcp:$ZIGBEE_PORT; otbr-agent on wpan0" ;;
  esac
}

# --- main ------------------------------------------------------------------------------------
status applying

last=""
while true; do
  # Re-enforce every 3 s, in every mode incl. off: Aqara's boot sequence (zigbee_cpcd_factory ->
  # sys.gateway.config=10, sys.svc.gateway=5 -> ha_matter) and its watchdog (sys.gateway.config=11)
  # start the stock stack again; Z3Gateway's wrapper sleeps 6 s before opening the pty, so we stop it first.
  if enforce; then
    if children_ok; then
      FAILS=0
      [ "$last" = ok ] || { status ok "$(detail)"; last=ok; }
    else
      FAILS=$((FAILS+1)); last=error # start_* set the status
    fi
  else
    FAILS=$((FAILS+1)); last=error
    status error "services not in wanted state:$bad"
  fi
  if [ $FAILS -gt 20 ]; then status error "giving up after repeated failures"; exec sleep 2147483647; fi
  sleep 3
done
