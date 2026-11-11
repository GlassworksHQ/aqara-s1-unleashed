#!/system/bin/sh
# s1kiosk_radio: root helper for the S1 Kiosk radio modes (docs/radio.md), contract v4.
#
# Runs as an init service (s1kiosk_radio.rc: root, u:r:su:s0, started at boot, never exits).
# Reads persist.s1kiosk.radio_mode, brings Aqara's radio services to the state that mode needs,
# starts/supervises our own processes (EZSP TCP bridges, zigbeed, otbr-agent) and reports in
# sys.s1kiosk.radio.status as "<applying|ok|error> <mode>[: detail]".
# The app changes mode with: setprop persist.s1kiosk.radio_mode <id>; setprop ctl.restart s1kiosk_radio
# (init kills the whole process group on restart, so our children go with it.)
#
# Mode ids: off (default) | zigbee_tcp | zigbee_router | thread | thread_router
#   experimental, not offered by the app: zigbee_tcp_thread (Zigbee + Thread on one multi-PAN radio)
# There is no stock mode: Aqara's services are init services, so removing this helper (or the app,
# tools/install.sh --undo) and rebooting brings the stock stack back.
#
#   zigbee_tcp     zigbeed's EZSP on tcp:$ZIGBEE_PORT (ZHA: socket://<panel>:6638, Zigbee2MQTT:
#                  tcp://<panel>:6638). Zigbee stack (v4): Aqara's zigbeed 7.3.3 (EZSP 12, default) or
#                  Silicon Labs' zigbeed 8.2.2 (EZSP 18, persist.s1kiosk.radio_zigbee=sisdk). The 8.2.2
#                  binary is Silicon Labs MSLA code: never in our bundles; the app downloads it from
#                  Silicon Labs and this helper accepts it only with the pinned SHA-256.
#   zigbee_router  zigbeed's EZSP on 127.0.0.1:$ROUTER_PORT only; the app's router host
#                  (radio/ZigbeeRouterHost.kt) drives zigbeed as an EMBER_ROUTER. Always Aqara's
#                  zigbeed (the router host speaks EZSP 12). zigbeed's NVM is copied to
#                  $DIR/zigbee-nvm/ first, because joining replaces a coordinator network.
#   thread         otbr-agent on spinel IID 2. With a border-routing build (tools/radio/build-otbr.sh
#                  PROFILE=br): backbone $BACKBONE (wlan0), REST API on $REST_LISTEN:$REST_PORT for
#                  Home Assistant's OTBR integration, OpenThread mDNS/SRP, IPv6 forwarding + the
#                  policy-routing rules Android needs. With the minimal proof build: agent only.
#   thread_router  otbr-agent without a backbone (no border routing), joins with the active dataset
#                  in $DIR/thread-dataset. The app hands a new dataset over in $APP_DATASET (its own
#                  private files dir); the helper moves it into $DIR (root 0600) and deletes the
#                  app's copy. Without a dataset the stack runs and waits ("ready to join").
#
# Firewall (contract v3): in every mode but off, an iptables/ip6tables INPUT chain ($FW_CHAIN) lets
# only loopback and the allowed addresses reach tcp:$ZIGBEE_PORT and tcp:$REST_PORT; everyone else
# is dropped. The allowed addresses come from the app (persist.s1kiosk.radio_allow: Home Assistant's
# address as seen on the ESPHome API, or the owner's explicit list), or RADIO_ALLOW in radio.conf.
# Empty = nobody but loopback yet. "any" = no rules (the v2 behaviour). Removed in off.
#
# Loader and radio bundles (contract v4): the copy of this script in the boot image is also a small,
# stable loader. If /data/s1kiosk/radio/bundle/current holds a radio bundle signed with the project's
# key (installed by s1kiosk_bundle.sh, checked again here on every start with s1kiosk_bundle.sh
# --check), its s1kiosk_radio.sh runs instead of this one, and its binaries win over the boot image's.
# A bundle helper that keeps exiting within a minute (3 times) is marked bad and this copy runs.
# sys.s1kiosk.radio.source says which helper runs: "boot[ (why)]" | "bundle <version>" | "dev".
#
# Radio firmware (contract v4): never automatic. The app writes "<id> <sha256 prefix>" to its own
# files/radio/fw-request; within 3 s the helper stops the radio, flashes that image with Aqara's own
# /vendor/bin/cpcd -c /etc/cpcd_ota.conf -f (the only proven path on this board), reads the radio's
# version back over CPC and rolls back to Aqara's stock image from /vendor/firmware if that fails.
# Images: "stock" (Aqara's GBL on /vendor) or a "firmware" item of the signed radio bundle.
# Status: sys.s1kiosk.radio.fw = "<idle|flashing|ok|error> <id>[: detail]", targets in
# sys.s1kiosk.radio.fw_targets = "<id>:<sha256 prefix>:<APP version> ...".
#
# Optional root-owned overrides in $DIR/radio.conf (sourced):
#   ZIGBEE_PORT=6638  ZIGBEE_RANGE=lan|<cidr>  ROUTER_PORT=6639  BACKBONE=wlan0
#   REST_LISTEN=0.0.0.0  REST_PORT=8081  OTBR_ARGS="..."  RADIO_URL=...
#   RADIO_ALLOW="<ip|cidr> ..."|any|none   (wins over the app's list; empty = the app's list)
#   ZIGBEE_STACK=aqara|sisdk               (wins over the app's choice; empty = the app's choice)
#   ZIGBEED_ARGS="..."                     (extra arguments for Silicon Labs' zigbeed, e.g. "-v 1 -d 3")
# Time zone (contract v4): Aqara's SystemManager resets the zone at every boot (and whenever its own
# gateway time-zone setting changes): with that setting empty, as on our panels with Aqara's cloud
# blocked, it picks the first zone at UTC+8, Antarctica/Casey. A background loop of this helper keeps
# the owner's zone instead: persist.s1kiosk.timezone (an Olson id, set by the installer or the app's
# `Time zone` entity) is re-applied with `service call alarm 3 s16 <id>` (AlarmManager.setTimeZone)
# whenever persist.sys.timezone differs, every 5 s for the first 10 minutes, then every 15 s. A zone
# Android doesn't accept is reported once and not retried until the wanted zone changes. Status:
# sys.s1kiosk.timezone.status = "<ok|error|none> [<id>][: detail]". The loop runs whatever the radio
# mode does (also after "giving up"), and is restarted with the helper.
#
# Developer override: with a root-made $DIR/dev-override file, a root-owned $DIR/s1kiosk_radio.sh runs
# instead of everything else, and binaries in $DIR win. (Before v4 those were honoured without the
# marker; a leftover copy would otherwise shadow every update.)

