"""Assemble the webgame story.json dict."""

from __future__ import annotations


def build_story_json(
    book_id: str,
    segments: list[dict],
    language_source: str,
    language_target: str,
) -> dict:
    voice = "fr-FR" if language_source == "fr" else "en-US"
    built = []
    for i, seg in enumerate(segments, start=1):
        built.append(
            {
                "id": f"segment_{i:03d}",
                "page": seg["page"],
                "image": seg["image"],
                "french_text": seg["french_text"],
                "english_text": seg["english_text"],
                "tts": {
                    "voice": voice,
                    "speed": 1.0,
                    "pitch": 1.0,
                },
            }
        )
    return {
        "book_id": book_id,
        "language_source": language_source,
        "language_target": language_target,
        "segments": built,
    }
