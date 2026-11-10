#!/usr/bin/env bash
# Builds tools/root/out/s1kiosk_verify: a static 32-bit ARM (armeabi-v7a userspace) Ed25519 checker
# for the boot image (overlay.d/sbin/s1kiosk_verify), used by s1kiosk_bundle.sh and the radio
# helper's loader to accept only radio bundles signed with the project's key (docs/radio.md
# "Radio bundles"). Source: tools/root/verify/s1kiosk_verify.c.
#
#   tools/root/build-verify.sh            # build + self-test (Docker, linux/arm/v7 under emulation)
#
# Third-party input, fetched at build time and hash-checked (nothing vendored):
#   Monocypher 4.0.3  https://monocypher.org/download/monocypher-4.0.3.tar.gz
#   sha256 8cc9bc341a66249016db9bd70e9142d8d0aef9945973744b1ac05dbc55d8ee66 (= GitHub's asset digest
#   for LoupVaillant/Monocypher 4.0.3; sha512 as published on monocypher.org)
#   License: CC0-1.0 or BSD-2-Clause (the binary carries the BSD notice in out/NOTICE.s1kiosk_verify).
# Toolchain: Alpine 3.20 (musl, gcc) in an arm32v7 container, so the result is static and needs nothing
# from Android. Self-test: RFC 8032 test vectors 1-3, a tampered message, a tampered signature, and a
# message signed here with OpenSSL (the same tool tools/radio/make-bundle.py uses).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out"
W="${VERIFY_BUILD:-$HERE/.build}"
MONO_VER=4.0.3
MONO_URL=https://monocypher.org/download/monocypher-$MONO_VER.tar.gz
MONO_SHA256=8cc9bc341a66249016db9bd70e9142d8d0aef9945973744b1ac05dbc55d8ee66
IMAGE=arm32v7/alpine:3.20@sha256:9c6dfd553de893ca76841b515e379350d234939c92d47f817069cb56221c3feb
OPENSSL=${OPENSSL:-openssl}

command -v docker >/dev/null || { echo "docker not found" >&2; exit 1; }
"$OPENSSL" version | grep -q '^OpenSSL 3' || { echo "need OpenSSL 3 (OPENSSL=<path>) for the self-test" >&2; exit 1; }
sha() { shasum -a 256 "$1" | awk '{print $1}'; }
mkdir -p "$W" "$OUT"
TGZ="$W/monocypher-$MONO_VER.tar.gz"
if [ ! -f "$TGZ" ] || [ "$(sha "$TGZ")" != "$MONO_SHA256" ]; then
  curl -fsSL --retry 3 -o "$TGZ.part" "$MONO_URL" && mv "$TGZ.part" "$TGZ"
fi
[ "$(sha "$TGZ")" = "$MONO_SHA256" ] || { echo "HASH MISMATCH $TGZ" >&2; exit 1; }
rm -rf "$W/src" && mkdir -p "$W/src" && tar xzf "$TGZ" -C "$W/src" --strip-components=1
cp "$HERE/verify/s1kiosk_verify.c" "$W/src/"

# A message signed with OpenSSL (Ed25519, raw input) and a throwaway key, for the self-test.
T="$W/test"; rm -rf "$T"; mkdir -p "$T"
"$OPENSSL" genpkey -algorithm ed25519 -out "$T/k.pem" 2>/dev/null
"$OPENSSL" pkey -in "$T/k.pem" -pubout -outform DER | tail -c 32 | xxd -p -c 64 > "$T/ossl.pub"
printf 'S1RADIO-BUNDLE 1\nversion 1\n' > "$T/ossl.msg"
"$OPENSSL" pkeyutl -sign -inkey "$T/k.pem" -rawin -in "$T/ossl.msg" | xxd -p -c 128 > "$T/ossl.sig"
rm -f "$T/k.pem"

