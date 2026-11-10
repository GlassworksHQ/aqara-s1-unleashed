#!/system/bin/sh
# s1kiosk_radio: root helper for the S1 Kiosk radio modes (docs/radio.md), contract v2.
#
# Runs as an init service (s1kiosk_radio.rc: root, u:r:su:s0, started at boot, never exits).
# Reads persist.s1kiosk.radio_mode, brings Aqara's radio services to the state that mode needs,
# starts/supervises our own processes (EZSP TCP bridges, otbr-agent) and reports in
# sys.s1kiosk.radio.status as "<applying|ok|error> <mode>[: detail]".
# The app changes mode with: setprop persist.s1kiosk.radio_mode <id>; setprop ctl.restart s1kiosk_radio
# (init kills the whole process group on restart, so our children go with it.)
#
# Mode ids: off (default) | zigbee_tcp | zigbee_router | thread | thread_router
#   experimental, not offered by the app: zigbee_tcp_thread (Zigbee + Thread on one multi-PAN radio)
# There is no stock mode: Aqara's services are init services, so removing this helper (or the app,
# tools/install.sh --undo) and rebooting brings the stock stack back.
#
#   zigbee_tcp     zigbeed's EZSP on tcp:$ZIGBEE_PORT for ZHA (bellows, socket://<panel>:6638)
#   zigbee_router  zigbeed's EZSP on 127.0.0.1:$ROUTER_PORT only; the app's router host
#                  (radio/ZigbeeRouterHost.kt) drives zigbeed as an EMBER_ROUTER. zigbeed's NVM is
#                  copied to $DIR/zigbee-nvm/ first, because joining replaces a coordinator network.
#   thread         otbr-agent on spinel IID 2. With a border-routing build (tools/radio/build-otbr.sh
#                  PROFILE=br): backbone $BACKBONE (wlan0), REST API on $REST_LISTEN:$REST_PORT for
#                  Home Assistant's OTBR integration, OpenThread mDNS/SRP, IPv6 forwarding + the
#                  policy-routing rules Android needs. With the minimal proof build: agent only.
#   thread_router  otbr-agent without a backbone (no border routing), joins with the active dataset
#                  in $DIR/thread-dataset. The app hands a new dataset over in $APP_DATASET (its own
#                  private files dir); the helper moves it into $DIR (root 0600) and deletes the
#                  app's copy. Without a dataset the stack runs and waits ("ready to join").
#
# Optional root-owned overrides in $DIR/radio.conf (sourced):
#   ZIGBEE_PORT=6638  ZIGBEE_RANGE=lan|<cidr>  ROUTER_PORT=6639  BACKBONE=wlan0
#   REST_LISTEN=0.0.0.0  REST_PORT=8081  OTBR_ARGS="..."  RADIO_URL=...
# otbr-agent/ot-ctl come from tools/radio/build-otbr.sh; build them with OTBR_RUNTIME_DIR=$DIR/run
# (socket dir, compiled in).

