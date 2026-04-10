# -*- coding: utf-8 -*-
"""
Boukili books -> tappableText JSON (for BaerenEd rotation).

Input layout (under app/src/main/assets/boukili/<folder>/):
  - One .txt per book; pages separated by 3+ blank lines.
  - Images: p1.png … pN.png (sorted by page number).

Output: app/src/main/assets/tappableText/<id>.json

To add a new book:
  1. Add folder + txt + p1..pN.png under boukili/.
  2. Append a dict to BOOKS (folder, txt filename, id, title, merge_last_two_pages).
  3. Extend FR_EN (_RAW) for better English glosses in prompts.
  4. Run: python scripts/generate_boukili_tappable.py
  5. Comprehension MCQs live in COMPREHENSION_QUESTIONS in this file; to refresh only
     those fields in existing JSON (e.g. no PNGs in this checkout): add --inject-comprehension-only

merge_last_two_pages: only if text has one extra page vs images (rare); prefer matching N pages to N pngs.
"""
import json
import os
import re
import sys
import unicodedata

BASE = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")
)
BOUKILI = os.path.join(BASE, "boukili")
OUT = os.path.join(BASE, "tappableText")

# French token -> English gloss for prompts (Tape le mot qui veut dire '…'.)
FR_EN: dict[str, str] = {}

# Common words (extend as needed)
_RAW = """
éléphants elephants girafes giraffes voyageurs travelers photos photos animaux animals
famille family singes monkeys jeep jeep bébé baby grimaces faces sourit smiles
clics clicks appareils cameras amusent amuse curieux curious touche touch jouet toy
approche approaches grand-maman grandmother malin clever regarde look beau beautiful
revoir goodbye travail work demande asks inquiète worried éléphant elephant agités agitated
perdu lost arbre tree arrivent arrive rient laugh crient shout couche bedtime donnent give
grimacer grimace oiseaux birds nid nest branche branch pie magpie voleuse thief laine wool
chandail sweater gazon lawn recycleuse recycler œufs eggs hirondelle swallow corneille crow
moineau sparrow hibou owl pic-bois woodpecker tambourine drums creuse digs trou hole
intrus intruders colibri hummingbird coquille shell nourrit feeds faim hungry montagne mountain
ferme farm wagon wagon cochons pigs tire pulls avance advances roule rolls content happy
montagne mountain supplient beg transporte carries pirate pirate équipage crew trésor treasure
carte map sucettes lollipops lourd heavy couler sink canard duck perroquet parrot choix choice
accompagnent accompany amis friends bateau boat destination aperçoit spots brousse bush prennent take
sortes kinds bâtir build voler steal joyeux joyful fâché angry usé worn pousse pushes doux soft
bientôt soon télésphore télésphore chantonnent sing rempli full bouge moves
"""
for line in _RAW.strip().splitlines():
    parts = line.split()
    for i in range(0, len(parts) - 1, 2):
        FR_EN[parts[i].lower()] = parts[i + 1]


def norm(w: str) -> str:
    w = unicodedata.normalize("NFKD", w)
    return "".join(c for c in w if not unicodedata.combining(c)).lower()


def gloss_for(word: str) -> str:
    lw = norm(word).strip("'").strip("-")
    if lw in FR_EN:
        return FR_EN[lw]
    # strip leading l' d' etc.
    for p in ("l'", "d'", "qu'", "s'", "m'", "t'", "n'", "c'"):
        if lw.startswith(p):
            lw = lw[len(p) :]
            break
    if lw in FR_EN:
        return FR_EN[lw]
    return lw  # fallback: use token as gloss (still works for tapping)


def split_pages(text: str) -> list[str]:
    parts = re.split(r"\n{3,}", text.strip())
    return [p.strip() for p in parts if p.strip()]


def page_text_lines(block: str) -> list[str]:
    lines = []
    for ln in block.splitlines():
        t = ln.strip()
        if t:
            lines.append(t)
    return lines if lines else [block.strip()]


STOP = set(
    """
    dans des les un une et est que pour avec sans son sa ses ce cet cette ces de du le la
    mon ma mes mais ou où qui quoi comme sur en au aux à l d elles ils nous vous tu te ta
    tes ton ne pas plus très tout toute tous bien mal si oui non y a ai on leur leurs du
    des ses ces cet cette ceux celui celle ce qui dont où même déjà aussi alors même
    """.split()
)


