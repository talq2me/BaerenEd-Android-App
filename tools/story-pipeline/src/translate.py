"""Argos Translate: French → English from the local models/argos_fr_en package."""

from __future__ import annotations

from pathlib import Path

_TOOL_ROOT = Path(__file__).resolve().parent.parent
_MODEL_DIR = _TOOL_ROOT / "models" / "argos_fr_en"
_ready = False


def _has_fr_en(packages) -> bool:
    for pkg in packages:
        from_code = getattr(pkg, "from_code", None)
        to_code = getattr(pkg, "to_code", None)
        if from_code == "fr" and to_code == "en":
            return True
    return False


def _ensure_model() -> None:
    global _ready
    if _ready:
        return

    from argostranslate import package

    if not _has_fr_en(package.get_installed_packages()):
        models = sorted(_MODEL_DIR.glob("*.argosmodel"))
        if not models:
            raise RuntimeError(
                "Argos FR→EN model not found. Place translate-fr_en-*.argosmodel "
                f"in {_MODEL_DIR}"
            )
        for model_path in models:
            try:
                package.install_from_path(str(model_path))
            except Exception:
                # Already installed or a duplicate package file.
                pass
        if not _has_fr_en(package.get_installed_packages()):
            raise RuntimeError(
                "Argos FR→EN model could not be installed from "
                f"{_MODEL_DIR}. See README.md."
            )

    _ready = True


def translate_fr_to_en(text: str) -> str:
    if not text or not text.strip():
        return ""
    _ensure_model()
    from argostranslate import translate

    return translate.translate(text, "fr", "en")
