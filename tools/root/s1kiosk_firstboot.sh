#!/system/bin/sh
# S1 Kiosk first-boot provisioning. Runs as root from the Magisk boot image (s1kiosk_firstboot.rc),
# once per panel: a marker in /data/s1kiosk stops it from undoing a later user choice.
#
# Factory firmware exposes no adb. The installer bakes the installing computer's adb public key into
# the boot image (overlay.d/sbin/s1kiosk_adb_keys); this authorizes that key and enables adb over USB,
# so tools/install.sh can continue with no screen interaction.
MARK=/data/s1kiosk/.firstboot-done
KEYS=${0%/*}/s1kiosk_adb_keys
ADB_KEYS=/data/misc/adb/adb_keys
log() { echo "s1kiosk_firstboot: $*" > /dev/kmsg; }

[ -e "$MARK" ] && exit 0
mkdir -p /data/s1kiosk && chown root:root /data/s1kiosk && chmod 700 /data/s1kiosk

if [ -s "$KEYS" ]; then
  mkdir -p /data/misc/adb
  [ -e "$ADB_KEYS" ] || : > "$ADB_KEYS"
  n=0
  while IFS= read -r k; do
    [ -n "$k" ] || continue
    grep -qxF "$k" "$ADB_KEYS" || { echo "$k" >> "$ADB_KEYS"; n=$((n + 1)); }
  done < "$KEYS"
  chown system:shell "$ADB_KEYS"; chmod 0640 "$ADB_KEYS"; restorecon "$ADB_KEYS" 2>/dev/null
  cfg=$(getprop persist.sys.usb.config)
  case ",$cfg," in
    *,adb,*) ;;
    ,,|,none,) setprop persist.sys.usb.config adb ;;
    *) setprop persist.sys.usb.config "$cfg,adb" ;;
  esac
  settings put global adb_enabled 1
  log "authorized $n new adb key(s); usb config $(getprop persist.sys.usb.config)"
else
  log "no installer adb key in the boot image; adb left as is"
fi
# A factory-reset panel boots with Wi-Fi off, and Android 10 won't let the kiosk app (targetSdk > 28)
# turn it on, so Improv (over Bluetooth or from the installer) and "Choose network" couldn't join.
# Turn it on once here; the user can still switch it off later.
if [ "$(settings get global wifi_on)" != 1 ]; then svc wifi enable && log "Wi-Fi turned on"; fi
touch "$MARK"
