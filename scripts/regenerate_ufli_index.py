#!/usr/bin/env python3
"""Regenerate ufliWordChains/index.json and ufliIrregularWords/index.json from folder contents."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FOLDERS = ("ufliWordChains", "ufliIrregularWords")


def write_index(folder: str) -> int:
    d = ROOT / "app/src/main/assets" / folder
    files = sorted(
        p.name for p in d.glob("*.json") if p.name != "index.json"
    )
    out = d / "index.json"
    out.write_text(json.dumps({"files": files}, indent=2) + "\n", encoding="utf-8")
    print(f"{folder}: {len(files)} files -> {out.relative_to(ROOT)}")
    return len(files)


def main() -> None:
    for folder in FOLDERS:
        write_index(folder)


if __name__ == "__main__":
    main()