def pick_words(block: str, n: int = 3) -> list[str]:
    raw = re.findall(r"\b[\wÀ-ÿâêîôûéèàùç'\-]+\b", block, re.I)
    seen: set[str] = set()
    out: list[str] = []

    def consider(w: str) -> bool:
        lw = norm(w).strip("'").strip("-")
        if len(lw) < 5 and lw not in FR_EN:
            return False
        if lw in STOP or len(lw) < 4:
            return False
        return True

    # Prefer longer, meaningful words first (stable order)
    ranked = [w for _, w in sorted(enumerate(raw), key=lambda iw: (-len(norm(iw[1])), iw[0]))]
    for w in ranked:
        lw = norm(w)
        if lw in seen:
            continue
        if not consider(w):
            continue
        seen.add(lw)
        out.append(w)
        if len(out) >= n:
            break
    # Fallback: shorter words if needed
    if len(out) < 2:
        for w in raw:
            lw = norm(w)
            if lw in seen:
                continue
            if len(lw) < 4 or lw in STOP:
                continue
            seen.add(lw)
            out.append(w)
            if len(out) >= 3:
                break
    return out[:n]


def make_tappable_questions(page_num: int, words: list[str]) -> list[dict]:
    qs = []
    for i, w in enumerate(words[:3], start=1):
        en = gloss_for(w)
        qs.append(
            {
                "id": f"p{page_num}_tw{i}",
                "prompt": f"Tape le mot qui veut dire '{en}'.",
                "correct_word": w,
            }
        )
    return qs


BOOKS = [
    {
        "folder": "singe",
        "txt": "singe.txt",
        "id": "boukili-singe-tappable",
        "title": "Boukili — Le bébé singe (Tappable Text)",
        "merge_last_two_pages": False,
    },
    {
        "folder": "nid",
        "txt": "nid.txt",
        "id": "boukili-nid-tappable",
        "title": "Boukili — Le nid (Tappable Text)",
        "merge_last_two_pages": False,
    },
    {
        "folder": "pec",
        "txt": "pec.txt",
        "id": "boukili-pec-tappable",
        "title": "Boukili — Pic-Bois (Tappable Text)",
        "merge_last_two_pages": False,
    },
    {
        "folder": "train",
        "txt": "train.txt",
        "id": "boukili-train-tappable",
        "title": "Boukili — Télésphore (Tappable Text)",
        "merge_last_two_pages": False,
    },
    {
        "folder": "les vrais tresors",
        "txt": "Le Pirate Raté voyage sur la mer.txt",
        "id": "boukili-pirate-rate-tappable",
        "title": "Boukili — Le Pirate Raté voyage sur la mer (Tappable Text)",
        "merge_last_two_pages": False,
    },
]

# Three multiple-choice questions per book (page_number, prompt, 4 options, 0-based correct_index).
COMPREHENSION_QUESTIONS: dict[str, list[dict[str, object]]] = {
    "boukili-singe-tappable": [
        {
            "page": 4,
            "prompt": "Qu'est-ce que le bébé singe aime beaucoup dans cette histoire ?",
            "options": [
                "Les girafes",
                "Les photos",
                "La jeep",
                "Les éléphants",
            ],
            "correct_index": 1,
        },
        {
            "page": 8,
            "prompt": "Qu'est-ce que l'éléphant dit avoir entendu ?",
            "options": [
                "Un bébé qui pleure",
                "Clic-clic-clic dans un arbre",
                "La grand-maman",
                "Les voyageurs",
            ],
            "correct_index": 1,
        },
        {
            "page": 12,
            "prompt": "Que donnent les voyageurs au petit singe à la fin ?",
            "options": [
                "Un jouet",
                "Une photo",
                "Une sucette",
                "Un appareil neuf",
            ],
            "correct_index": 1,
        },
    ],
    "boukili-nid-tappable": [
        {
            "page": 4,
            "prompt": "Qui prend la branche que Momo voulait utiliser pour le nid ?",
            "options": [
                "Rachelle",
                "La pie voleuse",
                "Momo",
                "Mimi",
            ],
            "correct_index": 1,
        },
        {
            "page": 8,
            "prompt": "Qu'est-ce que Rachelle apporte pour aider les oiseaux ?",
            "options": [
                "Une branche neuve",
                "Un chandail usé",
                "Des œufs",
                "Un nid tout fait",
            ],
            "correct_index": 1,
        },
        {
            "page": 12,
            "prompt": "Pourquoi Momo et Mimi remercient-ils Rachelle ?",
            "options": [
                "Pour la musique",
                "Pour l'aide à bâtir leur nid",
                "Pour des graines",
                "Pour une photo",
            ],
            "correct_index": 1,
        },
    ],
    "boukili-pec-tappable": [
        {
            "page": 4,
            "prompt": "Pourquoi Pic-Bois n'entend pas les autres oiseaux ?",
            "options": [
                "Il dort",
                "Il tambourine trop fort",
                "Il est tout seul",
                "Il a peur",
            ],
            "correct_index": 1,
        },
        {
            "page": 8,
            "prompt": "Que fait Pic-Bois près de son nid ?",
            "options": [
                "Il part en voyage",
                "Il protège son nid et chasse les intrus",
                "Il dort",
                "Il pond des œufs",
            ],
            "correct_index": 1,
        },
        {
            "page": 12,
            "prompt": "À la fin, qui fait de la musique ensemble ?",
            "options": [
                "Seulement les autres oiseaux",
                "La famille Pic-Bois",
                "Le hibou",
                "La corneille",
            ],
            "correct_index": 1,
        },
    ],
    "boukili-train-tappable": [
        {
            "page": 3,
            "prompt": "Pourquoi Télésphore dit-il qu'il n'est pas un bon train ?",
            "options": [
                "Il est en panne",
                "Il se croit trop petit",
                "Il n'a pas de rails",
                "Il n'aime pas les cochons",
            ],
            "correct_index": 1,
        },
        {
            "page": 7,
            "prompt": "Comment Télésphore arrive-t-il en haut de la montagne ?",
            "options": [
                "En volant",
                "En tirant encore et encore",
                "Les cochons poussent seuls",
                "Il abandonne",
            ],
            "correct_index": 1,
        },
        {
            "page": 11,
            "prompt": "À la fin, que disent les cochons sur Télésphore ?",
            "options": [
                "Qu'il est trop lent",
                "Qu'il est très fort",
                "Qu'il est trop petit",
                "Qu'il dort",
            ],
            "correct_index": 1,
        },
    ],
    "boukili-pirate-rate-tappable": [
        {
            "page": 2,
            "prompt": "Que montre la carte au trésor au Pirate Raté ?",
            "options": [
                "Seulement la mer",
                "La route vers le trésor",
                "Le nom du bateau",
                "Les poissons",
            ],
            "correct_index": 1,
        },
        {
            "page": 5,
            "prompt": "Quel choix difficile doit faire le Pirate Raté ?",
            "options": [
                "Quitter la mer",
                "Son équipage ou les sucettes",
                "Le canard ou le perroquet",
                "Nager ou rester",
            ],
            "correct_index": 1,
        },
        {
            "page": 8,
            "prompt": "Selon le Pirate Raté à la fin, quels sont les vrais trésors ?",
            "options": [
                "Les sucettes",
                "Les bons amis",
                "L'énorme sac",
                "Le bateau",
            ],
            "correct_index": 1,
        },
    ],
}