DIR=${S1R_DIR:-/data/s1kiosk/radio}     # radio.conf, thread-dataset, zigbee-nvm/, bundle/, fw/, sisdk/
CONTRACT=4
BOOT=${0%/*}                             # where this script lives (boot image: ${MAGISKTMP})

setp() { # name value: setprop refuses values over 91 bytes (PROP_VALUE_MAX) and keeps the old one
  v_=$2; [ ${#v_} -gt 91 ] && v_="$(printf '%.88s' "$v_")..."
  setprop "$1" "$v_"
}

# --- loader (boot-image copy only) ---------------------------------------------------------------
if [ -z "$S1R_NO_OVERRIDE" ]; then
  lsay() { log -t s1kiosk_radio "$*" 2>/dev/null; }
  isroot() { [ -e "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u "$1" 2>/dev/null)" = 0 ]; }
  RB=$DIR/bundle/current
  if isroot "$DIR" && isroot "$DIR/dev-override" && isroot "$DIR/s1kiosk_radio.sh"; then
    S1R_NO_OVERRIDE=1 S1R_BUNDLE="$BOOT" S1R_SOURCE=dev S1R_DEV=1 exec /system/bin/sh "$DIR/s1kiosk_radio.sh" "$@"
  fi
  [ -e "$DIR/s1kiosk_radio.sh" ] && lsay "ignoring $DIR/s1kiosk_radio.sh: radio bundles replace it (or create $DIR/dev-override)"
  why=""; bv=0
  # the app-readable state (s1kiosk_bundle.sh --publish): "<ok|bad|none> <version> <floor>"
  pub() { /system/bin/sh "$BOOT/s1kiosk_bundle.sh" --publish "$1" "$2" >/dev/null 2>&1; }
  if [ -e "$RB" ]; then
    if out=$(/system/bin/sh "$BOOT/s1kiosk_bundle.sh" --check "$RB" 2>&1); then
      # shellcheck disable=SC2086
      set -- $out; bv=$1
      if [ -e "$DIR/bundle/bad-$bv" ]; then
        why="bundle $bv: its helper kept exiting"
      elif [ "$2" = helper ]; then
        setp sys.s1kiosk.radio.bundle "ok $bv"; pub ok "$bv"
        quick=0
        while :; do
          t0=$(date +%s)
          S1R_NO_OVERRIDE=1 S1R_BUNDLE="$BOOT" S1R_RBUNDLE="$RB" S1R_SOURCE="bundle $bv" /system/bin/sh "$RB/s1kiosk_radio.sh"
          rc=$?
          [ -n "$S1R_LOADER_ONCE" ] && exit $rc   # tests only
          if [ $(( $(date +%s) - t0 )) -lt 60 ]; then quick=$((quick + 1)); else quick=0; fi
          lsay "radio bundle $bv: helper exited ($rc), quick exits in a row: $quick"
          [ $quick -ge 3 ] && break
          sleep 2
        done
        : > "$DIR/bundle/bad-$bv"
        why="bundle $bv: its helper kept exiting"
      else
        setp sys.s1kiosk.radio.bundle "ok $bv"; pub ok "$bv"
        S1R_RBUNDLE=$RB      # a bundle without a helper: this helper with the bundle's files
      fi
    else
      why="bundle rejected: $out"
      bv=$(sed -n 's/^version \([0-9][0-9]*\)$/\1/p' "$RB/manifest" 2>/dev/null | head -1)
    fi
    if [ -n "$why" ]; then
      setp sys.s1kiosk.radio.bundle "bad: $why"; pub bad "${bv:-0}"; lsay "$why; using the boot image's helper"
    fi
  else
    setp sys.s1kiosk.radio.bundle none; pub none 0
  fi
  S1R_SOURCE="boot${why:+ ($why)}"
fi

BUNDLE=${S1R_BUNDLE:-$BOOT}              # the boot image's files (verifier, key, otbr-agent, ot-ctl)
RBUNDLE=${S1R_RBUNDLE:-}                 # the signed radio bundle the loader verified, or empty
SOURCE=${S1R_SOURCE:-boot}
DEV=${S1R_DEV:-}
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
# Who may reach tcp:$ZIGBEE_PORT / tcp:$REST_PORT (firewall, see the header). Empty = the app's list.
RADIO_ALLOW=""
ZIGBEE_STACK=""                          # radio.conf: aqara|sisdk; empty = persist.s1kiosk.radio_zigbee
ZIGBEED_ARGS=""
PROP_ALLOW=persist.s1kiosk.radio_allow   # app -> helper: allowed addresses ("" | any | "<ip|cidr> ...")
PROP_ALLOWED=sys.s1kiosk.radio.allowed   # helper -> app: "<app|conf> <any|none|<ip|cidr> ...>", "" in off
FW_CHAIN=S1RADIO
APP_DATA=/data/data/sh.edm.s1kiosk
APP_DATASET=$APP_DATA/files/radio/thread-dataset  # app -> helper hand-off (thread_router)
EZSP_PTY=/dev/ttyZigbeeNCP               # host side of Aqara's socat pty pair (zigbeed is on /tmp/ttyZigbeeNCP)
ZIGBEED_PTY=/tmp/ttyZigbeeNCP
ZIGBEED_NVM=/data/zigbee_host/zigbeed_host_token.nvm
CPC_SOCK=/dev/shm/cpcd/cpcd_0/ctrl.cpcd.sock
RULE_PREF_IN=14500                       # thread BR: "iif <backbone> lookup <wpan0's table>" (LAN -> Thread)
RULE_PREF_OUT=14501                      # thread BR: "iif wpan0 lookup <backbone>" (Thread -> LAN)
# Silicon Labs zigbeed 8.2.2 (EmberZNet 8.2.2, EZSP 18): Simplicity SDK v2025.6.3 debian-bookworm.zip,
# deb/zigbeed_8.2.2_armhf.deb, usr/local/bin/zigbeed, unmodified (tools/radio/build-zigbeed.sh pins).
SISDK_VERSION=8.2.2
SISDK_SHA256=a66d45842a770e1c449a55208a6d2a3b36786eb1c94bf10d3c3aac317274e3bb
SISDK_SIZE=663312
SISDK_BIN=$DIR/sisdk/zigbeed-$SISDK_VERSION   # root 0700, taken from the app's download
SISDK_STATE=$DIR/zigbeed-sisdk                # its host_token.nvm (Aqara's NVM is never touched)
APP_ZIGBEED=radio/zigbeed-$SISDK_VERSION      # app -> helper hand-off, relative to the app's files/
ZIGBEED_URL='spinel+cpc://cpcd_0?iid=1&iid-list=0'
# Radio firmware: Aqara's stock RCP image (the rollback target) and the ones we know.
FW_DIR=$DIR/fw
STOCK_GLOB='/vendor/firmware/lichun#30000000#*.bin'
KNOWN_STOCK="d7b79c6fab1e36e438d0e510bc596de7ac053f6d7b4b99a291af3f323baa8fa9:0017"
APP_FW_REQ=radio/fw-request                   # app -> helper, relative to the app's files/
APP_THREAD_LEAVE=radio/thread-leave           # app -> helper: forget the Thread network (any mode)

# Root-only state dir; created on first run so a freshly flashed boot image needs no other install step.
[ -d "$DIR" ] || { mkdir -p "$DIR" && chown root:root "$DIR" && chmod 700 "$DIR"; }
# Binaries: developer copies in $DIR (with dev-override only), then the radio bundle, then the boot image.
bin() {
  if [ -n "$DEV" ] && [ -x "$DIR/$1" ]; then echo "$DIR/$1"
  elif [ -n "$RBUNDLE" ] && [ -x "$RBUNDLE/$1" ]; then echo "$RBUNDLE/$1"
  else echo "$BUNDLE/$1"; fi
}
[ -r "$DIR/radio.conf" ] && . "$DIR/radio.conf"

MODE=${S1R_MODE:-$(getprop persist.s1kiosk.radio_mode)}  # S1R_MODE: manual test without touching the persist prop
[ -n "$MODE" ] || MODE=off

say() { log -t s1kiosk_radio "$*" 2>/dev/null; }
SHOWN=""
status() { # state [detail]; only writes when it changed
  if [ -n "$2" ]; then st_="$1 $MODE: $2"; else st_="$1 $MODE"; fi
  # setprop refuses values over 91 bytes (PROP_VALUE_MAX) and the old status would stay: cut it
  [ ${#st_} -gt 91 ] && st_="$(printf '%.88s' "$st_")..."
  [ "$st_" = "$SHOWN" ] && return 0
  SHOWN=$st_
  setprop sys.s1kiosk.radio.status "$st_"
  say "$st_"
}
setprop sys.s1kiosk.radio.version $CONTRACT   # v4 = v3 + bundles, Zigbee stack choice, radio firmware
setp sys.s1kiosk.radio.source "$SOURCE"

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

# --- time zone ------------------------------------------------------------------------------------
TZ_PROP=persist.s1kiosk.timezone          # wanted: an Olson id ("" = not managed)
TZ_STATUS=sys.s1kiosk.timezone.status
# What Aqara's sync picks for its default "GMT+08:00" (never adopted as the owner's choice).
TZ_AQARA_DEFAULT="Antarctica/Casey"
TZ_PID=""; TZ_BAD=""; TZ_SHOWN=""
tz_valid() { # an Olson id: letters/digits/_+- in up to 3 /-separated parts, or UTC/GMT forms
  case "$1" in ""|/*|*/|*//*|*[!A-Za-z0-9_+/-]*) return 1 ;; esac
  [ ${#1} -le 64 ] && [ "$(echo "$1" | tr -cd / | wc -c)" -le 2 ]
}
tz_say() { [ "$1" = "$TZ_SHOWN" ] && return 0; TZ_SHOWN=$1; setp $TZ_STATUS "$1"; say "time zone: $1"; }
tz_check() {
  want_=$(getprop $TZ_PROP); have_=$(getprop persist.sys.timezone)
  if [ -z "$want_" ]; then
    # Panels set up before this existed: the zone the installer set is still in persist.sys.timezone
    # at boot, until Aqara's sync overwrites it. Adopt it once if it is a real choice.
    if tz_valid "$have_" && [ "$have_" != "$TZ_AQARA_DEFAULT" ] && [ "$have_" != GMT ] && [ "$have_" != UTC ]; then
      setprop $TZ_PROP "$have_"; say "time zone: adopted $have_ as the zone to keep"; want_=$have_
    else
      tz_say "none: no time zone chosen (Time zone in Home Assistant, or the installer)"; return 0
    fi
  fi
  if ! tz_valid "$want_"; then tz_say "error: '$(printf '%.24s' "$want_")' is not a time zone id"; return 0; fi
  if [ "$want_" = "$have_" ]; then TZ_BAD=""; tz_say "ok $want_"; return 0; fi
  [ "$want_" = "$TZ_BAD" ] && return 0     # Android refused it before: don't loop on it
  [ "$(settings get global auto_time_zone 2>/dev/null)" = 0 ] || settings put global auto_time_zone 0 2>/dev/null
  service call alarm 3 s16 "$want_" >/dev/null 2>&1
  i_=0; while [ "$(getprop persist.sys.timezone)" != "$want_" ] && [ $i_ -lt 20 ]; do sleep 0.1; i_=$((i_ + 1)); done
  now_=$(getprop persist.sys.timezone)
  if [ "$now_" = "$want_" ]; then
    say "time zone: ${have_:-unset} -> $want_ (re-applied)"; tz_say "ok $want_"
  else
    TZ_BAD=$want_; tz_say "error $want_: Android did not accept it (now ${now_:-unset})"
  fi
}
tz_start() { # one check now (so the first pass is done before the radio work), then the loop
  tz_check
  parent_=$$   # the helper; the loop ends with it (also if it exits without tz_stop)
  ( t0_=$(date +%s)
    while :; do
      if [ $(( $(date +%s) - t0_ )) -lt 600 ]; then sleep 5; else sleep 15; fi
      kill -0 "$parent_" 2>/dev/null || exit 0
      tz_check
    done ) &
  TZ_PID=$!
}
tz_stop() { alive "$TZ_PID" && kill "$TZ_PID"; TZ_PID=""; }
tz_start

alive() { [ -n "$1" ] && kill -0 "$1" 2>/dev/null; }
sha256() { set -- $(sha256sum "$1" 2>/dev/null); echo "$1"; }

# --- files the app hands over --------------------------------------------------------------------
# take_app_file <path relative to the app's files/> <N|max:N> <dest> [move]: copies a file the kiosk
# wrote into a root-only place, with `s1kiosk_verify --copy` (or --move, which also removes the app's
# copy): an openat(O_NOFOLLOW) walk where every component must be the kiosk's, the file opened
# O_NONBLOCK and checked on the fd (regular, one link, size), exactly that many bytes copied. So a path
# swapped for a symlink, a FIFO or /dev/zero can't make root read something else or hang. Returns 1
# quietly when it isn't there, 2 with WHY when it is unacceptable; TAKE_MTIME = the file's mtime.
APP_UID=""; TAKE_MTIME=0
take_app_file() {
  [ -n "$APP_UID" ] || APP_UID=$(stat -c %u "$APP_DATA" 2>/dev/null)
  case "$APP_UID" in ''|*[!0-9]*) WHY="kiosk not installed"; return 2 ;; esac
  [ "$APP_UID" -ge 10000 ] || { WHY="kiosk data dir has a system owner"; return 2; }
  [ -x "$BUNDLE/s1kiosk_verify" ] || { WHY="no s1kiosk_verify in the boot image"; return 2; }
  rm -f "$3"
  o_=$("$BUNDLE/s1kiosk_verify" "--${4:-copy}" "$APP_DATA" "files/$1" "$APP_UID" "$2" "$3" 2>&1); r_=$?
  case $r_ in
    0) set -- $o_; TAKE_MTIME=${2:-0}; return 0 ;;
    3) return 1 ;;
    4) o_=${o_#s1kiosk_verify: }; WHY="${1##*/} refused: ${o_%%: *}" ;;
    *) WHY="cannot copy ${1##*/}" ;;
  esac
  rm -f "$3"; return 2
}

