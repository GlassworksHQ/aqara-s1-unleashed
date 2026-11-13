"""The pinned Home Assistant frontend for Clarity (MIT; the frontend itself is Apache-2.0).

Clarity's reference dashboards run HA's real frontend, the same build HA core serves: the
`home-assistant-frontend` wheel from PyPI. It is 117 MB, so it isn't in the repo. Like Magisk, it is
fetched on first use and checked by SHA-256, then unpacked into a cache. Nothing in it is executed
on this computer; its files are only served to the panel.

  python3 tools/perf/hafrontend.py          # fetch and check it, print where it is
"""
import hashlib
import os
import shutil
import sys
import urllib.request
import zipfile

VERSION = "20261028.5"
WHEEL = f"home_assistant_frontend-{VERSION}-py3-none-any.whl"
SHA256 = "df2af7a45296c6701de41b1d7e8582179c48ccf942ca17df0bea99aab4258730"
URL = ("https://files.pythonhosted.org/packages/py3/h/home-assistant-frontend/" + WHEEL)
LICENSE = "Apache-2.0"

CACHE = os.environ.get("GW_PERF_CACHE") or os.path.join(os.path.expanduser("~"), ".cache", "glassworks-perf")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def wheel_path():
    """The wheel, fetched if it isn't cached; refused if its hash isn't the pinned one."""
    os.makedirs(os.path.join(CACHE, "dl"), exist_ok=True)
    path = os.path.join(CACHE, "dl", WHEEL)
    if not os.path.exists(path):
        tmp = path + ".part"
        with urllib.request.urlopen(URL, timeout=300) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f)
        os.replace(tmp, path)
    got = sha256(path)
    if got != SHA256:
        raise SystemExit(f"{WHEEL}: SHA-256 {got}, expected {SHA256}; not used (delete it to fetch again)")
    return path


def root():
    """The unpacked `hass_frontend` directory of the pinned build."""
    out = os.path.join(CACHE, f"hass_frontend-{VERSION}")
    done = os.path.join(out, ".unpacked")
    if not os.path.exists(done):
        shutil.rmtree(out, ignore_errors=True)
        with zipfile.ZipFile(wheel_path()) as z:
            for name in z.namelist():
                # Only hass_frontend/, and never a path that climbs out of the cache.
                if not name.startswith("hass_frontend/") or ".." in name.split("/"):
                    continue
                z.extract(name, out)
        with open(done, "w") as f:
            f.write(SHA256 + "\n")
    return os.path.join(out, "hass_frontend")


if __name__ == "__main__":
    print(root())
    sys.exit(0)
