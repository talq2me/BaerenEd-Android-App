from pathlib import Path
from PIL import Image


def main() -> None:
    root = Path("app/src/main/assets/boukili")
    files = list(root.rglob("*.png"))
    converted = 0
    errors = 0

    for src in files:
        try:
            dst = src.with_suffix(".webp")
            with Image.open(src) as im:
                if im.mode in ("RGBA", "LA"):
                    im.save(dst, "WEBP", quality=80, method=6)
                else:
                    im.convert("RGB").save(dst, "WEBP", quality=80, method=6)
            converted += 1
        except Exception:
            errors += 1

    remaining_png = len(list(root.rglob("*.png")))
    total_webp = len(list(root.rglob("*.webp")))
    print(f"converted={converted}")
    print(f"errors={errors}")
    print(f"remaining_png={remaining_png}")
    print(f"total_webp={total_webp}")


if __name__ == "__main__":
    main()
