"""File helpers: page ordering, rename, language heuristic, WebP, JSON I/O."""

from __future__ import annotations

import json
import re
from pathlib import Path

from PIL import Image

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".tif", ".tiff", ".bmp"}

FR_FUNCTION_WORDS = frozenset(
    """
    le la les des une un et est que pour dans avec du de au aux ce cette ces
    qui pas plus tout toute tous sont nous vous ils elles elle il je tu on ne
    se son sa ses mon ma mes ton ta tes leur leurs mais ou bien aussi
    comme sur en y a ai as ont etait etre avoir fait dit
    tres sans sous entre apres avant chez
    """.split()
)

EN_FUNCTION_WORDS = frozenset(
    """
    the a an is are of to and in that it for on with as was be this have
    from or by not at they he she we you i his her their was were been
    had has do does did will would can could should about into than then
    """.split()
)

ACCENT_CHARS = frozenset("àâäæçéèêëîïôœùûüÿ")


def creation_time(path: Path) -> float:
    """File creation time (Windows st_ctime / st_birthtime)."""
    stat = path.stat()
    return getattr(stat, "st_birthtime", stat.st_ctime)


def list_page_images(pages_dir: Path) -> list[Path]:
    files = [
        p
        for p in pages_dir.iterdir()
        if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS
    ]
    return sorted(files, key=lambda p: (creation_time(p), p.name.lower()))


def rename_sequentially(paths: list[Path]) -> list[Path]:
    """Rename images to 1.ext, 2.ext, … in two passes so nothing is overwritten."""
    if not paths:
        return []
    parent = paths[0].parent
    temps: list[Path] = []
    for i, src in enumerate(paths):
        tmp = parent / f".__seq_tmp_{i}{src.suffix.lower()}"
        src.rename(tmp)
        temps.append(tmp)
    result: list[Path] = []
    for i, tmp in enumerate(temps, start=1):
        dest = parent / f"{i}{tmp.suffix.lower()}"
        tmp.rename(dest)
        result.append(dest)
    return result


def detect_language(text: str) -> str:
    """Book-level FR vs EN heuristic. Returns 'fr' or 'en'."""
    if not text or not text.strip():
        return "fr"
    tokens = re.findall(r"[A-Za-zÀ-ÿ]+", text.lower())
    fr_score = sum(1 for t in tokens if t in FR_FUNCTION_WORDS)
    en_score = sum(1 for t in tokens if t in EN_FUNCTION_WORDS)
    fr_score += sum(1 for c in text.lower() if c in ACCENT_CHARS)
    if en_score > fr_score:
        return "en"
    return "fr"


def convert_to_webp(image_path: Path, quality: int = 80) -> Path:
    """Convert an image to WebP. Already-webp files are left as-is. Originals are deleted."""
    dest = image_path.with_suffix(".webp")
    if image_path.suffix.lower() == ".webp":
        return image_path
    with Image.open(image_path) as im:
        if im.mode in ("RGBA", "LA"):
            im.save(dest, "WEBP", quality=quality, method=6)
        else:
            im.convert("RGB").save(dest, "WEBP", quality=quality, method=6)
    image_path.unlink()
    return dest


def save_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))