docker run --rm --platform linux/arm/v7 -v "$W":/w -w /w/src "$IMAGE" sh -euc '
  apk add --no-cache gcc musl-dev binutils >/dev/null
  gcc -std=c99 -Os -static -s -Wall -Wextra -Werror -ffile-prefix-map=/w/src=. \
      -Isrc/optional -Isrc -o /w/s1kiosk_verify \
      s1kiosk_verify.c src/monocypher.c src/optional/monocypher-ed25519.c
  gcc --version | head -1 > /w/gcc-version.txt
  V=/w/s1kiosk_verify; T=/w/test; cd $T
  ok()  { "$V" "$@" 2>/dev/null || { echo "FAIL (want valid): $*"; exit 1; }; }
  bad() { set +e; "$V" "$@" 2>/dev/null; r=$?; set -e; [ $r = 1 ] || { echo "FAIL (want 1, got $r): $*"; exit 1; }; }
  err() { set +e; "$V" "$@" 2>/dev/null; r=$?; set -e; [ $r = 2 ] || { echo "FAIL (want 2, got $r): $*"; exit 1; }; }
  # RFC 8032 section 7.1, TEST 1-3
  echo d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a > t1.pub; : > t1.msg
  echo e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b > t1.sig
  echo 3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c > t2.pub; printf "\162" > t2.msg
  echo 92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00 > t2.sig
  echo fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025 > t3.pub; printf "\257\202" > t3.msg
  echo 6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a > t3.sig
  for t in t1 t2 t3; do ok $t.pub $t.msg $t.sig; done
  ok ossl.pub ossl.msg ossl.sig
  printf x >> ossl.msg; bad ossl.pub ossl.msg ossl.sig                  # tampered message
  bad t2.pub t3.msg t3.sig                                              # wrong key
  sed "s/^e/f/" t1.sig > t1x.sig; bad t1.pub t1.msg t1x.sig             # tampered signature
  echo zz > junk; err junk t1.msg t1.sig; err t1.pub t1.msg junk        # not hex
  err t1.pub /nonexistent t1.sig
  ln -s t1.msg link.msg; err t1.pub link.msg t1.sig                     # symlink refused
  head -c 1048577 /dev/zero > big.msg; err t1.pub big.msg t1.sig        # over 1 MiB
  err t1.pub t1.msg                                                     # usage
  # --copy/--move: what root services take from the kiosk (uid 10050)
  rc() { set +e; timeout 10 "$V" "$@" >/tmp/out 2>/dev/null; r=$?; set -e; echo $r; }
  want() { w=$1; shift; r=$(rc "$@"); [ "$r" = "$w" ] || { echo "FAIL (want $w, got $r): $*"; exit 1; }; }
  A=/tmp/app; rm -rf $A /tmp/d*; mkdir -p $A/files/radio; printf hello > $A/files/radio/f; chown -R 10050:10050 $A
  want 0 --copy $A files/radio/f 10050 5 /tmp/d1; [ "$(cat /tmp/d1)" = hello ] && grep -q "^5 " /tmp/out
  [ "$(stat -c %a:%u /tmp/d1)" = 600:0 ] || { echo "FAIL dest mode"; exit 1; }
  want 0 --copy $A files/radio/f 10050 max:10 /tmp/d2
  want 4 --copy $A files/radio/f 10050 4 /tmp/d3; [ ! -e /tmp/d3 ]       # wrong size
  want 4 --copy $A files/radio/f 10050 max:4 /tmp/d3
  want 4 --copy $A files/radio/f 10051 5 /tmp/d3                        # another uid
  want 3 --copy $A files/radio/nope 10050 5 /tmp/d3                     # absent
  want 3 --copy $A files/none/f 10050 5 /tmp/d3
  want 2 --copy $A files/radio/f 10050 5 /tmp/d1                        # dest exists
  ln -s /dev/zero $A/files/radio/z; want 4 --copy $A files/radio/z 10050 max:999 /tmp/d3   # symlinked file
  mkdir -p /tmp/elsewhere; printf hello > /tmp/elsewhere/f; chown -R 10050 /tmp/elsewhere
  ln -s /tmp/elsewhere $A/files/link; want 4 --copy $A files/link/f 10050 5 /tmp/d3        # symlinked dir
  mkfifo $A/files/radio/fifo; chown 10050 $A/files/radio/fifo
  want 4 --copy $A files/radio/fifo 10050 max:9 /tmp/d3                 # FIFO: refused, never blocks
  ln $A/files/radio/f $A/files/radio/hl; want 4 --copy $A files/radio/f 10050 5 /tmp/d3; rm $A/files/radio/hl
  chown 0 $A/files/radio/f; want 4 --copy $A files/radio/f 10050 5 /tmp/d3; chown 10050 $A/files/radio/f
  chown 0 $A/files/radio; want 4 --copy $A files/radio/f 10050 5 /tmp/d3; chown 10050 $A/files/radio
  want 2 --copy $A ../app/files/radio/f 10050 5 /tmp/d3                # .. refused
  want 2 --copy $A files/../files/radio/f 10050 5 /tmp/d3
  want 0 --move $A files/radio/f 10050 5 /tmp/d4; [ ! -e $A/files/radio/f ] && [ "$(cat /tmp/d4)" = hello ]
  echo "self-test passed"'

install -m 0755 "$W/s1kiosk_verify" "$OUT/s1kiosk_verify"
{
  echo "s1kiosk_verify: built $(date -u +%FT%TZ) by tools/root/build-verify.sh"
  echo "source: tools/root/verify/s1kiosk_verify.c (MIT, this repository)"
  echo "crypto: Monocypher $MONO_VER ($MONO_URL, sha256 $MONO_SHA256)"
  echo "toolchain: $IMAGE, $(cat "$W/gcc-version.txt"), static musl"
  echo "sha256 $(sha "$OUT/s1kiosk_verify")  s1kiosk_verify"
} > "$OUT/BUILD-INFO.s1kiosk_verify.txt"
{
  echo "s1kiosk_verify contains Monocypher $MONO_VER, used under the 2-clause BSD licence:"
  echo
  sed -n '/^Licence 1/,/^Licence 2/p' "$W/src/LICENCE.md" | sed '$d'
  echo "and musl libc (MIT): https://musl.libc.org/ (Alpine 3.20 package musl)."
} > "$OUT/NOTICE.s1kiosk_verify"
cat "$OUT/BUILD-INFO.s1kiosk_verify.txt"
file "$OUT/s1kiosk_verify" 2>/dev/null || true
