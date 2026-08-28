"""Discover story folders and run OCR → translate → WebP → JSON."""

from __future__ import annotations

import sys
from pathlib import Path

from .build_json import build_story_json
from .ocr import extract_text
from .translate import translate_fr_to_en
from .utils import (
    convert_to_webp,
    detect_language,
    list_page_images,
    rename_sequentially,
    save_json,
)

TOOL_ROOT = Path(__file__).resolve().parent.parent
STORIES_DIR = TOOL_ROOT / "stories"
FLAG_NAME = "processed.flag"


def discover_story_folders() -> list[Path]:
    if not STORIES_DIR.is_dir():
        return []
    return sorted(
        p for p in STORIES_DIR.iterdir() if p.is_dir() and not p.name.startswith(".")
    )


def process_story(story_dir: Path) -> None:
    book_id = story_dir.name
    flag = story_dir / FLAG_NAME
    if flag.exists():
        print(f"[skip] {book_id} (already processed)")
        return

    pages_dir = story_dir / "pages"
    if not pages_dir.is_dir():
        print(f"[skip] {book_id} (no pages/ directory)")
        return

    images = list_page_images(pages_dir)
    if not images:
        print(f"[skip] {book_id} (no page images)")
        return

    print(f"[start] {book_id} ({len(images)} pages)")
    images = rename_sequentially(images)

    ocr_texts: list[str] = []
    for img in images:
        print(f"  OCR {img.name}...")
        ocr_texts.append(extract_text(img))

    language_source = detect_language("\n".join(ocr_texts))
    language_target = "en"
    print(f"  language mode: {language_source}")

    segments: list[dict] = []
    for i, french in enumerate(ocr_texts, start=1):
        if language_source == "fr":
            english = translate_fr_to_en(french)
        else:
            english = french
        segments.append(
            {
                "page": i,
                "image": f"pages/{i}.webp",
                "french_text": french,
                "english_text": english,
            }
        )

    for img in images:
        print(f"  WebP {img.name}...")
        convert_to_webp(img)

    story = build_story_json(book_id, segments, language_source, language_target)
    out_path = story_dir / "output" / "story.json"
    save_json(out_path, story)
    flag.write_text("", encoding="utf-8")
    print(f"[done] {book_id} -> {out_path}")


def main() -> int:
    folders = discover_story_folders()
    if not folders:
        print(f"No story folders found in {STORIES_DIR}")
        return 0

    failures = 0
    for folder in folders:
        try:
            process_story(folder)
        except Exception as exc:
            failures += 1
            print(f"[error] {folder.name}: {exc}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
