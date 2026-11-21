"""Find the music gaps in a radio show.

inaSpeechSegmenter labels audio as speech / music / noise / silence. Raw labels
are noisy (sung lyrics can be called speech, hosts talking over an intro can be
called music), so we smooth them into a simple plan: long music runs are songs
to replace, everything else is kept.
"""
from __future__ import annotations

import json
from pathlib import Path

SPEECH = {"speech", "male", "female"}


def smooth(raw: list[tuple[str, float, float]], min_song: float = 60.0,
           max_blip: float = 4.0, edge: float = 0.4) -> list[dict]:
    """Turn raw (label, start, end) segments into song gaps.

    min_song: music shorter than this is a jingle/bed and is kept.
    max_blip: speech shorter than this inside music is treated as lyrics.
    edge:     trim a little off each gap so host words at the boundary survive.
    """
    # 1. Collapse into two classes: talk vs music (noise/silence join their neighbours).
    segs = []
    for label, s, e in raw:
        cls = "talk" if label in SPEECH else "music" if label == "music" else None
        if cls is None:
            if segs:
                segs[-1][2] = e
            continue
        if segs and segs[-1][0] == cls:
            segs[-1][2] = e
        else:
            segs.append([cls, s, e])

    # 2. Short talk blips sandwiched between music are lyrics: fold them into the music.
    changed = True
    while changed:
        changed = False
        for i in range(1, len(segs) - 1):
            c, s, e = segs[i]
            if c == "talk" and e - s < max_blip and segs[i - 1][0] == "music" and segs[i + 1][0] == "music":
                segs[i - 1][2] = segs[i + 1][2]
                del segs[i:i + 2]
                changed = True
                break

    # 3. Long music runs are songs.
    gaps = []
    for c, s, e in segs:
        if c == "music" and e - s >= min_song:
            gaps.append({"start": round(s + edge, 2), "end": round(e - edge, 2),
                         "length": round(e - s - 2 * edge, 1)})
    return gaps


def segment(audio: Path, min_song: float = 60.0) -> Path:
    from inaSpeechSegmenter import Segmenter  # heavy import (TensorFlow)

    print("Analysing speech vs music (takes a few minutes for a 3-hour show)...")
    seg = Segmenter(vad_engine="smn", detect_gender=False)
    raw = [(l, float(s), float(e)) for l, s, e in seg(str(audio))]
    gaps = smooth(raw, min_song=min_song)
    duration = raw[-1][2] if raw else 0.0

    out = audio.parent / "plan.json"
    out.write_text(json.dumps({
        "audio": audio.name,
        "duration": duration,
        "gaps": gaps,
        "raw": raw,
    }, indent=1))
    music = sum(g["length"] for g in gaps)
    print(f"Found {len(gaps)} songs ({music/60:.0f} min of music) in {duration/60:.0f} min. Plan: {out}")
    return out