# --- Zigbee stack (zigbee_tcp only) ---------------------------------------------------------------
ZWANT=${S1R_ZIGBEE:-${ZIGBEE_STACK:-$(getprop persist.s1kiosk.radio_zigbee)}}
[ "$ZWANT" = sisdk ] || ZWANT=aqara
ZRUN=aqara; [ "$MODE" = zigbee_tcp ] && [ "$ZWANT" = sisdk ] && ZRUN=sisdk
SISDK_DIR=""; [ -n "$RBUNDLE" ] && [ -f "$RBUNDLE/sisdk/zigbeed.sh" ] && SISDK_DIR=$RBUNDLE/sisdk
ZSHOWN=""
zstate() { # what the app sees in sys.s1kiosk.radio.zigbee: "aqara" | "sisdk <ready|missing|no-runtime|bad ...>"
  if [ "$ZWANT" = aqara ]; then z_=aqara
  elif [ -z "$SISDK_DIR" ]; then z_="sisdk no-runtime"
  elif [ -x "$SISDK_BIN" ]; then z_="sisdk ready $SISDK_VERSION"
  elif [ -n "$SISDK_BAD" ]; then z_="sisdk bad $SISDK_BAD"
  else z_="sisdk missing $SISDK_VERSION $SISDK_SHA256"; fi
  [ "$z_" = "$ZSHOWN" ] || { setp sys.s1kiosk.radio.zigbee "$z_"; ZSHOWN=$z_; }
}
# The app downloads zigbeed 8.2.2 from Silicon Labs into its files dir; take it if it is the pinned one.
SISDK_BAD=""
take_sisdk() {
  [ "$ZWANT" = sisdk ] && [ -n "$SISDK_DIR" ] && [ ! -x "$SISDK_BIN" ] || return 0
  mkdir -p "$DIR/sisdk" && chmod 700 "$DIR/sisdk"
  take_app_file "$APP_ZIGBEED" "$SISDK_SIZE" "$SISDK_BIN.tmp"; r_=$?
  [ $r_ = 1 ] && return 0
  [ $r_ = 2 ] && { [ "$SISDK_BAD" = "$WHY" ] || say "zigbeed hand-off refused: $WHY"; SISDK_BAD=$(echo "$WHY" | cut -c 1-40); return 0; }
  h_=$(sha256 "$SISDK_BIN.tmp")
  if [ "$h_" = "$SISDK_SHA256" ] && [ "$(stat -c %s "$SISDK_BIN.tmp")" = "$SISDK_SIZE" ]; then
    chmod 700 "$SISDK_BIN.tmp" && mv "$SISDK_BIN.tmp" "$SISDK_BIN" && SISDK_BAD="" &&
      say "Silicon Labs zigbeed $SISDK_VERSION installed (sha256 matches the pin)"
  else
    rm -f "$SISDK_BIN.tmp"
    [ "$SISDK_BAD" = "$(echo "$h_" | cut -c 1-12)" ] || say "zigbeed hand-off refused: sha256 $h_ is not the pinned $SISDK_SHA256"
    SISDK_BAD=$(echo "$h_" | cut -c 1-12)
  fi
}

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
# Silicon Labs' zigbeed replaces Aqara's: same pty pair, our process instead of zigbee_zigbeed.
[ "$ZRUN" = sisdk ] && WANT="zigbee_cpcd zigbee_socat"
MODE_WANT=$WANT

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

# --- firewall: who may reach the radio's network services --------------------------------------
# Android 10 here: legacy iptables/ip6tables 1.6.1 in /system/bin (no multiport match, so one jump
# per port; iptables-save needs no lock). netd owns INPUT's other jumps (bw_INPUT, fw_INPUT) and may
# flush INPUT when it restarts, so the rules are checked every loop and put back. The chain's
# contents are replaced atomically (iptables-restore --noflush with ":$FW_CHAIN -" flushes just that
# chain in the same commit), so a change of address never opens the ports in between.
FW_STATE=unknown   # what is in place: unknown | clear | any | "<list>|<ports>"
FW_EFF=""          # addresses actually allowed (normalized list, "any", or "" = loopback only)
FW_SRC=app         # where the list came from: app (persist prop) | conf (RADIO_ALLOW)
FW_BAD=""          # tokens that were ignored (not an address) or rejected by iptables

