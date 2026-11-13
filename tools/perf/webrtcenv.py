"""The Python the fake Home Assistant runs under (MIT, #202): a venv with webrtc-requirements.txt,
so its WebRTC peer (webrtc.py) has aiortc. Made once in the cache, named by the requirements' hash, so
a changed lock gets a new venv. Wheels only, every hash checked; nothing ships.

  python3 tools/perf/webrtcenv.py        # make it if needed; print its python

Writes only into the cache.
"""
import hashlib
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REQ = os.path.join(HERE, "webrtc-requirements.txt")
CACHE = os.environ.get("GW_PERF_CACHE") or os.path.join(os.path.expanduser("~"), ".cache", "glassworks-perf")


def python():
    if sys.version_info < (3, 11):
        sys.exit(f"the WebRTC peer needs Python 3.11 or later (this is {sys.version.split()[0]})")
    with open(REQ, "rb") as f:
        key = hashlib.sha256(f.read() + sys.version.split()[0].encode()).hexdigest()[:12]
    venv = os.path.join(CACHE, f"venv-webrtc-{key}")
    py = os.path.join(venv, "bin", "python")
    if not os.path.exists(os.path.join(venv, ".done")):
        shutil.rmtree(venv, ignore_errors=True)
        subprocess.run([sys.executable, "-m", "venv", venv], check=True, stdout=sys.stderr)
        subprocess.run([py, "-m", "pip", "install", "--quiet", "--disable-pip-version-check", "--require-hashes",
                        "--only-binary", ":all:", "-r", REQ], check=True, stdout=sys.stderr)
        subprocess.run([py, "-c", "import aiortc, av"], check=True)
        open(os.path.join(venv, ".done"), "w").close()
    return py


if __name__ == "__main__":
    print(python())