DIR=${S1R_DIR:-/data/s1kiosk/radio}     # otbr-agent, ot-ctl, radio.conf, thread-dataset, zigbee-nvm/
ZIGBEE_PORT=6638
# Who may connect to the coordinator bridge (socat range=). Empty = anyone (default: Home Assistant
# is often on another subnet/VLAN than the panel). "lan" = the panel's current wlan0 subnet, followed
# when DHCP changes it. Or a fixed CIDR such as 10.0.0.0/8.
ZIGBEE_RANGE=""
ROUTER_PORT=6639                         # zigbee_router: localhost-only bridge for the app's host
BACKBONE=""                              # thread: default wlan0 with a border-routing build
REST_LISTEN=0.0.0.0                      # thread (BR build): REST API for HA; 127.0.0.1 = local only
REST_PORT=8081
RADIO_URL='spinel+cpc://cpcd_0?iid=2&iid-list=0'  # zigbeed holds iid=1
OTBR_ARGS=""
APP_DATASET=/data/data/sh.edm.s1kiosk/files/radio/thread-dataset  # app -> helper hand-off (thread_router)
EZSP_PTY=/dev/ttyZigbeeNCP               # host side of Aqara's socat pty pair (zigbeed is on /tmp/ttyZigbeeNCP)
ZIGBEED_PTY=/tmp/ttyZigbeeNCP
ZIGBEED_NVM=/data/zigbee_host/zigbeed_host_token.nvm
CPC_SOCK=/dev/shm/cpcd/cpcd_0/ctrl.cpcd.sock
RULE_PREF_IN=14500                       # thread BR: "iif <backbone> lookup <wpan0's table>" (LAN -> Thread)
RULE_PREF_OUT=14501                      # thread BR: "iif wpan0 lookup <backbone>" (Thread -> LAN)
BUNDLE=${0%/*}                           # binaries shipped next to this script (boot image: ${MAGISKTMP})
# A newer helper in $DIR (root-owned, root-only dir) replaces the one baked into the boot image, so
# fixes and new modes ship as a file instead of a new boot image. S1R_NO_OVERRIDE stops the hand-off loop.
if [ -z "$S1R_NO_OVERRIDE" ] && [ "$0" != "$DIR/s1kiosk_radio.sh" ] && [ -f "$DIR/s1kiosk_radio.sh" ] &&
   [ "$(stat -c %u "$DIR/s1kiosk_radio.sh")" = 0 ] && [ "$(stat -c %u "$DIR")" = 0 ]; then
  S1R_NO_OVERRIDE=1 S1R_BUNDLE="$BUNDLE" exec /system/bin/sh "$DIR/s1kiosk_radio.sh" "$@"
fi
BUNDLE=${S1R_BUNDLE:-$BUNDLE}            # an override still uses the boot image's bundled binaries
# Root-only state dir; created on first run so a freshly flashed boot image needs no other install step.
[ -d "$DIR" ] || { mkdir -p "$DIR" && chown root:root "$DIR" && chmod 700 "$DIR"; }
# A binary in $DIR (user-installed) wins over the bundled copy.
bin() { if [ -x "$DIR/$1" ]; then echo "$DIR/$1"; else echo "$BUNDLE/$1"; fi; }
[ -r "$DIR/radio.conf" ] && . "$DIR/radio.conf"

MODE=${S1R_MODE:-$(getprop persist.s1kiosk.radio_mode)}  # S1R_MODE: manual test without touching the persist prop
[ -n "$MODE" ] || MODE=off

say() { log -t s1kiosk_radio "$*" 2>/dev/null; }
SHOWN=""
status() { # state [detail]; only writes when it changed
  if [ -n "$2" ]; then st_="$1 $MODE: $2"; else st_="$1 $MODE"; fi
  [ "$st_" = "$SHOWN" ] && return 0
  SHOWN=$st_
  setprop sys.s1kiosk.radio.status "$st_"
  say "$st_"
}
setprop sys.s1kiosk.radio.version 2

# What the installed otbr-agent can do (app: which Thread modes are real): br | agent | none
otbr_kind() {
  b=$(bin otbr-agent)
  if [ ! -x "$b" ]; then echo none
  elif grep -q -F /node/ba-id "$b" 2>/dev/null; then echo br
  else echo agent; fi
}
OTBR_KIND=$(otbr_kind)
setprop sys.s1kiosk.radio.otbr "$OTBR_KIND"
[ "$OTBR_KIND" = br ] && [ -z "$BACKBONE" ] && BACKBONE=wlan0

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

# --- network helpers ---------------------------------------------------------------------------
# The panel's wlan0 IPv4 subnet as a.b.c.d/bits (network address), or nothing when offline.
lan_cidr() {
  a=$(ip -4 -o addr show dev wlan0 2>/dev/null); a=${a#*inet }; a=${a%% *}
  case "$a" in */*) ;; *) return 1 ;; esac
  ip4=${a%/*}; bits=${a#*/}
  o=$IFS; IFS=.; set -- $ip4; IFS=$o
  [ $# -eq 4 ] || return 1
  n=$(( ($1<<24) | ($2<<16) | ($3<<8) | $4 ))
  if [ "$bits" -eq 0 ]; then m=0; else m=$(( (0xffffffff << (32-bits)) & 0xffffffff )); fi
  n=$(( n & m ))
  echo "$(( (n>>24)&255 )).$(( (n>>16)&255 )).$(( (n>>8)&255 )).$(( n&255 ))/$bits"
}