ipt() { f_=$1; shift; if [ "$f_" = 6 ]; then ip6tables -w 5 "$@"; else iptables -w 5 "$@"; fi; }
ipt_save() { if [ "$1" = 6 ]; then ip6tables-save -t filter; else iptables-save -t filter; fi; }
ipt_restore() { if [ "$1" = 6 ]; then ip6tables-restore -w 5 --noflush; else iptables-restore -w 5 --noflush; fi; }

# Wanted list -> FW_SRC, FW_LIST ("any" | "" | "a b ..."), FW_BAD. Tokens may be separated by spaces
# or commas; "none" adds nothing; anything that isn't made of address characters is ignored.
fw_wanted() {
  if [ -n "$RADIO_ALLOW" ]; then FW_SRC=conf; v_=$RADIO_ALLOW
  else FW_SRC=app; v_=$(getprop $PROP_ALLOW); fi
  FW_LIST=""; FW_BAD=""
  set -f  # no globbing of tokens such as '*'
  for t_ in $(echo "$v_" | tr ',' ' '); do
    case "$t_" in
      any|ANY) FW_LIST=any; break ;;
      none|NONE) ;;
      *:*) case "$t_" in *[!0-9a-fA-F:./]*) FW_BAD="$FW_BAD $t_" ;; *) FW_LIST="$FW_LIST $t_" ;; esac ;;
      *.*) case "$t_" in *[!0-9./]*) FW_BAD="$FW_BAD $t_" ;; *) FW_LIST="$FW_LIST $t_" ;; esac ;;
      *) FW_BAD="$FW_BAD $t_" ;;
    esac
  done
  set +f
  FW_LIST=${FW_LIST# }; FW_BAD=${FW_BAD# }
}

fw_family_count() { # 4|6 list -> number of addresses of that family
  n_=0
  for t_ in $2; do
    case "$t_" in *:*) [ "$1" = 6 ] && n_=$((n_+1)) ;; *) [ "$1" = 4 ] && n_=$((n_+1)) ;; esac
  done
  echo $n_
}

fw_jumps() { for p_ in $ZIGBEE_PORT $REST_PORT; do echo "-A INPUT -p tcp -m tcp --dport $p_ -j $FW_CHAIN"; done; }

# The rules for family $1 are in place for list $2: the chain has lo + one RETURN per address + DROP,
# and INPUT has exactly our jumps (iptables-save prints them in rule order).
fw_ok() {
  s_=$(ipt_save $1) || return 1
  n_=$(printf '%s\n' "$s_" | grep -c "^-A $FW_CHAIN ")
  [ "$n_" = $(( $(fw_family_count $1 "$2") + 2 )) ] || return 1
  printf '%s\n' "$s_" | grep -q "^-A $FW_CHAIN -j DROP\$" || return 1
  [ "$(printf '%s\n' "$s_" | grep "^-A INPUT .*-j $FW_CHAIN\$")" = "$(fw_jumps)" ]
}

fw_del_jumps() { # family: removes every INPUT jump to the chain (also stale ports, duplicates)
  ipt_save $1 | sed -n "s/^-A INPUT \(.*-j $FW_CHAIN\)\$/-D INPUT \1/p" | while read -r l_; do
    # shellcheck disable=SC2086
    ipt $1 $l_
  done
}

fw_set() { # family list: chain contents (atomic), then the jumps at the top of INPUT
  {
    echo "*filter"
    echo ":$FW_CHAIN - [0:0]"
    echo "-A $FW_CHAIN -i lo -j RETURN"
    for t_ in $2; do
      case "$t_" in *:*) [ "$1" = 6 ] ;; *) [ "$1" = 4 ] ;; esac && echo "-A $FW_CHAIN -s $t_ -j RETURN"
    done
    echo "-A $FW_CHAIN -j DROP"
    echo "COMMIT"
  } | ipt_restore $1 || return 1
  if [ "$(ipt_save $1 | grep "^-A INPUT .*-j $FW_CHAIN\$")" != "$(fw_jumps)" ]; then
    fw_del_jumps $1
    for p_ in $REST_PORT $ZIGBEE_PORT; do ipt $1 -I INPUT 1 -p tcp --dport $p_ -j $FW_CHAIN || return 1; done
  fi
}

fw_clear() { # removes everything of ours (off, "any")
  for f_ in 4 6; do
    fw_del_jumps $f_
    ipt $f_ -F $FW_CHAIN 2>/dev/null; ipt $f_ -X $FW_CHAIN 2>/dev/null
  done
  return 0
}

# Every loop in every mode but off. Returns 1 (status set) when the rules could not be put in place.
fw_apply() {
  fw_wanted
  [ -n "$FW_BAD" ] && [ "$FW_BAD" != "$FW_BAD_SAID" ] && { say "firewall: ignoring '$FW_BAD' (not an address)"; FW_BAD_SAID=$FW_BAD; }
  if [ "$FW_LIST" = any ]; then
    [ "$FW_STATE" = any ] || { fw_clear; FW_STATE=any; FW_EFF=any; say "firewall: off (allow any), $FW_SRC"; }
  else
    sig_="$FW_LIST|$ZIGBEE_PORT $REST_PORT"
    if [ "$FW_STATE" != "$sig_" ] || ! fw_ok 4 "$FW_EFF" || ! fw_ok 6 "$FW_EFF"; then
      FW_EFF=$FW_LIST
      if ! fw_set 4 "$FW_EFF" || ! fw_set 6 "$FW_EFF"; then
        # iptables refused an entry (e.g. a bad prefix length): loopback only rather than open
        say "firewall: iptables rejected '$FW_EFF', allowing loopback only"
        FW_BAD="${FW_BAD:+$FW_BAD }$FW_EFF"; FW_EFF=""
        fw_set 4 "" && fw_set 6 ""
      fi
      if ! fw_ok 4 "$FW_EFF" || ! fw_ok 6 "$FW_EFF"; then
        FW_STATE=unknown; status error "firewall rules for tcp:$ZIGBEE_PORT/$REST_PORT could not be set"; return 1
      fi
      [ "$FW_STATE" = "$sig_" ] || say "firewall: tcp:$ZIGBEE_PORT/$REST_PORT from ${FW_EFF:-loopback only} ($FW_SRC)"
      FW_STATE=$sig_
    fi
  fi
  a_=$(allowed_value)
  [ "$a_" = "$FW_ALLOWED_SHOWN" ] || { setprop $PROP_ALLOWED "$a_"; FW_ALLOWED_SHOWN=$a_; }
  return 0
}

