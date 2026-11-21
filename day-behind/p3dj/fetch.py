"""Fetch a DR Lyd episode (audio + DR's own track list) for personal listening.

DR Lyd has no public download API, so this reads the episode page and looks for
audio stream URLs and the track list in the page's embedded JSON. Pages change,
so this is deliberately tolerant: it searches the whole JSON tree instead of
relying on one fixed path. If it can't find the audio, pass --audio-url with a
stream URL copied from the browser's network tab.
"""
from __future__ import annotations

import json
import re
import shutil
import subprocess
from pathlib import Path
from urllib.parse import urljoin

import requests

UA = "Mozilla/5.0 (personal p3dj player)"
AUDIO_RE = re.compile(r"https?://[^\"'\s]+?\.(?:m3u8|mp3|m4a|mp4|aac)(?:\?[^\"'\s]*)?", re.I)


def _get(url: str) -> str:
    r = requests.get(url, headers={"User-Agent": UA}, timeout=30)
    r.raise_for_status()
    return r.text


def _embedded_json(html: str) -> list:
    blobs = []
    for m in re.finditer(r'<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.S):
        try:
            blobs.append(json.loads(m.group(1)))
        except json.JSONDecodeError:
            pass
    for m in re.finditer(r'<script[^>]*type="application/(?:ld\+)?json"[^>]*>(.*?)</script>', html, re.S):
        try:
            blobs.append(json.loads(m.group(1)))
        except json.JSONDecodeError:
            pass
    return blobs


def _walk(node, path=()):
    yield path, node
    if isinstance(node, dict):
        for k, v in node.items():
            yield from _walk(v, path + (k,))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from _walk(v, path + (i,))


def find_audio_urls(html: str) -> list[str]:
    urls = []
    for blob in _embedded_json(html):
        for _, v in _walk(blob):
            if isinstance(v, str) and AUDIO_RE.fullmatch(v):
                urls.append(v)
    urls += AUDIO_RE.findall(html.replace("\\u002F", "/").replace("\\/", "/"))
    seen, out = set(), []
    for u in urls:
        if u not in seen:
            seen.add(u)
            out.append(u)
    # Prefer progressive files (easy to seek), then HLS, highest bitrate-looking first.
    def rank(u: str):
        kind = 0 if re.search(r"\.(mp3|m4a|mp4|aac)", u, re.I) else 1
        kbps = max([int(x) for x in re.findall(r"(\d{2,3})k", u)] or [0])
        return (kind, -kbps)
    return sorted(out, key=rank)


def find_tracks(html: str) -> list[dict]:
    """Return DR's own list of songs played, if the page includes one."""
    tracks = []
    for blob in _embedded_json(html):
        for _, v in _walk(blob):
            if isinstance(v, dict) and "title" in v and any(
                k in v for k in ("artist", "artists", "roles", "musicArtist", "byArtist")
            ):
                artist = v.get("artist") or v.get("artists") or v.get("roles") or v.get("byArtist")
                if isinstance(artist, list):
                    artist = ", ".join(
                        a.get("name") or a.get("title") or a.get("artist") or "" if isinstance(a, dict) else str(a)
                        for a in artist
                    )
                elif isinstance(artist, dict):
                    artist = artist.get("name", "")
                tracks.append({
                    "title": v.get("title"),
                    "artist": artist,
                    "start": v.get("startTime") or v.get("playedTime") or v.get("start"),
                    "offset_ms": v.get("offsetMs") or v.get("startOffsetMs"),
                })
    return tracks


def find_episode_links(html: str, base: str) -> list[str]:
    links = re.findall(r'href="(/lyd/[^"#?]+)"', html)
    out = []
    for l in links:
        full = urljoin(base, l)
        if full not in out and full.rstrip("/") != base.rstrip("/") and re.search(r"\d{6,}", l):
            out.append(full)
    return out


def download(audio_url: str, dest: Path) -> Path:
    if not shutil.which("ffmpeg"):
        raise SystemExit("ffmpeg is needed: install it (e.g. `brew install ffmpeg`).")
    dest.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        "ffmpeg", "-y", "-loglevel", "error", "-user_agent", UA,
        "-i", audio_url, "-vn", "-c:a", "libmp3lame", "-b:a", "128k", str(dest),
    ], check=True)
    return dest


def fetch(page_url: str, out_dir: Path, audio_url: str | None = None, latest: bool = False) -> Path:
    html = _get(page_url)
    if latest:
        eps = find_episode_links(html, page_url)
        if not eps:
            raise SystemExit("Found no episode links on that series page. Pass an episode URL instead.")
        page_url = eps[0]
        print(f"Latest episode: {page_url}")
        html = _get(page_url)

    slug = page_url.rstrip("/").rsplit("/", 1)[-1]
    ep_dir = out_dir / slug
    ep_dir.mkdir(parents=True, exist_ok=True)

    if not audio_url:
        urls = find_audio_urls(html)
        if not urls:
            (ep_dir / "page.html").write_text(html)
            raise SystemExit(
                f"No audio URL found on the page (saved it to {ep_dir/'page.html'} for debugging).\n"
                "Open the episode in your browser, play it, copy the .m3u8/.mp3 request from the\n"
                "network tab and pass it with --audio-url."
            )
        audio_url = urls[0]
    print(f"Downloading audio from {audio_url[:90]}...")
    audio = download(audio_url, ep_dir / "show.mp3")

    tracks = find_tracks(html)
    (ep_dir / "tracks.json").write_text(json.dumps(tracks, ensure_ascii=False, indent=2))
    (ep_dir / "meta.json").write_text(json.dumps({"page": page_url, "audio_url": audio_url}, indent=2))
    print(f"Saved {audio} and {len(tracks)} track(s) from DR's playlist.")
    return audio
