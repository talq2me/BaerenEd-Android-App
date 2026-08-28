"""PaddleOCR wrapper: extract and clean French (latin) text from a page image."""

from __future__ import annotations

import re
from pathlib import Path

_ocr = None

_LEADING_ARTIFACTS = re.compile(r"^[|\[\]{}<>~`^=_*#\\]+")
_TRAILING_ARTIFACTS = re.compile(r"[|\[\]{}<>~`^=_*#\\]+$")

# (xmin, xmax, ymin, ymax, text)
LineBox = tuple[float, float, float, float, str]


def _get_ocr():
    global _ocr
    if _ocr is None:
        # Windows: albumentations (pulled in by PaddleOCR) imports torch.
        # If paddle loads first, torch fails with WinError 127 on shm.dll.
        import torch  # noqa: F401
        from paddleocr import PaddleOCR

        attempts = (
            {
                "use_angle_cls": True,
                "lang": "fr",
                "use_gpu": False,
                "show_log": False,
                "enable_mkldnn": False,
            },
            {
                "use_angle_cls": True,
                "lang": "fr",
                "show_log": False,
                "enable_mkldnn": False,
            },
            {"use_angle_cls": True, "lang": "fr", "enable_mkldnn": False},
            {"use_angle_cls": True, "lang": "fr"},
        )
        last_error: TypeError | None = None
        for kwargs in attempts:
            try:
                _ocr = PaddleOCR(**kwargs)
                break
            except TypeError as exc:
                last_error = exc
        if _ocr is None:
            raise RuntimeError(f"Could not initialize PaddleOCR: {last_error}")
    return _ocr


def clean_text(text: str) -> str:
    """De-hyphenate line breaks, normalize whitespace, strip OCR artifacts."""
    text = re.sub(r"-\s*\n\s*", "", text)
    text = re.sub(r"\s+", " ", text)
    text = text.strip()
    text = _LEADING_ARTIFACTS.sub("", text)
    text = _TRAILING_ARTIFACTS.sub("", text)
    return text.strip()


def _line_boxes(ocr_result) -> list[LineBox]:
    boxes: list[LineBox] = []
    if not ocr_result:
        return boxes
    for page in ocr_result:
        if page is None:
            continue
        for item in page:
            if not item or len(item) < 2 or not item[1]:
                continue
            word = item[1][0]
            if not word:
                continue
            pts = item[0]
            xs = [p[0] for p in pts]
            ys = [p[1] for p in pts]
            boxes.append((min(xs), max(xs), min(ys), max(ys), str(word)))
    return boxes


def _split_columns(boxes: list[LineBox]) -> list[list[LineBox]]:
    """Split into left/right columns when a vertical gutter is present (two-page spread)."""
    if len(boxes) < 4:
        return [boxes]

    x_left = min(b[0] for b in boxes)
    x_right = max(b[1] for b in boxes)
    width = x_right - x_left
    if width <= 0:
        return [boxes]

    by_center = sorted(boxes, key=lambda b: (b[0] + b[1]) / 2)
    best_gap = 0.0
    best_idx: int | None = None
    for i in range(len(by_center) - 1):
        left_max = max(b[1] for b in by_center[: i + 1])
        right_min = min(b[0] for b in by_center[i + 1 :])
        gap = right_min - left_max
        gutter_x = (left_max + right_min) / 2
        in_middle = x_left + 0.25 * width < gutter_x < x_left + 0.75 * width
        if in_middle and gap > best_gap:
            best_gap = gap
            best_idx = i

    # Gutter must be a real empty strip, not just a slightly wider word space.
    if best_idx is None or best_gap < 0.08 * width:
        return [boxes]
    return [by_center[: best_idx + 1], by_center[best_idx + 1 :]]


def _lines_in_block(boxes: list[LineBox]) -> list[str]:
    """Top-to-bottom, then left-to-right within the same visual line."""
    if not boxes:
        return []
    ordered = sorted(boxes, key=lambda b: (b[2], b[0]))
    lines: list[str] = []
    current: list[LineBox] = []
    current_y: float | None = None

    def flush() -> None:
        if current:
            current.sort(key=lambda b: b[0])
            lines.append(" ".join(b[4] for b in current))

    for box in ordered:
        ymid = (box[2] + box[3]) / 2
        height = max(box[3] - box[2], 1.0)
        if current and current_y is not None and abs(ymid - current_y) > 0.55 * height:
            flush()
            current = [box]
            current_y = ymid
        else:
            current.append(box)
            current_y = sum((b[2] + b[3]) / 2 for b in current) / len(current)
    flush()
    return lines


def _reading_order_text(boxes: list[LineBox]) -> str:
    columns = _split_columns(boxes)
    parts: list[str] = []
    for column in columns:
        parts.extend(_lines_in_block(column))
    return "\n".join(parts)


def extract_text(image_path: str | Path) -> str:
    """Run PaddleOCR and return cleaned page text in column-aware reading order."""
    result = _get_ocr().ocr(str(image_path), cls=True)
    boxes = _line_boxes(result)
    if not boxes:
        return ""
    return clean_text(_reading_order_text(boxes))