# For $PROP_ALLOWED: "<src> <any|none|list>", at most 91 bytes (PROP_VALUE_MAX); a list that doesn't
# fit (long RADIO_ALLOW) ends with "+<n>" for the addresses left out.
allowed_value() {
  out_="$FW_SRC"; n_=0
  [ -n "$FW_EFF" ] || { echo "$out_ none"; return; }
  for t_ in $FW_EFF; do
    if [ $n_ -gt 0 ] || [ $(( ${#out_} + ${#t_} + 1 + 4 )) -gt 91 ]; then n_=$((n_+1)); else out_="$out_ $t_"; fi
  done
  [ $n_ -gt 0 ] && out_="$out_ +$n_"
  echo "$out_"
}

fw_off() {
  [ "$FW_STATE" = clear ] && return 0
  fw_clear; FW_STATE=clear; FW_EFF=""
  setprop $PROP_ALLOWED ""; FW_ALLOWED_SHOWN=""
}

# --- our processes ---------------------------------------------------------------------------
BRIDGE_PID=""; BRIDGE_RANGE=""; OTBR_PID=""; JOINED=""; FAILS=0
ZB_PID=""; ZB_SERVED=""; ZB_DEATHS=0

# The zigbeed process the bridge serves: ours (Silicon Labs) or Aqara's (under zigbee_zigbeed).
zb_pid() {
  if [ "$ZRUN" = sisdk ]; then alive "$ZB_PID" && echo "$ZB_PID"
  else p_=$(pidof zigbeed) && echo "${p_%% *}"; fi
}

# zigbeed is up and holds its end of the pty pair (it opens it a few seconds after start).
zigbeed_ready() {
  p=$(zb_pid) || return 1
  t=$(readlink $ZIGBEED_PTY) || return 1
  ls -l /proc/$p/fd 2>/dev/null | grep -q -- "-> $t\$"
}

# A fresh zigbeed writes an RSTACK (ASH "reset done") to the pty as it starts. Nobody reads it, so it
# waits in $EZSP_PTY for the first client: Zigbee2MQTT's ember adapter takes it for the answer to its
# own RST, sees zigbeed's real RSTACK ~4.3 s later as an NCP crash and fails to start (found on the
# panel, docs/research/zigbee2mqtt.md). bellows and herdsman's ezsp adapter only log CRC errors. So,
# before the bridge serves anyone after a zigbeed (re)start: read the pty until it has been quiet for
# a second after the first bytes (at most ~8 s when nothing comes).
drain_pty() {
  i_=0; got_=0
  while [ $i_ -lt 8 ]; do
    i_=$((i_ + 1))
    n_=$(timeout 3 /vendor/bin/socat -u -T 1 FILE:$EZSP_PTY,raw,echo=0 - 2>/dev/null | wc -c)
    n_=${n_##* }
    if [ "${n_:-0}" -gt 0 ]; then got_=$((got_ + n_)); elif [ $got_ -gt 0 ]; then break; fi
  done
  say "drained $got_ stale byte(s) from $EZSP_PTY before serving clients"
}
# Drain on connect: every client session runs $RELAY (socat EXEC; a root-only copy rewritten at each
# start), which first reads and drops what is already waiting in the pty, then relays. A client that
# goes away leaves zigbeed's ASH layer to time out and write an ERROR frame to the pty, which would
# otherwise greet the next client (Zigbee2MQTT: "CRC error", then ERROR_EXCEEDED_MAXIMUM_ACK_TIMEOUT_COUNT;
# ZHA after a Home Assistant restart alike). The client's own first bytes wait in the socket meanwhile:
# the relay only reads them once the drain is done. The drain ends after $RELAY_QUIET s without a byte,
# at most 2 s (timeout) if zigbeed kept talking. /system/bin/sh runs it (0600, no exec bit needed):
# Android has no /bin/sh, so socat's SYSTEM: address would not work.
RELAY=$DIR/ezsp-relay.sh
RELAY_QUIET=0.2
write_relay() { # (stdin from /dev/null for the drain: the client's bytes must stay in the socket)
  rm -f "$RELAY.tmp"
  ( umask 077
    { echo '#!/system/bin/sh'
      echo "# Written by s1kiosk_radio.sh at every start; one EZSP client session of the bridge (drain, relay)."
      echo "timeout 2 /vendor/bin/socat -u -T $RELAY_QUIET FILE:$EZSP_PTY,raw,echo=0 - </dev/null >/dev/null 2>&1"
      echo "exec /vendor/bin/socat - FILE:$EZSP_PTY,raw,echo=0"
    } > "$RELAY.tmp" ) && mv -f "$RELAY.tmp" "$RELAY"
}
# Aqara's socat is its own build: the relay needs its EXEC address. Without it (never seen, but a
# Zigbee bridge that can't start would be worse) the bridge relays straight to the pty as before v4.
RELAY_ADDR="FILE:$EZSP_PTY,raw,echo=0"
if /vendor/bin/socat -h 2>/dev/null | grep -q '^ *exec:'; then
  write_relay && RELAY_ADDR="EXEC:/system/bin/sh $RELAY"
else
  say "this socat has no EXEC address: no drain on connect"
fi

# The bridge has a connected client (socat forks one child per connection).
bridge_busy() { alive "$BRIDGE_PID" && pgrep -P "$BRIDGE_PID" >/dev/null 2>&1; }
# Called once zigbeed is ready: a new zigbeed process gets its boot bytes drained first. A connected
# client keeps its session (it is mid-conversation with the new process anyway and will reset).
zb_fresh() {
  p_=$(zb_pid)
  [ "$p_" = "$ZB_SERVED" ] && return 0
  if alive "$BRIDGE_PID" && ! bridge_busy; then kill "$BRIDGE_PID"; BRIDGE_PID=""; fi
  alive "$BRIDGE_PID" || drain_pty
  ZB_SERVED=$p_
}

# One EZSP host at a time (max-children=1); each connection reopens the pty (in $RELAY, after the
# drain), so a new client works even after Aqara's watchdog recreated the pty pair. TCP keepalive frees
# the slot within ~1 min when a client vanished without closing (HA host crash, Wi-Fi drop).
start_bridge() { # listen-address-option port range
  wait_for $EZSP_PTY 30 || { status error "$EZSP_PTY missing (zigbee_socat not up)"; return 1; }
  r=""; [ -n "$3" ] && r=",range=$3"
  /vendor/bin/socat TCP-LISTEN:$2,reuseaddr,fork,max-children=1,keepalive,keepidle=30,keepintvl=10,keepcnt=3$1$r \
    "$RELAY_ADDR" &
  BRIDGE_PID=$!; BRIDGE_RANGE=$3
}
coordinator_bridge() {
  zb_fresh
  r=$(bridge_range)
  if alive "$BRIDGE_PID" && [ "$r" != "$BRIDGE_RANGE" ]; then
    say "client range changed: '${BRIDGE_RANGE:-any}' -> '${r:-any}'"; kill "$BRIDGE_PID"; BRIDGE_PID=""
  fi
  alive "$BRIDGE_PID" || start_bridge "" "$ZIGBEE_PORT" "$r"
}

# A copy of a zigbeed NVM (network + keys) whenever it changed; the last 3 per stack are kept, other
# files in zigbee-nvm/ (manual backups) are never touched. Root-only, never leaves $DIR.
backup_nvm() { # nvm-file prefix
  [ -s "$1" ] || return 0
  mkdir -p "$DIR/zigbee-nvm" && chmod 700 "$DIR/zigbee-nvm"
  last=$(ls "$DIR/zigbee-nvm" 2>/dev/null | grep "^$2-.*\.nvm\$" | tail -1)
  [ -n "$last" ] && cmp -s "$1" "$DIR/zigbee-nvm/$last" && return 0
  cp "$1" "$DIR/zigbee-nvm/$2-$(date +%Y%m%d-%H%M%S).nvm" && chmod 600 "$DIR"/zigbee-nvm/*
  set -- $(ls "$DIR/zigbee-nvm" | grep "^$2-.*\.nvm\$"); while [ $# -gt 3 ]; do rm -f "$DIR/zigbee-nvm/$1"; shift; done
}
router_bridge() {
  zb_fresh
  alive "$BRIDGE_PID" && return 0
  backup_nvm "$ZIGBEED_NVM" zigbeed
  start_bridge ",bind=127.0.0.1" "$ROUTER_PORT" ""
}

# Silicon Labs' zigbeed 8.2.2 through the bundle's glibc runtime (sisdk/zigbeed.sh), on spinel IID 1
# and Aqara's pty pair, with its own NVM in $SISDK_STATE.
start_sisdk() {
  wait_for $CPC_SOCK 30 || { status error "cpcd socket missing"; return 1; }
  sleep 5   # cpcd needs ~4 s to reach the secondary after a (re)start (see start_otbr)
  mkdir -p "$SISDK_STATE" && chmod 700 "$SISDK_STATE"
  backup_nvm "$SISDK_STATE/host_token.nvm" sisdk
  # shellcheck disable=SC2086
  ZIGBEED_BIN=$SISDK_BIN ZIGBEED_STATE=$SISDK_STATE /system/bin/sh "$SISDK_DIR/zigbeed.sh" \
    -r "$ZIGBEED_URL" -p "$ZIGBEED_PTY" $ZIGBEED_ARGS >/dev/null 2>&1 &
  ZB_PID=$!; ZB_SERVED=""
  say "started Silicon Labs zigbeed $SISDK_VERSION (pid $ZB_PID)"
}
sisdk_ok() { # zigbee_tcp with the Silicon Labs stack: 0 = zigbeed is (being) run, 1 = status set
  take_sisdk; zstate
  [ -n "$SISDK_DIR" ] || { status error "the Silicon Labs Zigbee stack needs a radio bundle with its runtime (update the app)"; return 1; }
  [ -x "$SISDK_BIN" ] || return 0     # not_ready: waiting for the app's download
  if ! alive "$ZB_PID"; then
    [ -n "$ZB_PID" ] && { ZB_DEATHS=$((ZB_DEATHS + 1)); say "Silicon Labs zigbeed exited ($ZB_DEATHS)"; }
    [ $ZB_DEATHS -gt 10 ] && { status error "Silicon Labs zigbeed keeps exiting (try the Aqara stack)"; return 1; }
    start_sisdk || return 1
  fi
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
  take_app_file "${APP_DATASET#"$APP_DATA"/files/}" max:1024 "$DIR/thread-dataset.in" move; r_=$?
  [ $r_ = 1 ] && return 0
  [ $r_ = 2 ] && { say "ignoring app dataset hand-off ($WHY)"; return 0; }
  d=$(tr -d ' \r\n' < "$DIR/thread-dataset.in"); rm -f "$DIR/thread-dataset.in"
  case "$d" in ""|*[!0-9a-fA-F]*) say "ignoring app dataset hand-off (not hex TLVs)"; return 0 ;; esac
  [ $(( ${#d} % 2 )) -eq 0 ] || { say "ignoring app dataset hand-off (odd length)"; return 0; }
  ( umask 077; echo "$d" > "$DIR/thread-dataset" ); JOINED=""
  say "new Thread dataset from the app"  # never log the dataset itself
  thread_stored
  # a running agent may be attached elsewhere: restart it, the next pass joins with the new dataset
  alive "$OTBR_PID" && { kill "$OTBR_PID"; OTBR_PID=""; }
}
# "Leave Thread network" (v4, any mode, Off included): the app writes files/radio/thread-leave; the
# helper detaches a running router, then deletes the dataset it was given ($DIR/thread-dataset, the
# network key) and the router's OpenThread settings ($DIR/thread-router). The border-router mode's own
# network ($DIR/thread) is not touched. Result in sys.s1kiosk.radio.thread_leave = "<ok|error> <epoch>: ...".
thread_leave() {
  take_app_file "$APP_THREAD_LEAVE" max:64 "$DIR/thread-leave.req" move; r_=$?
  [ $r_ = 1 ] && return 0
  rm -f "$DIR/thread-leave.req"
  [ $r_ = 2 ] && { setp sys.s1kiosk.radio.thread_leave "error $(date +%s): $WHY"; return 0; }
  [ $(( $(date +%s) - TAKE_MTIME )) -le 120 ] || { setp sys.s1kiosk.radio.thread_leave "error $(date +%s): request too old, ignored"; return 0; }
  if [ "$MODE" = thread_router ] && alive "$OTBR_PID"; then
    timeout 10 "$(bin ot-ctl)" detach >/dev/null 2>&1   # tell the network we're going (best effort)
    ot thread stop >/dev/null 2>&1; ot ifconfig down >/dev/null 2>&1
    kill "$OTBR_PID"; i_=0; while alive "$OTBR_PID" && [ $i_ -lt 50 ]; do sleep 0.1; i_=$((i_ + 1)); done
    alive "$OTBR_PID" && kill -9 "$OTBR_PID"
    OTBR_PID=""
  fi
  had_=""; [ -e "$DIR/thread-dataset" ] && had_=1
  rm -f "$DIR/thread-dataset" "$DIR/thread-dataset.in"
  # our own root dir; a symlink there (only root could make one) is removed, never followed
  if [ -L "$DIR/thread-router" ]; then rm -f "$DIR/thread-router"; elif [ -e "$DIR/thread-router" ]; then rm -rf "$DIR/thread-router"; had_=1; fi
  JOINED=""
  if [ -e "$DIR/thread-dataset" ] || [ -e "$DIR/thread-router" ]; then
    setp sys.s1kiosk.radio.thread_leave "error $(date +%s): could not delete the Thread settings"; return 0
  fi
  if [ -n "$had_" ]; then m_="Thread dataset and router settings deleted"; else m_="no Thread network was stored"; fi
  setp sys.s1kiosk.radio.thread_leave "ok $(date +%s): $m_"
  say "Thread network left: dataset and router settings deleted"   # never the dataset itself
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

# --- radio firmware ------------------------------------------------------------------------------
# Only ever on an explicit request from the app (fw-request, at most 2 minutes old), only images whose
# hash the request names, only app-area GBLs, only with Aqara's own cpcd (its reset pin is inverted
# compared with upstream cpcd; docs/radio.md "Flash path, verified"), and only while a stock image to
# roll back to is on /vendor.
fwstat() { setp sys.s1kiosk.radio.fw "$*"; say "firmware: $*"; }
fwdone() { fwstat "$*"; echo "$*" > "$FW_DIR/last"; }   # a final outcome: shown again after restarts

# gbl_ok <file>: a Gecko bootloader image that can only write the application area: tags we know,
# no bootloader or Secure Engine upgrade, no encryption, ending with the end tag exactly at EOF.
gbl_ok() {
  g_=$1; sz_=$(stat -c %s "$g_" 2>/dev/null)
  { [ -n "$sz_" ] && [ "$sz_" -ge 32 ] && [ "$sz_" -le 1048576 ]; } || { WHY="not a radio image (size)"; return 1; }
  off_=0; end_=""
  while [ $off_ -lt "$sz_" ]; do
    # tag id and length, little-endian u32 (od prints host order; this panel is little-endian)
    set -- $(od -A n -t x4 -j $off_ -N 8 "$g_")
    [ $# = 2 ] || { WHY="truncated GBL tag"; return 1; }
    if [ $off_ = 0 ] && [ "$1" != 03a617eb ]; then WHY="not a GBL file"; return 1; fi
    case "$1" in
      03a617eb|f40a0af4|f60808f6|fe0101fe|fd0303fd|fd0505fd|fd0707fd|76a617eb|f70a0af7|f30b0bf3) ;;
      fc0404fc) end_=1 ;;
      f50909f5) WHY="the image would replace the bootloader"; return 1 ;;
      5ea617eb) WHY="the image would upgrade the Secure Engine"; return 1 ;;
      *) WHY="unknown or encrypted GBL tag 0x$1"; return 1 ;;
    esac
    l_=$((0x$2))   # mksh arithmetic is 32-bit signed: 0xfffffff8 is -8, which would loop forever
    { [ "$l_" -ge 0 ] && [ "$l_" -le $((sz_ - off_ - 8)) ]; } || { WHY="GBL tag length out of range"; return 1; }
    off_=$((off_ + 8 + l_))
    [ -n "$end_" ] && break
  done
  { [ -n "$end_" ] && [ $off_ = "$sz_" ]; } || { WHY="GBL does not end with its end tag"; return 1; }
}

# fw_resolve <id>: FW_FILE, FW_SHA, FW_APP (the CPC "Secondary APP" version it reports), FW_LABEL.
fw_resolve() {
  FW_FILE=""; FW_SHA=""; FW_APP=""; FW_LABEL=""
  if [ "$1" = stock ]; then
    # shellcheck disable=SC2086
    set -- $STOCK_GLOB
    { [ $# = 1 ] && [ -f "$1" ]; } || { WHY="no single stock radio image in /vendor/firmware"; return 1; }
    FW_FILE=$1; FW_SHA=$(sha256 "$1")
    for k_ in $KNOWN_STOCK; do [ "${k_%%:*}" = "$FW_SHA" ] && FW_APP=${k_#*:}; done
    if [ -n "$FW_APP" ]; then FW_LABEL="Aqara stock"
    else # an Aqara image we haven't seen: its file name carries the version (lichun#30000000#<n>#...)
      n_=${FW_FILE#*#30000000#}; n_=${n_%%#*}
      case "$n_" in ""|*[!0-9]*) WHY="stock image name without a version"; return 1 ;; esac
      FW_APP=$(printf '%04d' "$n_"); FW_LABEL="Aqara stock (unpinned)"
    fi
  else
    [ -n "$RBUNDLE" ] && [ -f "$RBUNDLE/manifest" ] || { WHY="no radio bundle"; return 1; }
    l_=$(grep "^firmware $1 " "$RBUNDLE/manifest" | head -1)
    [ -n "$l_" ] || { WHY="the radio bundle has no firmware '$1'"; return 1; }
    set -f; set -- $l_; set +f
    FW_FILE=$RBUNDLE/$3; FW_APP=$4; shift 4; FW_LABEL="$*"
    FW_SHA=$(sha256 "$FW_FILE")
    # the loader checked every file at start; check this one again right before use
    grep -q "^file [0-7]* [0-9]* $FW_SHA ${FW_FILE#"$RBUNDLE"/}\$" "$RBUNDLE/manifest" || { WHY="image does not match the signed manifest"; return 1; }
  fi
  gbl_ok "$FW_FILE"
}

fw_targets() { # sys.s1kiosk.radio.fw_targets: "<id>:<sha12>:<app> ..." (stock first)
  t_=""
  fw_resolve stock && t_="stock:$(echo "$FW_SHA" | cut -c 1-12):$FW_APP"
  if [ -n "$RBUNDLE" ] && [ -f "$RBUNDLE/manifest" ]; then
    for i_ in $(sed -n 's/^firmware \([a-z0-9-]*\) .*/\1/p' "$RBUNDLE/manifest"); do
      fw_resolve "$i_" && t_="$t_ $i_:$(echo "$FW_SHA" | cut -c 1-12):$FW_APP"
    done
  fi
  setp sys.s1kiosk.radio.fw_targets "${t_# }"
}

# The radio's application version as Aqara's cpcd reads it over CPC (normal config, nothing written:
# every cpcd start soft-resets the RCP). Needs the SPI bus free (no cpcd running).
fw_read_app() {
  timeout 30 /vendor/bin/cpcd -c /etc/cpcd.conf -p 2>&1 | tr -d '\r' |
    sed -n 's/.*Secondary APP v\([0-9A-Za-z._-]*\).*/\1/p' | tail -1
}

# How far cpcd -f is: the last "Info : progress:<n>" line it printed (0-100), or nothing when it printed
# none yet (or its output is still buffered). sys.zb_ota_progress is only set by Aqara's OTA wrapper.
fw_progress() {
  { tr -d '\r' < "$1"; } 2>/dev/null | sed -n 's/.*progress:[ ]*\([0-9][0-9]*\).*/\1/p' | tail -1 |
    { read -r n_ && [ "$n_" -le 100 ] 2>/dev/null && echo "$n_"; }
}

fw_flash_one() { # file id -> 0 if cpcd reports success
  ( umask 077; : > "$FW_DIR/flash.log" )
  /vendor/bin/cpcd -c /etc/cpcd_ota.conf -f "$1" >> "$FW_DIR/flash.log" 2>&1 &
  p_=$!; t0_=$(date +%s); t_=0
  while alive $p_; do
    sleep 2; t_=$(( $(date +%s) - t0_ ))
    fw_guard
    # a percentage only when cpcd said one; otherwise the time it has been flashing
    if pc_=$(fw_progress "$FW_DIR/flash.log"); then d_="$pc_%"; else d_="$t_ s"; fi
    fwstat "flashing $2: $d_ (do not unplug the panel)"
    [ $t_ -ge 240 ] && { kill $p_; WHY="cpcd timed out"; return 1; }
  done
  wait $p_; r_=$?
  grep -q "Firmware upgrade successful" "$FW_DIR/flash.log" && [ $r_ = 0 ] && return 0
  WHY="cpcd: $(tr -d '\r' < "$FW_DIR/flash.log" | grep -v '^ *$' | tail -1 | sed 's/^\[[^]]*\] //' | cut -c 1-50)"
  return 1
}

# After a flash: the bootloader starts the new app by itself. Its version must be the one expected;
# the spinel version (otbr-agent --radio-version on IID 2) is recorded when an otbr-agent is there.
fw_verify() { # expected-app -> 0 if it matches; FW_GOT, FW_SPINEL
  FW_GOT=""; FW_SPINEL=""
  for i_ in 1 2 3; do
    sleep 3; FW_GOT=$(fw_read_app); [ "$FW_GOT" = "$1" ] && break
  done
  [ "$FW_GOT" = "$1" ] || { WHY="radio reports APP ${FW_GOT:-nothing}, not $1"; return 1; }
  if [ -x "$(bin otbr-agent)" ] && svc zigbee_cpcd running && wait_for $CPC_SOCK 30; then
    sleep 5
    FW_SPINEL=$(timeout 30 "$(bin otbr-agent)" -s -v --radio-version "$RADIO_URL" 2>/dev/null | tr -d '\r' | tail -1 | cut -c 1-60)
  fi
  svc zigbee_cpcd stopped
  return 0
}

stop_children() {
  for p_ in $BRIDGE_PID $OTBR_PID $ZB_PID; do alive "$p_" && kill "$p_"; done
  sleep 1
  for p_ in $BRIDGE_PID $OTBR_PID $ZB_PID; do alive "$p_" && kill -9 "$p_"; done
  BRIDGE_PID=""; OTBR_PID=""; ZB_PID=""
}

# Before a flash: every radio process and service stopped, and nothing else holding the radio: Aqara's
# boot sequence (zigbee_cpcd_factory -> sys.gateway.config=10) and its watchdog (=11) can start
# zigbee_cpcd at any time, and its OTA service runs a cpcd of its own. 0 = quiet, else WHY.
fw_quiet() {
  stop_children; WANT=""; enforce
  i_=0
  while [ $i_ -lt 15 ]; do
    busy_=""
    for s_ in $STOP_ORDER start_rcp_ota zigbee_cpcd_factory; do
      case "$(svc_state $s_)" in running|restarting) busy_=$s_; setprop ctl.stop $s_ ;; esac
    done
    [ -z "$busy_" ] && pidof cpcd >/dev/null && busy_="a cpcd process"
    [ -z "$busy_" ] && return 0
    sleep 1; i_=$((i_ + 1))
  done
  WHY="$busy_ is still using the radio (sys.gateway.config=$(getprop sys.gateway.config))"; return 1
}
# While cpcd -f runs (every 2 s): anything of Aqara's that starts gets stopped again. Our cpcd holds the
# SPI device's lock, so a late zigbee_cpcd can't disturb the transfer; it would only restart in a loop.
fw_guard() {
  for s_ in $STOP_ORDER start_rcp_ota; do
    case "$(svc_state $s_)" in running|restarting) setprop ctl.stop $s_; say "firmware: stopped $s_ during the flash" ;; esac
  done
}

fw_attempt() { # id -> flash it (2 tries) and verify; FW_* from fw_resolve
  for a_ in 1 2; do
    fwstat "flashing $1: try $a_ (do not unplug the panel)"
    fw_flash_one "$FW_FILE" "$1" && fw_verify "$FW_APP" && return 0
    say "firmware: $1 try $a_ failed: $WHY"
  done
  return 1
}

fw_do() { # id sha-prefix: the whole explicit operation; the helper restarts afterwards
  id_=$1
  fw_resolve "$id_" || { fwstat "error $id_: $WHY"; return 0; }
  case "$FW_SHA" in "$2"*) ;; *) fwstat "error $id_: the image is not the one requested (${2})"; return 0 ;; esac
  T_FILE=$FW_FILE; T_APP=$FW_APP; T_SHA=$FW_SHA
  # The rollback target must exist and check out before anything else happens.
  fw_resolve stock || { fwstat "error $id_: no stock image to roll back to ($WHY)"; return 0; }
  echo "stock $FW_SHA $FW_APP $FW_FILE" > "$FW_DIR/stock.ref"
  FW_FILE=$T_FILE; FW_APP=$T_APP; FW_SHA=$T_SHA

  fwstat "flashing $id_: stopping the radio"
  status applying "radio firmware update ($id_)"
  fw_quiet || { fwstat "error $id_: $WHY"; WANT=$MODE_WANT; return 0; }
  before=$(fw_read_app)
  say "firmware: radio reports APP '${before:-nothing}' before flashing $id_ (APP $FW_APP, sha256 $FW_SHA)"
  echo "$id_ $FW_SHA $(date +%s)" > "$FW_DIR/in-progress"
  if fw_attempt "$id_"; then
    rm -f "$FW_DIR/in-progress" "$FW_DIR/restore-tries"
    echo "$id_ $FW_APP $FW_SPINEL" > "$FW_DIR/current"
    fwdone "ok $id_: APP $FW_APP${FW_SPINEL:+ ($FW_SPINEL)}"
  else
    why_=$WHY
    if [ "$id_" != stock ] && fw_resolve stock; then
      fwstat "flashing stock: $id_ failed ($why_), rolling back"
      if fw_attempt stock; then
        rm -f "$FW_DIR/in-progress" "$FW_DIR/restore-tries"
        echo "stock $FW_APP $FW_SPINEL" > "$FW_DIR/current"
        fwdone "error $id_: $why_; rolled back to stock (APP $FW_APP)"
      else
        fwdone "error $id_: $why_; ROLLBACK FAILED ($WHY): flash Aqara stock again"
      fi
    else
      fwdone "error $id_: $why_"
    fi
  fi
  # Start over cleanly (children are gone; the loop state belongs to the old services).
  say "firmware: restarting the helper"
  tz_stop
  exec /system/bin/sh "$0"
}

# Every loop: an explicit request from the app?
fw_request() {
  # --move: taken (and removed through the verified directory) in one step, never acted on twice
  take_app_file "$APP_FW_REQ" max:128 "$FW_DIR/request" move; r_=$?
  [ $r_ = 1 ] && return 0
  [ $r_ = 2 ] && { fwstat "error request: $WHY"; return 0; }
  age_=$(( $(date +%s) - TAKE_MTIME ))
  [ $age_ -le 120 ] || { fwstat "error request: too old (${age_} s), ignored"; return 0; }
  read -r id_ sha_ rest_ < "$FW_DIR/request"; rm -f "$FW_DIR/request"
  case "$id_" in ""|*[!a-z0-9-]*) fwstat "error request: bad image id"; return 0 ;; esac
  case "$sha_" in *[!0-9a-f]*) sha_="" ;; esac
  [ ${#sha_} -ge 12 ] || { fwstat "error $id_: the request names no image hash"; return 0; }
  fw_do "$id_" "$sha_"
}

# At start: a flash that was interrupted (the helper was killed, the panel lost power) may have left
# the radio without an application. Then the mode's stack must not start; instead Aqara's stock image
# is flashed back (at most twice across restarts), and if that fails too the radio is held off until
# the owner flashes it from Home Assistant.
FW_HOLD=""
fw_recover() {
  read -r i_ rest_ < "$FW_DIR/in-progress"
  tries_=$(cat "$FW_DIR/restore-tries" 2>/dev/null); case "$tries_" in ''|*[!0-9]*) tries_=0 ;; esac
  if [ "$tries_" -lt 2 ] && fw_resolve stock; then   # (fw_resolve uses n_ itself)
    echo $((tries_ + 1)) > "$FW_DIR/restore-tries"
    fwstat "flashing stock: the flash of $i_ was interrupted, restoring Aqara's stock image"
    status applying "restoring the radio's stock firmware"
    if fw_quiet && fw_attempt stock; then
      rm -f "$FW_DIR/in-progress" "$FW_DIR/restore-tries"
      echo "stock $FW_APP $FW_SPINEL" > "$FW_DIR/current"
      fwdone "error $i_: the flash was interrupted; restored stock (APP $FW_APP)"
      say "firmware: restarting the helper"
      tz_stop
      exec /system/bin/sh "$0"
    fi
  fi
  FW_HOLD=1
  fwstat "error $i_: interrupted flash, radio held off; flash Aqara stock from Home Assistant"
}