# socat range for the coordinator bridge: "" = anyone, "lan" = current wlan0 subnet, else as given.
bridge_range() {
  case "$ZIGBEE_RANGE" in
    "") echo "" ;;
    lan) lan_cidr || echo "127.0.0.1/32" ;;  # offline: nobody but us until the LAN is back
    *) echo "$ZIGBEE_RANGE" ;;
  esac
}

# Thread border routing on Android: forwarding on, and two policy rules. Android's kernel puts each
# interface's routes in its own table (accept_ra_rt_table=-1000: ifindex + 1000), and netd's rules
# only consult those tables for locally generated traffic, so packets arriving on wlan0 for Thread
# (and on wpan0 for the LAN) would hit "unreachable". wpan0 is recreated with a new ifindex whenever
# the agent restarts, so the inbound rule follows its current table. Removed in every other mode.
FWD_SAVE=$DIR/forwarding.orig        # forwarding before thread mode turned it on
BR_TABLE=""
br_net_on() {
  [ -f "$FWD_SAVE" ] || cat /proc/sys/net/ipv6/conf/all/forwarding > "$FWD_SAVE" 2>/dev/null
  echo 1 > /proc/sys/net/ipv6/conf/all/forwarding
  # forwarding=1 disables RA processing unless accept_ra=2 (Android sets 2 on wlan0; keep it so)
  echo 2 > /proc/sys/net/ipv6/conf/$BACKBONE/accept_ra 2>/dev/null
  t=$(ip -6 route show table all dev wpan0 2>/dev/null | sed -n 's/.* table \([0-9A-Za-z_]*\) .*/\1/p' | head -1)
  if [ -n "$t" ] && { [ "$t" != "$BR_TABLE" ] || ! ip -6 rule show | grep -q "^$RULE_PREF_IN:"; }; then
    while ip -6 rule del pref $RULE_PREF_IN 2>/dev/null; do :; done
    ip -6 rule add pref $RULE_PREF_IN iif "$BACKBONE" lookup "$t" && BR_TABLE=$t
  fi
  ip -6 rule show | grep -q "^$RULE_PREF_OUT:" || ip -6 rule add pref $RULE_PREF_OUT iif wpan0 lookup "$BACKBONE"
}
br_net_off() {
  while ip -6 rule del pref $RULE_PREF_IN 2>/dev/null; do :; done
  while ip -6 rule del pref $RULE_PREF_OUT 2>/dev/null; do :; done
  if [ -f "$FWD_SAVE" ]; then cat "$FWD_SAVE" > /proc/sys/net/ipv6/conf/all/forwarding; rm -f "$FWD_SAVE"; fi
  BR_TABLE=""
}

# --- our processes ---------------------------------------------------------------------------
BRIDGE_PID=""; BRIDGE_RANGE=""; OTBR_PID=""; JOINED=""; FAILS=0

# zigbeed is up and holds its end of the pty pair (it opens it a few seconds after start).
zigbeed_ready() {
  p=$(pidof zigbeed) || return 1
  t=$(readlink $ZIGBEED_PTY) || return 1
  ls -l /proc/${p%% *}/fd 2>/dev/null | grep -q -- "-> $t\$"
}

# One EZSP host at a time (max-children=1); each connection reopens the pty, so a new client works
# even after Aqara's watchdog recreated the pty pair. TCP keepalive frees the slot within ~1 min when
# a client vanished without closing (HA host crash, Wi-Fi drop).
start_bridge() { # listen-address-option port range
  wait_for $EZSP_PTY 30 || { status error "$EZSP_PTY missing (zigbee_socat not up)"; return 1; }
  r=""; [ -n "$3" ] && r=",range=$3"
  /vendor/bin/socat TCP-LISTEN:$2,reuseaddr,fork,max-children=1,keepalive,keepidle=30,keepintvl=10,keepcnt=3$1$r \
    FILE:$EZSP_PTY,raw,echo=0 &
  BRIDGE_PID=$!; BRIDGE_RANGE=$3
}
coordinator_bridge() {
  r=$(bridge_range)
  if alive "$BRIDGE_PID" && [ "$r" != "$BRIDGE_RANGE" ]; then
    say "client range changed: '${BRIDGE_RANGE:-any}' -> '${r:-any}'"; kill "$BRIDGE_PID"; BRIDGE_PID=""
  fi
  alive "$BRIDGE_PID" || start_bridge "" "$ZIGBEE_PORT" "$r"
}

