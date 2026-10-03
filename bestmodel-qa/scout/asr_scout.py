#!/usr/bin/env python3
"""asr_scout.py — daily scout for on-device streaming ASR candidates.

Finds speech-recognition models on the Hugging Face Hub that could beat the
current pick for "streaming dictation on a phone, PT/EN/ES", and writes a
ranked candidate list. It does NOT measure anything: every row it emits is a
*candidate to benchmark*, never a result (honesty ladder: no data yet).

Stdlib only. Usage:
    python3 asr_scout.py --days 30 --out candidates.json --md candidates.md
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

HF_API = "https://huggingface.co/api/models"
REQUIRED_LANGS = ("en", "pt", "es")
MAX_PARAMS_B = 4.5  # Voxtral Mini 4B Realtime is the upper bound that fits a 12 GB phone at Q4
STREAMING_HINTS = ("streaming", "realtime", "real-time", "cache-aware", "online", "rnnt", "transducer")
MOBILE_FORMAT_TAGS = {"onnx": "onnx", "gguf": "gguf", "tflite": "tflite", "coreml": "coreml",
                      "qnn": "qnn", "executorch": "executorch", "litert": "tflite", "mlx": "mlx"}
# Repos that only re-package another model for one runtime: collapse onto their base.
DERIVATIVE_HINT = re.compile(r"(-|_)(onnx|gguf|int4|int8|q4|q8|mlx|coreml|qnn|tflite|\d+bit)", re.I)


def fetch(params: dict) -> list[dict]:
    url = f"{HF_API}?{urllib.parse.urlencode(params, doseq=True)}"
    req = urllib.request.Request(url, headers={"User-Agent": "bestmodel-asr-scout/0.1"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def params_b(model: dict) -> float | None:
    total = (model.get("safetensors") or {}).get("total")
    if total:
        return total / 1e9
    match = re.search(r"(\d+(?:\.\d+)?)\s*([bm])(?![a-z])", model["id"].split("/")[-1].lower())
    if not match:
        return None
    value = float(match.group(1))
    return value if match.group(2) == "b" else value / 1000


def langs(model: dict) -> set[str]:
    tags = set(model.get("tags") or [])
    card = model.get("cardData") or {}
    card_langs = card.get("language") or []
    if isinstance(card_langs, str):
        card_langs = [card_langs]
    return {t.split("-")[0].lower() for t in list(tags) + list(card_langs) if 2 <= len(t) <= 5}


def base_of(model: dict) -> str | None:
    base = (model.get("cardData") or {}).get("base_model")
    if isinstance(base, list):
        base = base[0] if base else None
    return base


def collapse_key(model: dict) -> str:
    """Runtime re-packages fold onto their base; real fine-tunes stay separate rows."""
    base = base_of(model)
    if not base or base == model["id"]:
        return model["id"]
    same_name = model["id"].split("/")[-1].lower() == base.split("/")[-1].lower()
    return base if same_name or DERIVATIVE_HINT.search(model["id"]) else model["id"]


def score(model: dict) -> tuple[float, list[str]]:
    hay = " ".join([model["id"]] + list(model.get("tags") or [])).lower()
    why: list[str] = []
    points = 0.0
    if any(h in hay for h in STREAMING_HINTS):
        points += 3
        why.append("streaming hint")
    formats = sorted({fmt for tag, fmt in MOBILE_FORMAT_TAGS.items() if tag in hay})
    if formats:
        points += 1 + 0.5 * len(formats)
        why.append("mobile formats: " + ",".join(formats))
    if "eval-results" in hay or "model-index" in hay:
        points += 1
        why.append("publishes evals")
    size = params_b(model)
    if size is not None and size <= 1.0:
        points += 1.5
        why.append(f"{size:.2f}B fits CPU/NPU comfortably")
    points += min(3.0, (model.get("downloads") or 0) ** 0.25 / 10)
    points += min(1.0, (model.get("likes") or 0) / 200)
    return round(points, 2), why


def scout(days: int, limit: int) -> list[dict]:
    since = datetime.now(timezone.utc) - timedelta(days=days)
    common = {"pipeline_tag": "automatic-speech-recognition", "full": "true", "cardData": "true",
              "limit": limit, "direction": -1}
    seen: dict[str, dict] = {}
    for sort in ("createdAt", "trendingScore", "downloads"):
        for model in fetch({**common, "sort": sort}):
            seen.setdefault(model["id"], model)

    rows: dict[str, dict] = {}
    for model in seen.values():
        created = datetime.fromisoformat(model.get("createdAt", "1970-01-01T00:00:00Z").replace("Z", "+00:00"))
        model_langs = langs(model)
        multilingual = "multilingual" in (model.get("tags") or [])
        if not (set(REQUIRED_LANGS) <= model_langs or multilingual):
            continue
        size = params_b(model)
        if size is not None and size > MAX_PARAMS_B:
            continue
        key = collapse_key(model)
        points, why = score(model)
        row = rows.setdefault(key, {"model": key, "variants": [], "params_b": None, "score": 0.0,
                                    "why": [], "created_at": None, "downloads": 0, "is_new": False})
        row["variants"].append(model["id"])
        row["params_b"] = row["params_b"] or (round(size, 3) if size else None)
        row["downloads"] = max(row["downloads"], model.get("downloads") or 0)
        if points > row["score"]:
            row["score"], row["why"] = points, why
        row["created_at"] = min(filter(None, [row["created_at"], created.isoformat()]))
        row["is_new"] = row["is_new"] or (model["id"] == key and created >= since)
        row["langs_ok"] = set(REQUIRED_LANGS) <= model_langs or row.get("langs_ok", False)

    ranked = sorted(rows.values(), key=lambda r: (r["score"], r["downloads"]), reverse=True)
    for row in ranked:
        row["basis"] = "no data yet"  # a scout row is a benchmark request, not a number
    return ranked


def to_markdown(rows: list[dict], top: int) -> str:
    lines = ["| # | model | params | score | new | why | variants |", "|---|---|---|---|---|---|---|"]
    for i, row in enumerate(rows[:top], 1):
        size = f"{row['params_b']}B" if row["params_b"] else "?"
        lines.append(f"| {i} | `{row['model']}` | {size} | {row['score']} | {'yes' if row['is_new'] else ''} "
                     f"| {'; '.join(row['why'])} | {len(row['variants'])} |")
    return "\n".join(lines) + "\n\nBasis for every row: **no data yet** — these are benchmark requests.\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--days", type=int, default=30, help="window that counts as 'new'")
    parser.add_argument("--limit", type=int, default=200, help="models fetched per sort order")
    parser.add_argument("--top", type=int, default=25)
    parser.add_argument("--out", default="candidates.json")
    parser.add_argument("--md")
    args = parser.parse_args()

    rows = scout(args.days, args.limit)
    with open(args.out, "w") as fh:
        json.dump({"generated_at": datetime.now(timezone.utc).isoformat(), "required_langs": REQUIRED_LANGS,
                   "max_params_b": MAX_PARAMS_B, "candidates": rows}, fh, indent=2)
    if args.md:
        with open(args.md, "w") as fh:
            fh.write(to_markdown(rows, args.top))
    print(to_markdown(rows, args.top))
    return 0


if __name__ == "__main__":
    sys.exit(main())