fw_init() { # status at start: an interrupted flash, the last verified version, or idle
  mkdir -p "$FW_DIR" && chmod 700 "$FW_DIR"
  if [ -s "$FW_DIR/in-progress" ]; then
    fw_recover
  elif [ -s "$FW_DIR/last" ]; then
    fwstat "$(head -1 "$FW_DIR/last")"
  else
    fwstat "idle stock: never flashed by the kiosk"
  fi
  fw_targets
}

children_ok() {
  case "$MODE" in
    off)               br_net_off; fw_off ;;
    zigbee_tcp)        br_net_off; fw_apply || return 1
                       if [ "$ZRUN" = sisdk ]; then sisdk_ok || return 1; fi
                       zigbeed_ready && { coordinator_bridge || return 1; } ;;
    zigbee_router)     br_net_off; fw_apply || return 1
                       zigbeed_ready && { router_bridge || return 1; } ;;
    thread)            fw_apply || return 1
                       alive "$OTBR_PID"   || start_thread_br || return 1
                       [ "$OTBR_KIND" = br ] && br_net_on ;;
    thread_router)     br_net_off; fw_apply || return 1; take_app_dataset
                       alive "$OTBR_PID"   || start_thread_router || return 1 ;;
    zigbee_tcp_thread) fw_apply || return 1
                       zigbeed_ready && { coordinator_bridge || return 1; }
                       alive "$OTBR_PID"   || start_otbr "$DIR/thread" || return 1 ;;
  esac
  return 0
}

