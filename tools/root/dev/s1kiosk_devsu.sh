#!/system/bin/sh
# DEVELOPMENT ONLY. Grants Magisk su to adb's shell user (uid 2000) and authorizes the adb key(s) baked
# into the image, then revokes the su grant after HOURS (the sleep restarts on every boot, so it lasts
# at most HOURS after the last boot). Remove by flashing a normal boot image and running
#   magisk --sqlite "DELETE FROM policies WHERE uid=2000"
HOURS=__HOURS__
BIN=${0%/*}
log() { echo "s1kiosk_devsu: $*" > /dev/kmsg; }

# adb keys: like s1kiosk_firstboot.sh, but every boot (first boot may have run long ago)
KEYS=$BIN/s1kiosk_adb_keys ADB_KEYS=/data/misc/adb/adb_keys
if [ -s "$KEYS" ]; then
  mkdir -p /data/misc/adb; [ -e "$ADB_KEYS" ] || : > "$ADB_KEYS"
  while IFS= read -r k; do [ -n "$k" ] && ! grep -qxF "$k" "$ADB_KEYS" && echo "$k" >> "$ADB_KEYS"; done < "$KEYS"
  chown system:shell "$ADB_KEYS"; chmod 0640 "$ADB_KEYS"; restorecon "$ADB_KEYS" 2>/dev/null
fi

"$BIN/magisk" --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES(2000,2,0,1,0)" \
  && log "adb shell has root for ${HOURS} h" || log "could not grant su"
sleep $((HOURS * 3600))
"$BIN/magisk" --sqlite "DELETE FROM policies WHERE uid=2000" && log "adb shell root revoked"
