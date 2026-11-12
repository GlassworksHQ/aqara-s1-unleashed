#!/usr/bin/env bash
# Checks that an S1 Kiosk APK still installs *over* the app already on panels, keeping its data,
# its device-admin rights and the names the root helper and the installer rely on. Run on every PR
# (unsigned build) and on releases and test builds (signed, with --signed).
#
#   tools/release/check-update-compat.sh <apk>            identifiers only
#   tools/release/check-update-compat.sh --signed <apk>   also the release signing certificate
#
# What breaks updates in place, and is checked here:
#   - another applicationId: Android installs it as a second app, and the panel keeps the old one;
#   - a different signing key: Android refuses the update (only with --signed; PR builds are unsigned);
#   - adding android:sharedUserId: Android refuses the update;
#   - a renamed component that something outside the APK names. AdminReceiver: device admin is
#     granted to the component, so a rename silently drops it (no lockNow, no silent updates).
#     MainActivity, ImprovActivity: started by name by tools/install.sh, the web installer and the
#     docs. BootReceiver: autostart. The Kotlin package rename (Glassworks migration step 7) must keep
#     these names, e.g. by keeping these classes where they are.
# Needs aapt2 (and apksigner for --signed) from the newest Android build-tools under $ANDROID_HOME
# (or $ANDROID_SDK_ROOT, or ~/Library/Android/sdk).
set -euo pipefail

signed=false
if [ "${1:-}" = "--signed" ]; then signed=true; shift; fi
apk="${1:?usage: check-update-compat.sh [--signed] <apk>}"
[ -f "$apk" ] || { echo "no such APK: $apk" >&2; exit 2; }

HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=sh.edm.s1kiosk
COMPONENTS=(
  "$PKG.AdminReceiver"
  "$PKG.MainActivity"
  "$PKG.improv.ImprovActivity"
  "$PKG.BootReceiver"
)

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
bt="$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -n 1)"
[ -x "$bt/aapt2" ] || { echo "aapt2 not found under $sdk/build-tools" >&2; exit 2; }

fails=0
fail() { echo "::error::$*"; fails=$((fails + 1)); }

manifest="$("$bt/aapt2" dump xmltree --file AndroidManifest.xml "$apk")"
package="$(sed -n 's/^ *A: package="\([^"]*\)".*/\1/p' <<<"$manifest" | head -n 1)"
[ "$package" = "$PKG" ] || fail "applicationId is '$package', not $PKG: panels would get a second app instead of an update"
if grep -q 'sharedUserId' <<<"$manifest"; then fail "android:sharedUserId was added: Android refuses to update an app into a shared user"; fi
for c in "${COMPONENTS[@]}"; do
  grep -qF "android:name(0x01010003)=\"$c\"" <<<"$manifest" || fail "component $c is gone or renamed"
done

if $signed; then
  want="$(tr -d ' \n' < "$HERE/release-cert.sha256")"
  # Every signer's certificate, whatever apksigner calls them ("Signer #1 certificate …" in older
  # build-tools, "V2 Signer: certificate …" in newer ones). Exactly one certificate is allowed.
  got="$("$bt/apksigner" verify --print-certs "$apk" | sed -n -E 's/^.*[Ss]igner.*certificate SHA-256 digest: ([0-9a-f]{64})$/\1/p' | sort -u | tr '\n' ' ' | sed 's/ $//')"
  [ "$got" = "$want" ] || fail "signed with certificate ${got:-<none>}, not the release key $want: panels would refuse the update"
fi

if [ "$fails" -gt 0 ]; then echo "$fails problem(s): this APK would not update panels in place"; exit 1; fi
echo "ok: $PKG, ${#COMPONENTS[@]} stable components, no sharedUserId$($signed && echo ', release certificate')"