# While the mode's stack isn't usable yet: prints why and returns 0. Ready: returns 1.
not_ready() {
  case "$MODE" in
    zigbee_tcp|zigbee_router|zigbee_tcp_thread)
      if [ "$ZRUN" = sisdk ] && [ ! -x "$SISDK_BIN" ]; then
        echo "waiting for zigbeed $SISDK_VERSION (the app downloads it from Silicon Labs)"; return 0
      fi
      zigbeed_ready || { echo "waiting for zigbeed"; return 0; }
      alive "$BRIDGE_PID" || { echo "waiting for the EZSP bridge"; return 0; } ;;
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
      r=$BRIDGE_RANGE
      if [ "$ZRUN" = sisdk ]; then s="EZSP 18 on tcp:$ZIGBEE_PORT, Silicon Labs zigbeed $SISDK_VERSION"
      else s="EZSP on tcp:$ZIGBEE_PORT"; fi
      echo "$s${r:+ (clients from $r)}" ;;
    zigbee_router) echo "EZSP on 127.0.0.1:$ROUTER_PORT for the router host" ;;
    thread)
      if [ "$OTBR_KIND" = br ]; then
        echo "border router on wpan0, backbone $BACKBONE, REST :$REST_PORT ($(ot state | head -1))"
      else
        echo "otbr-agent on wpan0, agent build without border routing ($(ot state | head -1))"
      fi ;;
    thread_router)
      # The role and the network's name only (the app says them plainly): never the key, PSKc or ids.
      if [ -s "$DIR/thread-dataset" ]; then
        r_=$(ot state | head -1)
        n_=$(ot networkname | head -1 | tr -cd 'A-Za-z0-9 ._+-' | cut -c1-16)
        w_=""; [ -n "$n_" ] && w_=", network \"$n_\""
        case "$r_" in
          router|leader|child) echo "joined as $r_$w_" ;;
          detached)            echo "detached: looking for network${n_:+ \"$n_\"}" ;;
          disabled|"")         echo "starting Thread$w_" ;;
          *)                   echo "Thread state $(printf '%s' "$r_" | tr -cd 'a-z')$w_" ;;
        esac
      else echo "ready to join: waiting for a Thread dataset from Home Assistant"; fi ;;
    zigbee_tcp_thread) echo "EZSP on tcp:$ZIGBEE_PORT; otbr-agent on wpan0" ;;
  esac
}

