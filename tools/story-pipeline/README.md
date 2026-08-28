# Storybook OCR → Translation → JSON Pipeline

Authoring tool for BaerenEd. Drop page images into a story folder; the pipeline OCRs French (or English), translates when needed, converts pages to WebP, and writes `output/story.json` for a future webgame.

This does **not** generate tappable-text JSON. Output stays under `tools/story-pipeline/stories/` (not copied into app assets).

## Setup

From this directory (`tools/story-pipeline/`):

```bash
python -m venv .venv
# Windows Git Bash / macOS / Linux:
source .venv/Scripts/activate   # Windows
# source .venv/bin/activate     # macOS / Linux
pip install -r requirements.txt
```

### Argos FR → EN model

1. Download the free `translate-fr_en-*.argosmodel` package from [Argos Open Tech](https://www.argosopentech.com/argospm/index/) (French → English).
2. Place the `.argosmodel` file in `models/argos_fr_en/`.
3. The pipeline installs it from that path on first translation.

English-only books skip translation, so the model is only required for French books.

## Drop-images workflow

1. Create a folder under `stories/`. The folder name becomes `book_id`.
2. Put page images in `pages/` (jpg, jpeg, png, webp, tif, tiff, bmp).
3. From this directory, run:

```bash
python -m src.pipeline
```

The pipeline:

- Skips any folder that already has `processed.flag`
- Sorts images by file creation time
- Renames them `1.ext`, `2.ext`, …
- Runs OCR on the originals
- Detects French vs English (book-level heuristic)
- Translates French → English, or copies OCR into `english_text` for English-only books
- Converts pages to WebP (replaces originals)
- Writes `output/story.json`
- Creates empty `processed.flag` only after JSON succeeds

## Reprocess a book

Delete `stories/<book_id>/processed.flag` and run the pipeline again.

## English-only mode

If concatenated OCR looks mostly English (function words + few accents), translation is skipped. JSON still uses the `french_text` field for the source OCR; `language_source` and `language_target` are both `"en"`.

## Troubleshooting

- **`WinError 127` / `torch\\lib\\shm.dll`** — on Windows, PaddleOCR must load PyTorch before Paddle. The pipeline does this automatically; re-run `python -m src.pipeline`.
- **`OneDnnContext does not have the input Filter`** — PaddleOCR 2.x needs PaddlePaddle 2.6.2, not 3.x. From this directory with the venv on: `pip install -r requirements.txt`
- **First OCR run is slow** — Paddle downloads detection/recognition models once into `%USERPROFILE%/.paddleocr/`. Keep the venv activated and stay online for that first run.
