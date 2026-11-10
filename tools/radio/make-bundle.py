#!/usr/bin/env python3
"""Build and sign an S1 Kiosk radio bundle (docs/radio.md "Radio bundles").

    tools/radio/make-bundle.py --version 30200 --key KEY.pem --out OUTDIR \\
        --file s1kiosk_radio.sh=tools/radio/s1kiosk_radio.sh:0700 \\
        --file otbr-agent=tools/radio/out/otbr-agent:0700 \\
        --dir sisdk=tools/radio/out/sisdk \\
        [--firmware ID=PATH-IN-BUNDLE:APP-VERSION:LABEL ...]

Writes OUTDIR/radio-bundle/{manifest,manifest.sig,<files>} (the layout the app ships as an APK asset
and the panel's s1kiosk_bundle.sh installs). KEY.pem is the project's Ed25519 private key (PKCS#8 PEM,
GitHub secret S1_RADIO_BUNDLE_KEY in CI); it is only read by `openssl pkeyutl -sign`. --pubkey FILE
checks the result against a public key (tools/root/s1kiosk_bundle.pub) afterwards.

The manifest format is the one s1kiosk_bundle.sh parses (its header): version must only go up, so CI
uses the app's versionCode. The script refuses to package Silicon Labs' zigbeed binary (MSLA object
code, never redistributed by this project: the panel downloads it from Silicon Labs).
Only the standard library and an OpenSSL 3 command line are needed.
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

PATH_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]*(/[A-Za-z0-9][A-Za-z0-9._+-]*){0,3}$")
MODES = {"0600", "0644", "0700", "0755"}
FW_ID_RE = re.compile(r"^[a-z0-9-]{1,16}$")
APP_RE = re.compile(r"^[A-Za-z0-9._-]{1,16}$")
# Silicon Labs zigbeed 8.2.2 armhf (tools/radio/build-zigbeed.sh): must never end up in a bundle.
MSLA_SHA256 = {"a66d45842a770e1c449a55208a6d2a3b36786eb1c94bf10d3c3aac317274e3bb"}
MAX_FILES = 200
MAX_TOTAL = 96 * 1024 * 1024


def die(msg):
    sys.exit(f"make-bundle: {msg}")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def good_path(p):
    return bool(PATH_RE.match(p)) and len(p) <= 160 and p not in ("manifest", "manifest.sig")


def is_msla(path, rel):
    if sha256(path) in MSLA_SHA256:
        return True
    # any ELF called zigbeed (a different build would still be Silicon Labs' object code)
    if os.path.basename(rel) == "zigbeed":
        with open(path, "rb") as f:
            return f.read(4) == b"\x7fELF"
    return False


def openssl():
    for c in (os.environ.get("OPENSSL"), "openssl", "/opt/local/bin/openssl", "/opt/homebrew/opt/openssl@3/bin/openssl"):
        if not c:
            continue
        try:
            out = subprocess.run([c, "version"], capture_output=True, text=True, check=True).stdout
        except (OSError, subprocess.CalledProcessError):
            continue
        if out.startswith("OpenSSL 3"):
            return c
    die("need an OpenSSL 3 command line (set OPENSSL=<path>)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", required=True, type=int)
    ap.add_argument("--app-version", help="the APK's MAJOR.MINOR.PATCH: the bundle version must not exceed its versionCode")
    ap.add_argument("--force", action="store_true", help="skip the --app-version check (tests, emergencies)")
    ap.add_argument("--key", required=True, help="Ed25519 private key, PKCS#8 PEM")
    ap.add_argument("--out", required=True)
    ap.add_argument("--file", action="append", default=[], help="BUNDLE-PATH=SOURCE[:MODE]")
    ap.add_argument("--dir", action="append", default=[], help="BUNDLE-PREFIX=SOURCE-DIR (modes kept: 0755 if executable else 0644)")
    ap.add_argument("--firmware", action="append", default=[], help="ID=PATH-IN-BUNDLE:APP-VERSION:LABEL")
    ap.add_argument("--created", default=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    ap.add_argument("--pubkey", help="verify the signature with this public key file (64 hex digits)")
    a = ap.parse_args()

    if not 0 < a.version <= 999_999_999:
        die("version must be 1..999999999")
    # Panels never accept a lower version than the highest they ever installed, so a typo like 3020000
    # instead of 30200 would block every later bundle. The version is the app's versionCode.
    if not a.force:
        m = re.match(r"^(\d{1,4})\.(\d{1,2})\.(\d{1,2})$", a.app_version or "")
        if not m:
            die("--app-version MAJOR.MINOR.PATCH is required (or --force)")
        code = int(m[1]) * 10000 + int(m[2]) * 100 + int(m[3])
        if a.version > code:
            die(f"version {a.version} is above the app's versionCode {code}: panels would refuse every later bundle (--force to override)")
    files = {}  # bundle path -> (source, mode)
    for spec in a.file:
        dst, _, src = spec.partition("=")
        mode = "0644"
        if re.search(r":0[67][0-7][0-7]$", src):
            src, mode = src[:-5], src[-4:]
        files[dst] = (src, mode)
    for spec in a.dir:
        prefix, _, src = spec.partition("=")
        if not os.path.isdir(src):
            die(f"{src}: not a directory")
        for root, dirs, names in os.walk(src):
            dirs.sort()
            for n in sorted(names):
                p = os.path.join(root, n)
                rel = prefix + "/" + os.path.relpath(p, src).replace(os.sep, "/")
                files[rel] = (p, "0755" if os.access(p, os.X_OK) else "0644")

    if not files:
        die("no files")
    if len(files) > MAX_FILES:
        die("too many files")
    total = 0
    lines = ["S1RADIO-BUNDLE 1", f"version {a.version}", f"created {a.created}"]
    for dst in sorted(files):
        src, mode = files[dst]
        if not good_path(dst):
            die(f"bad bundle path {dst!r}")
        if mode not in MODES:
            die(f"{dst}: mode {mode} not allowed")
        if not os.path.isfile(src) or os.path.islink(src):
            die(f"{src}: not a regular file")
        if is_msla(src, dst):
            die(f"{src}: that is Silicon Labs' zigbeed (MSLA): it is never bundled; the panel downloads it")
        size = os.path.getsize(src)
        total += size
        lines.append(f"file {mode} {size} {sha256(src)} {dst}")
    if total > MAX_TOTAL:
        die("bundle too big")
    for spec in a.firmware:
        fid, _, rest = spec.partition("=")
        path, app, label = (rest.split(":", 2) + ["", ""])[:3]
        if not FW_ID_RE.match(fid) or fid == "stock":
            die(f"bad firmware id {fid!r}")
        if path not in files:
            die(f"firmware {fid}: {path} is not one of the files")
        if not APP_RE.match(app):
            die(f"firmware {fid}: bad APP version {app!r}")
        label = re.sub(r"\s+", " ", label).strip() or fid
        lines.append(f"firmware {fid} {path} {app} {label}")
    lines.append("end")
    manifest = ("\n".join(lines) + "\n").encode()
    for l in lines:
        if "  " in l or l != l.strip():
            die(f"manifest line {l!r} has stray spaces")

    out = os.path.join(a.out, "radio-bundle")
    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(out)
    for dst, (src, mode) in files.items():
        d = os.path.join(out, dst)
        os.makedirs(os.path.dirname(d), exist_ok=True)
        shutil.copyfile(src, d)
        os.chmod(d, int(mode, 8))
    with open(os.path.join(out, "manifest"), "wb") as f:
        f.write(manifest)

    ossl = openssl()
    with tempfile.TemporaryDirectory() as t:
        sig_raw = os.path.join(t, "sig")
        subprocess.run([ossl, "pkeyutl", "-sign", "-inkey", a.key, "-rawin", "-in", os.path.join(out, "manifest"),
                        "-out", sig_raw], check=True)
        sig = open(sig_raw, "rb").read()
        if len(sig) != 64:
            die("openssl produced no Ed25519 signature (is the key Ed25519?)")
        with open(os.path.join(out, "manifest.sig"), "w") as f:
            f.write(sig.hex() + "\n")
        if a.pubkey:
            pub = bytes.fromhex(open(a.pubkey).read().strip())
            if len(pub) != 32:
                die(f"{a.pubkey}: not a 32-byte public key")
            # SubjectPublicKeyInfo DER for Ed25519 = fixed 12-byte prefix + the key
            der = bytes.fromhex("302a300506032b6570032100") + pub
            pem = os.path.join(t, "pub.der")
            open(pem, "wb").write(der)
            r = subprocess.run([ossl, "pkeyutl", "-verify", "-pubin", "-inkey", pem, "-keyform", "DER", "-rawin",
                                "-in", os.path.join(out, "manifest"), "-sigfile", sig_raw], capture_output=True, text=True)
            if r.returncode != 0:
                die(f"the signature does not verify with {a.pubkey}: wrong key? {r.stdout.strip()} {r.stderr.strip()}")
    print(f"radio bundle {a.version}: {len(files)} files, {total} bytes -> {out}")
    print(manifest.decode(), end="")


if __name__ == "__main__":
    main()