# zigbee_router: keep a copy of zigbeed's NVM (network + keys) before the app's router host may
# replace it by joining. Only when it changed; the last 3 are kept. Root-only, never leaves $DIR.
backup_nvm() {
  [ -s "$ZIGBEED_NVM" ] || return 0
  mkdir -p "$DIR/zigbee-nvm" && chmod 700 "$DIR/zigbee-nvm"
  last=$(ls "$DIR/zigbee-nvm" 2>/dev/null | tail -1)
  [ -n "$last" ] && cmp -s "$ZIGBEED_NVM" "$DIR/zigbee-nvm/$last" && return 0
  cp "$ZIGBEED_NVM" "$DIR/zigbee-nvm/zigbeed-$(date +%Y%m%d-%H%M%S).nvm" && chmod 600 "$DIR"/zigbee-nvm/*
  set -- $(ls "$DIR/zigbee-nvm"); while [ $# -gt 3 ]; do rm -f "$DIR/zigbee-nvm/$1"; shift; done
}
router_bridge() {
  alive "$BRIDGE_PID" && return 0
  backup_nvm
  start_bridge ",bind=127.0.0.1" "$ROUTER_PORT" ""
}

ot() { # ot-ctl without its CRLF line ends, keeping its exit status
  o_=$("$(bin ot-ctl)" "$@" 2>/dev/null); r_=$?
  [ -n "$o_" ] && printf '%s\n' "$o_" | tr -d '\r'
  return $r_
}

start_otbr() { # settings-dir extra-args...
  data=$1; shift
  [ -x "$(bin otbr-agent)" ] || { status error "otbr-agent not installed ($DIR or $BUNDLE)"; return 1; }
  wait_for $CPC_SOCK 30 || { status error "cpcd socket missing"; return 1; }
  # cpcd needs ~4 s to reach the secondary after a (re)start, and endpoint 12 closes asynchronously:
  # an early start fails ("CPC init failed -2" / "CPC endpoint open failed"); the loop retries.
  sleep 5
  mkdir -p "$DIR/run" "$data"  # TUN is /dev/tun (compiled in by build-otbr.sh)
  # shellcheck disable=SC2086
  "$(bin otbr-agent)" -s -I wpan0 "$@" --data-path "$data" $OTBR_ARGS "$RADIO_URL" &
  OTBR_PID=$!
}

start_thread_br() {
  if [ "$OTBR_KIND" = br ]; then
    br_net_on
    start_otbr "$DIR/thread" -B "$BACKBONE" --rest-listen-address "$REST_LISTEN" --rest-listen-port "$REST_PORT"
  else
    start_otbr "$DIR/thread"  # proof build: agent only (no border routing/REST/mDNS)
  fi
}

# thread_router: no backbone (no border routing, no mDNS on the LAN). Never forms a network on its
# own initiative: it only starts Thread once it has a dataset the user supplied. Its own settings dir
# ($DIR/thread-router), so it never auto-attaches to the network the border-router mode stored.
take_app_dataset() {
  [ -e "$APP_DATASET" ] || return 0
  if [ -L "$APP_DATASET" ] || [ "$(stat -c %s "$APP_DATASET" 2>/dev/null || echo 9999)" -gt 1024 ]; then
    say "ignoring app dataset hand-off (symlink or too big)"; rm -f "$APP_DATASET"; return 0
  fi
  d=$(tr -d ' \r\n' < "$APP_DATASET"); rm -f "$APP_DATASET"
  case "$d" in ""|*[!0-9a-fA-F]*) say "ignoring app dataset hand-off (not hex TLVs)"; return 0 ;; esac
  [ $(( ${#d} % 2 )) -eq 0 ] || { say "ignoring app dataset hand-off (odd length)"; return 0; }
  ( umask 077; echo "$d" > "$DIR/thread-dataset" ); JOINED=""
  say "new Thread dataset from the app"  # never log the dataset itself
  # a running agent may be attached elsewhere: restart it, the next pass joins with the new dataset
  alive "$OTBR_PID" && { kill "$OTBR_PID"; OTBR_PID=""; }
}
start_thread_router() {
  if [ "$OTBR_KIND" = br ]; then start_otbr "$DIR/thread-router" --rest-listen-address 127.0.0.1 --rest-listen-port "$REST_PORT"
  else start_otbr "$DIR/thread-router"; fi
  JOINED=""
}
# Called every loop once the agent answers: joins once a dataset is there.
thread_router_join() {
  [ -n "$JOINED" ] && return 0
  [ -s "$DIR/thread-dataset" ] || return 0
  if ot dataset set active "$(cat "$DIR/thread-dataset")" >/dev/null && ot ifconfig up >/dev/null &&
     ot thread start >/dev/null; then
    JOINED=1
  else
    status error "ot-ctl join steps failed (dataset rejected?)"; return 1
  fi
}

alive() { [ -n "$1" ] && kill -0 "$1" 2>/dev/null; }

children_ok() {
  case "$MODE" in
    off)               br_net_off ;;
    zigbee_tcp)        br_net_off; coordinator_bridge || return 1 ;;
    zigbee_router)     br_net_off; router_bridge || return 1 ;;
    thread)            alive "$OTBR_PID"   || start_thread_br || return 1
                       [ "$OTBR_KIND" = br ] && br_net_on ;;
    thread_router)     br_net_off; take_app_dataset
                       alive "$OTBR_PID"   || start_thread_router || return 1 ;;
    zigbee_tcp_thread) coordinator_bridge || return 1
                       alive "$OTBR_PID"   || start_otbr "$DIR/thread" || return 1 ;;
  esac
  return 0
}

# While the mode's stack isn't usable yet: prints why and returns 0. Ready: returns 1.
not_ready() {
  case "$MODE" in
    zigbee_tcp|zigbee_router|zigbee_tcp_thread)
      zigbeed_ready || { echo "waiting for zigbeed"; return 0; } ;;
  esac
  case "$MODE" in
    thread|thread_router|zigbee_tcp_thread)
      ot state >/dev/null || { echo "waiting for otbr-agent"; return 0; } ;;
  esac
  return 1
}

detail() {
  case "$MODE" in
    off) echo "Aqara radio services stopped, radio idle" ;;
    zigbee_tcp)
      r=$BRIDGE_RANGE; echo "EZSP on tcp:$ZIGBEE_PORT${r:+ (clients from $r)}" ;;
    zigbee_router) echo "EZSP on 127.0.0.1:$ROUTER_PORT for the router host" ;;
    thread)
      if [ "$OTBR_KIND" = br ]; then
        echo "border router on wpan0, backbone $BACKBONE, REST :$REST_PORT ($(ot state | head -1))"
      else
        echo "otbr-agent on wpan0, agent build without border routing ($(ot state | head -1))"
      fi ;;
    thread_router)
      if [ -s "$DIR/thread-dataset" ]; then echo "Thread router on wpan0 ($(ot state | head -1))"
      else echo "ready to join: waiting for a Thread dataset from Home Assistant"; fi ;;
    zigbee_tcp_thread) echo "EZSP on tcp:$ZIGBEE_PORT; otbr-agent on wpan0" ;;
  esac
}

# --- main ------------------------------------------------------------------------------------
status applying

while true; do
  # Re-enforce every 3 s, in every mode incl. off: Aqara's boot sequence (zigbee_cpcd_factory ->
  # sys.gateway.config=10, sys.svc.gateway=5 -> ha_matter) and its watchdog (sys.gateway.config=11)
  # start the stock stack again; Z3Gateway's wrapper sleeps 6 s before opening the pty, so we stop it first.
  if enforce; then
    if children_ok; then
      FAILS=0
      if w=$(not_ready); then
        status applying "$w"
      elif [ "$MODE" = thread_router ] && ! thread_router_join; then
        FAILS=$((FAILS+1))   # thread_router_join set the status
      else
        status ok "$(detail)"
      fi
    else
      FAILS=$((FAILS+1)) # start_* set the status
    fi
  else
    FAILS=$((FAILS+1))
    status error "services not in wanted state:$bad"
  fi
  if [ $FAILS -gt 20 ]; then status error "giving up after repeated failures"; exec sleep 2147483647; fi
  sleep 3
done