# A Thread router network is stored (what "Leave Thread network" deletes), for the app's Leave
# button in every mode: sys.s1kiosk.radio.thread_stored = yes|no. Only whether, never what.
thread_stored() {
  if [ -s "$DIR/thread-dataset" ] || [ -e "$DIR/thread-router" ]; then t_=yes; else t_=no; fi
  [ "$t_" = "$TSTORED" ] && return 0
  TSTORED=$t_; setprop sys.s1kiosk.radio.thread_stored "$t_"
}
TSTORED=""

# --- main ------------------------------------------------------------------------------------
status applying
zstate
fw_init
[ -n "$S1R_ONCE" ] && LOOPS=$S1R_ONCE   # tests: run this many loops, then exit

while true; do
  fw_request   # an explicit firmware request runs first (and restarts the helper when done)
  thread_leave # "Leave Thread network", in every mode
  thread_stored
  if [ -n "$FW_HOLD" ]; then
    # radio without a trusted application after an interrupted flash: everything stays off
    WANT=""; enforce; br_net_off; fw_off
    status error "radio firmware missing after an interrupted flash: flash Aqara stock"
    if [ -n "$LOOPS" ]; then LOOPS=$((LOOPS - 1)); [ $LOOPS -le 0 ] && { tz_stop; exit 0; }; fi
    sleep ${S1R_SLEEP:-3}; continue
  fi
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
        ZB_DEATHS=0
      fi
    else
      FAILS=$((FAILS+1)) # start_* set the status
    fi
  else
    FAILS=$((FAILS+1))
    status error "services not in wanted state:$bad"
  fi
  if [ $FAILS -gt 20 ]; then status error "giving up after repeated failures"; exec sleep 2147483647; fi
  if [ -n "$LOOPS" ]; then LOOPS=$((LOOPS - 1)); [ $LOOPS -le 0 ] && { tz_stop; exit 0; }; fi
  sleep ${S1R_SLEEP:-3}
done
