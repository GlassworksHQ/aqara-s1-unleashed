#!/usr/bin/env python3
"""Serve tools/perf/page on 127.0.0.1 with caching off (MIT). Used by run.sh.

Every run must load the pages the same way. With the default headers, later runs would load from
GeckoView's HTTP and script caches and be faster than the first.

    python3 serve.py <port> <directory>
"""
import functools
import http.server
import sys


class NoStore(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


def main():
    port, root = int(sys.argv[1]), sys.argv[2]
    handler = functools.partial(NoStore, directory=root)
    http.server.ThreadingHTTPServer(("127.0.0.1", port), handler).serve_forever()


if __name__ == "__main__":
    main()
