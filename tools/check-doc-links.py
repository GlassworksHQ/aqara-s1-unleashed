#!/usr/bin/env python3
"""Check that relative links and #anchors in README.md and docs/**/*.md resolve.

Usage: tools/check-doc-links.py [files...]   (default: README.md and docs/**/*.md)
Exits 1 and lists each broken link. External (http/https/mailto) links are not fetched.
Anchors follow GitHub's heading slugs, plus explicit <a name/id="..."> tags.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LINK = re.compile(r'(?<!!)\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)|!\[[^\]]*\]\(\s*<?([^)\s>]+)>?[^)]*\)'
                  r'|<(?:a|img)\b[^>]*?\b(?:href|src)="([^"]+)"')
HEADING = re.compile(r'^(#{1,6})\s+(.*?)\s*#*\s*$')
EXPLICIT = re.compile(r'<a\s+(?:name|id)="([^"]+)"')
FENCE = re.compile(r'^\s*(```|~~~)')


def slug(text):
    text = re.sub(r'<[^>]+>', '', text)                  # inline HTML
    text = re.sub(r'!?\[([^\]]*)\]\([^)]*\)', r'\1', text)  # links -> text
    text = text.replace('`', '').strip().lower()
    text = re.sub(r'[^\w\- ]', '', text)                  # GitHub keeps letters, digits, _, -, space
    return text.replace(' ', '-')


def lines_outside_code(path):
    fenced = False
    for line in path.read_text(encoding='utf-8').splitlines():
        if FENCE.match(line):
            fenced = not fenced
            continue
        if not fenced:
            yield line


_anchors = {}


def anchors(path):
    if path not in _anchors:
        seen, out = {}, set()
        for line in lines_outside_code(path):
            out.update(EXPLICIT.findall(line))
            m = HEADING.match(line)
            if m:
                s = slug(m.group(2))
                n = seen.get(s, 0)
                seen[s] = n + 1
                out.add(s if n == 0 else f'{s}-{n}')
        _anchors[path] = out
    return _anchors[path]


def check(path):
    bad = []
    for line in lines_outside_code(path):
        line = re.sub(r'`[^`]*`', '', line)  # inline code
        for m in LINK.finditer(line):
            target = next(g for g in m.groups() if g)
            if re.match(r'^[a-z][a-z0-9+.-]*:', target, re.I) or target.startswith('//'):
                continue
            file_part, _, anchor = target.partition('#')
            dest = (path.parent / file_part).resolve() if file_part else path
            if not dest.exists():
                bad.append(f'{target}: no such file')
            elif anchor and dest.suffix == '.md' and anchor.lower() not in anchors(dest):
                bad.append(f'{target}: no such anchor')
    return bad


def main(argv):
    files = [Path(a).resolve() for a in argv] or [ROOT / 'README.md', *sorted((ROOT / 'docs').rglob('*.md'))]
    failures = 0
    for f in files:
        for b in check(f):
            print(f'{f.relative_to(ROOT)}: {b}')
            failures += 1
    print(f'{len(files)} files, {failures} broken link(s)')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
