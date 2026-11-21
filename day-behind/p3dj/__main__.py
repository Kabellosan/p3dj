"""p3dj: the P3 morning show, with your own music in the gaps.

  python -m p3dj fetch  <DR Lyd episode URL> [--audio-url URL]
  python -m p3dj fetch  <DR Lyd series URL> --latest
  python -m p3dj segment episodes/<episode>/show.mp3
  python -m p3dj serve                 # open http://127.0.0.1:8888
  python -m p3dj daily  <series URL>   # fetch latest + segment (for a morning cron job)
"""
from __future__ import annotations

import argparse
import functools
import http.server
import json
import webbrowser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EPISODES = ROOT / "episodes"
WEB = ROOT / "web"
PORT = 8888


class Handler(http.server.SimpleHTTPRequestHandler):
    """Serves web/ at / and episodes/ at /episodes/, with byte ranges for seeking."""

    def translate_path(self, path):
        path = path.split("?", 1)[0]
        if path.startswith("/episodes/"):
            return str(EPISODES / path[len("/episodes/"):])
        if path == "/api/episodes":
            return ""
        return str(WEB / (path.lstrip("/") or "index.html"))

    def do_GET(self):
        if self.path.startswith("/api/episodes"):
            eps = []
            for plan in sorted(EPISODES.glob("*/plan.json"), key=lambda p: p.stat().st_mtime, reverse=True):
                d = json.loads(plan.read_text())
                eps.append({"id": plan.parent.name, "audio": f"/episodes/{plan.parent.name}/{d['audio']}",
                            "duration": d["duration"], "gaps": d["gaps"]})
            body = json.dumps(eps).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        rng = self.headers.get("Range")
        fpath = Path(self.translate_path(self.path))
        if rng and fpath.is_file():
            size = fpath.stat().st_size
            start_s, _, end_s = rng.replace("bytes=", "").partition("-")
            start = int(start_s or 0)
            end = min(int(end_s) if end_s else size - 1, size - 1)
            self.send_response(206)
            self.send_header("Content-Type", self.guess_type(str(fpath)))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.send_header("Content-Length", str(end - start + 1))
            self.end_headers()
            with open(fpath, "rb") as f:
                f.seek(start)
                remaining = end - start + 1
                while remaining > 0:
                    chunk = f.read(min(65536, remaining))
                    if not chunk:
                        break
                    try:
                        self.wfile.write(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        return
                    remaining -= len(chunk)
            return
        super().do_GET()

    def end_headers(self):
        self.send_header("Accept-Ranges", "bytes")
        super().end_headers()


def serve():
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    url = f"http://127.0.0.1:{PORT}/"
    print(f"Player running at {url}  (Ctrl+C to stop)")
    webbrowser.open(url)
    srv.serve_forever()


def main():
    ap = argparse.ArgumentParser(prog="p3dj")
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fetch")
    f.add_argument("url")
    f.add_argument("--audio-url")
    f.add_argument("--latest", action="store_true")
    s = sub.add_parser("segment")
    s.add_argument("audio", type=Path)
    s.add_argument("--min-song", type=float, default=60.0)
    sub.add_parser("serve")
    d = sub.add_parser("daily")
    d.add_argument("url")
    a = ap.parse_args()

    if a.cmd == "fetch":
        from .fetch import fetch
        fetch(a.url, EPISODES, a.audio_url, a.latest)
    elif a.cmd == "segment":
        from .segment import segment
        segment(a.audio, a.min_song)
    elif a.cmd == "serve":
        serve()
    elif a.cmd == "daily":
        from .fetch import fetch
        from .segment import segment
        segment(fetch(a.url, EPISODES, latest=True))


if __name__ == "__main__":
    main()