def attach_comprehension(book_id: str, pages: list[dict]) -> None:
    for page in pages:
        pn = int(page["page_number"])
        for cq in COMPREHENSION_QUESTIONS.get(book_id, []):
            if int(cq["page"]) != pn:
                continue
            page["comprehension_question"] = {
                "id": f"p{pn}_c1",
                "prompt": str(cq["prompt"]),
                "options": list(cq["options"]),
                "correct_index": int(cq["correct_index"]),
            }
            break


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    for spec in BOOKS:
        folder = spec["folder"]
        txt_path = os.path.join(BOUKILI, folder, spec["txt"])
        with open(txt_path, encoding="utf-8") as f:
            text = f.read()
        pages = split_pages(text)
        if spec.get("merge_last_two_pages") and len(pages) >= 2:
            a, b = pages[-2], pages[-1]
            pages = pages[:-2] + [a + "\n\n" + b]

        png_dir = os.path.join(BOUKILI, folder)
        pngs = sorted(
            [x for x in os.listdir(png_dir) if x.lower().endswith(".png")],
            key=lambda s: int(re.search(r"p(\d+)", s, re.I).group(1)) if re.search(r"p(\d+)", s, re.I) else 0,
        )
        if len(pages) != len(pngs):
            raise SystemExit(
                f"{folder}: page count {len(pages)} != png count {len(pngs)}"
            )

        out_pages = []
        for i, block in enumerate(pages, start=1):
            lines = page_text_lines(block)
            words = pick_words(block, 3)
            img = f"boukili/{folder}/p{i}"
            out_pages.append(
                {
                    "page_number": i,
                    "text": lines,
                    "image": {"image_id": img},
                    "tappable_word_questions": make_tappable_questions(i, words),
                }
            )

        attach_comprehension(spec["id"], out_pages)

        root = {
            "id": spec["id"],
            "title": spec["title"],
            "language": "fr",
            "pages": out_pages,
        }
        out_name = spec["id"] + ".json"
        out_path = os.path.join(OUT, out_name)
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(root, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print("Wrote", out_path, "pages", len(out_pages))


def inject_comprehension_only() -> None:
    """Merge comprehension_question into existing tappableText JSON (no txt/png needed)."""
    for spec in BOOKS:
        out_path = os.path.join(OUT, spec["id"] + ".json")
        if not os.path.isfile(out_path):
            print("Skip (missing):", out_path)
            continue
        with open(out_path, encoding="utf-8") as f:
            root = json.load(f)
        pages = root.get("pages")
        if not isinstance(pages, list):
            continue
        for p in pages:
            p.pop("comprehension_question", None)
        attach_comprehension(spec["id"], pages)
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(root, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print("Updated", out_path)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--inject-comprehension-only":
        inject_comprehension_only()
    else:
        main()
