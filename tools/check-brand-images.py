#!/usr/bin/env python3
"""Check that every glassworks.dev brand image the docs use is live.

Usage: tools/check-brand-images.py [files...]   (default: every *.md git tracks)
Collects each glassworks.dev/brand/<file> in the Markdown (src, srcset, ![](...); http or https, with
or without www., which the site redirects to https://www.) and
asks the site for it (HEAD, read-only). Exits 1 and lists every image that is missing (HTTP 404/410)
with the files that use it: a README must not point at a banner or badge that isn't deployed yet
(docs/branding.md, "A new library needs its banner first"). If the site can't be reached at all, it
warns and exits 0, so an outage there doesn't fail every pull request.
"""
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
URL = re.compile(r'https?://(?:www\.)?glassworks\.dev/brand/[A-Za-z0-9._-]+')
MISSING = {404, 410}
TRIES = 3


def markdown_files(args):
    if args:
        return [Path(a) for a in args]
    out = subprocess.run(['git', 'ls-files', '-z', '*.md'], cwd=ROOT, capture_output=True, text=True, check=True).stdout
    return [ROOT / p for p in out.split('\0') if p]


def status(url):
    """(HTTP status, None), or (None, why) if the site couldn't be reached after [TRIES] tries."""
    req = urllib.request.Request(url, method='HEAD', headers={'User-Agent': 'glassworks-ci-brand-check'})
    why = ''
    for attempt in range(TRIES):
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                return r.status, None
        except urllib.error.HTTPError as e:
            if e.code in MISSING:
                return e.code, None
            why = f'HTTP {e.code}'
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            why = str(getattr(e, 'reason', e))
        if attempt < TRIES - 1:
            time.sleep(2 * (attempt + 1))
    return None, why


def main(args):
    used = {}
    for f in markdown_files(args):
        for url in URL.findall(f.read_text(encoding='utf-8')):
            used.setdefault(url, set()).add(str(f.relative_to(ROOT)) if f.is_relative_to(ROOT) else str(f))
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = dict(zip(used, pool.map(status, used)))
    missing = sorted(u for u, (s, _) in results.items() if s in MISSING)
    unreachable = sorted(u for u, (s, _) in results.items() if s is None)
    for u in missing:
        print(f'{u}: not on glassworks.dev ({results[u][0]}); used in {", ".join(sorted(used[u]))}')
    if unreachable:
        why = results[unreachable[0]][1]
        print(f'::warning::glassworks.dev unreachable for {len(unreachable)} of {len(used)} brand images ({why}); those not checked')
    print(f'{len(used)} brand images, {len(missing)} missing')
    return 1 if missing else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
