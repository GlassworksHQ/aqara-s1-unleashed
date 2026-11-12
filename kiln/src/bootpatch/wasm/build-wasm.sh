#!/usr/bin/env bash
# Rebuild bootpatch.wasm (magiskboot v31.1's gzip/xz, see src/lib.rs) in Docker, reproducibly.
#
#   kiln/src/bootpatch/wasm/build-wasm.sh          # writes bootpatch.wasm next to this script
#   CHECK=1 kiln/src/bootpatch/wasm/build-wasm.sh  # rebuild into a temp dir and compare
#
# Pinned: the rust image by digest (Rust 1.91.1, linux/arm64), crate versions in Cargo.toml (=x.y.z)
# and Cargo.lock (--locked). Paths are remapped so the output does not depend on where it is built.
# Nothing is written into the source tree except bootpatch.wasm (the cargo target dir stays in the
# container). Rust is not needed on the host.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
IMAGE=${RUST_IMAGE:-rust:1.91@sha256:867f1d1162913c401378a8504fb17fe2032c760dc316448766f150a130204aad}
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT

docker run --rm --platform linux/arm64 \
  -v "$HERE":/src:ro -v "$OUT":/out -v s1-bootpatch-cargo-registry:/usr/local/cargo/registry \
  -e SOURCE_DATE_EPOCH=0 -e CARGO_INCREMENTAL=0 \
  -e RUSTFLAGS="--remap-path-prefix=/usr/local/cargo/registry=/cargo --remap-path-prefix=/build=/s1-bootpatch-wasm --remap-path-prefix=/usr/local/rustup=/rustup" \
  "$IMAGE" sh -euc '
  rustup target add wasm32-unknown-unknown >/dev/null 2>&1
  mkdir /build && cp -r /src/Cargo.toml /src/Cargo.lock /src/src /build/ && cd /build
  cargo build --release --locked --target wasm32-unknown-unknown -q
  cp target/wasm32-unknown-unknown/release/s1_bootpatch_wasm.wasm /out/bootpatch.wasm
  rustc -V > /out/rustc.txt'

echo "built with $(cat "$OUT/rustc.txt"): $(wc -c < "$OUT/bootpatch.wasm") bytes, sha256 $(shasum -a 256 "$OUT/bootpatch.wasm" | cut -d" " -f1)"
if [ -n "${CHECK:-}" ]; then
  cmp "$OUT/bootpatch.wasm" "$HERE/bootpatch.wasm" && echo "reproducible: identical to the committed bootpatch.wasm"
else
  cp "$OUT/bootpatch.wasm" "$HERE/bootpatch.wasm"
  echo "wrote $HERE/bootpatch.wasm"
fi
