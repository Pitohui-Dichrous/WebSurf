"""Loopback-only media fixtures, including cookie/referrer gates and byte ranges."""
from __future__ import annotations

import argparse
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
from pathlib import Path
import re
import threading
import time
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/fixtures"
REQUESTS: deque[dict] = deque(maxlen=400)
LOCK = threading.Lock()
MIMES = {".m3u8": "application/vnd.apple.mpegurl", ".ts": "video/mp2t", ".mp4": "video/mp4", ".m4a": "audio/mp4", ".key": "application/octet-stream"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"{self.log_date_time_string()} {fmt % args}", flush=True)

    def note(self, status: int, size: int = 0) -> None:
        with LOCK:
            REQUESTS.append({"time": time.time(), "method": self.command, "path": self.path,
                "status": status, "bytes": size, "cookiePresent": "websurf_fixture=ready" in self.headers.get("Cookie", ""),
                "refererPresent": bool(self.headers.get("Referer")), "range": self.headers.get("Range", ""),
                "fetchDest": self.headers.get("Sec-Fetch-Dest", "")})

    def payload(self, body: bytes, content_type: str, status: int = 200, cookie: bool = False) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if cookie:
            self.send_header("Set-Cookie", "websurf_fixture=ready; Path=/; HttpOnly; SameSite=Lax")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)
        self.note(status, len(body))

    def do_HEAD(self) -> None:
        self.do_GET()

    def do_GET(self) -> None:
        route = unquote(urlsplit(self.path).path)
        if route in ("/", "/index.html"):
            return self.payload((ROOT / "index.html").read_bytes(), "text/html; charset=utf-8", cookie=True)
        if route == "/multi.html":
            return self.payload((ROOT / "multi.html").read_bytes(), "text/html; charset=utf-8", cookie=True)
        if route == "/frame.html":
            return self.payload((ROOT / "frame.html").read_bytes(), "text/html; charset=utf-8", cookie=True)
        if route == "/scroll.html":
            return self.payload((ROOT / "scroll.html").read_bytes(), "text/html; charset=utf-8", cookie=True)
        if route == "/quality.html":
            return self.payload((ROOT / "quality.html").read_bytes(), "text/html; charset=utf-8", cookie=True)
        if route == "/recovery.html":
            return self.payload((ROOT / "recovery.html").read_bytes(), "text/html; charset=utf-8", cookie=True)
        if route == "/health":
            return self.payload(b'{"status":"ready","fixture":"WebSurf"}', "application/json")
        if route == "/api/requests":
            with LOCK:
                entries = list(REQUESTS)
            return self.payload(json.dumps(entries, indent=2).encode(), "application/json")
        if route == "/manifest.json":
            return self.payload((ROOT / "manifest.json").read_bytes(), "application/json")
        if route.startswith("/browser-only/"):
            # WebView's own media request succeeds; the separate native request is rejected.
            # This fixture tests fallback, without damaging or replacing the HTML decoder.
            if self.headers.get("Sec-Fetch-Dest", "").lower() != "video":
                return self.payload(b'{"error":"browser_media_request_required"}', "application/json", 403)
            relative = route[len("/browser-only/"):]
        elif route.startswith("/protected/"):
            reference = urlsplit(self.headers.get("Referer", ""))
            cookie_ok = any(part.strip() == "websurf_fixture=ready" for part in self.headers.get("Cookie", "").split(";"))
            referer_ok = reference.scheme == "http" and reference.hostname in ("127.0.0.1", "localhost") and reference.port == self.server.server_port
            if not cookie_ok or not referer_ok:
                return self.payload(json.dumps({"error": "cookie_and_referer_required", "cookie": cookie_ok, "referer": referer_ok}).encode(), "application/json", 403)
            relative = route[len("/protected/"):]
        elif route.startswith("/public/"):
            relative = route[len("/public/"):]
        else:
            return self.payload(b"Not found", "text/plain", 404)
        media_root = (ROOT / "media").resolve()
        file = (media_root / relative).resolve()
        if not file.is_relative_to(media_root) or not file.is_file():
            return self.payload(b"Not found", "text/plain", 404)
        total = file.stat().st_size
        start, end = 0, total - 1
        status = 200
        range_value = self.headers.get("Range")
        if range_value:
            match = re.fullmatch(r"bytes=(\d*)-(\d*)", range_value)
            if not match or not any(match.groups()):
                return self.payload(b"Invalid range", "text/plain", 416)
            low, high = match.groups()
            if low:
                start = int(low)
                end = min(int(high), end) if high else end
            else:
                start = max(0, total - int(high))
            if start > end or start >= total:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{total}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                self.note(416)
                return
            status = 206
        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", MIMES.get(file.suffix, mimetypes.guess_type(str(file))[0] or "application/octet-stream"))
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-store")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
        self.end_headers()
        self.note(status, length)
        if self.command == "HEAD":
            return
        with file.open("rb") as source:
            source.seek(start)
            remaining = length
            try:
                while remaining:
                    block = source.read(min(64 * 1024, remaining))
                    if not block:
                        break
                    self.wfile.write(block)
                    remaining -= len(block)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", default=8765, type=int)
    arguments = parser.parse_args()
    if not (ROOT / "media/hls/master.m3u8").exists():
        raise SystemExit("Run scripts/generate_fixtures.py first.")
    server = ThreadingHTTPServer(("127.0.0.1", arguments.port), Handler)
    server.daemon_threads = True
    print(f"WebSurf fixture ready: http://127.0.0.1:{arguments.port}/", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
