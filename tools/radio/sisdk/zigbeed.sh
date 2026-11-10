#!/system/bin/sh
# Runs Silicon Labs' zigbeed (a glibc armhf binary) on Android through the glibc runtime next to this
# script (lib/, from tools/radio/build-sisdk-runtime.sh). Part of the radio bundle as sisdk/zigbeed.sh;
# the radio helper starts it in zigbee_tcp mode with the Silicon Labs stack. Args are zigbeed's own:
#   zigbeed.sh -r 'spinel+cpc://cpcd_0?iid=1&iid-list=0' -p /tmp/ttyZigbeeNCP [-v 1 -d 3]
# Environment:
#   ZIGBEED_BIN    the zigbeed binary (default bin/zigbeed next to this script). The radio bundle never
#                  contains it: it is Silicon Labs MSLA object code that the panel downloads from Silicon
#                  Labs' own release, and the helper installs only the pinned SHA-256.
#   ZIGBEED_STATE  working dir; zigbeed keeps its NVM in ./host_token.nvm there (default
#                  /data/s1kiosk/radio/zigbeed-sisdk). Aqara's /data/zigbee_host is never touched.
D=$(cd "${0%/*}" && pwd) || exit 1
ZB=${ZIGBEED_BIN:-$D/bin/zigbeed}
ST=${ZIGBEED_STATE:-/data/s1kiosk/radio/zigbeed-sisdk}
[ -d "$ST" ] || { mkdir -p "$ST" && chmod 700 "$ST"; }
cd "$ST" || exit 1
# -c /dev/null: no /usr/local/etc/zigbeed.conf on Android; everything comes from the command line.
# Added once only: zigbeed re-execs itself through this script (below), with the args it already has.
[ "$1" = -c ] || set -- -c /dev/null "$@"
# --argv0: on every ASH reset (each EZSP host connect) zigbeed longjmps back into main(), prints
# "Restarting" and calls execvp(argv[0], argv). argv[0] would be the zigbeed binary, whose ELF
# interpreter /lib/ld-linux-armhf.so.3 doesn't exist on Android: execvp fails, zigbeed falls through
# and segfaults. With argv[0] = this script, the re-exec comes back here and through the bundled
# loader (same PID; the environment, ZIGBEED_BIN included, is kept).
exec "$D/lib/ld-linux-armhf.so.3" --library-path "$D/lib" --argv0 "$D/zigbeed.sh" "$ZB" "$@"
