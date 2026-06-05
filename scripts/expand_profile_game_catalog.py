#!/usr/bin/env python3
"""Ensure every game launch type appears in required + optional for AM/BM/TE."""
import copy
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG_DIR = ROOT / "app/src/main/assets/config"
DISABLE = "Dec 31, 2099"
GITHUB = "https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/html/"
EXCLUDE = {"kpop", "googleClassroom", "jeLis", "googleReadAlong"}

PROFILE_FILES = {
    "AM": CONFIG_DIR / "AM_config.json",
    "BM": CONFIG_DIR / "BM_config.json",
    "TE": CONFIG_DIR / "TE_config.json",
}

PROFILE_SUBS = {
    "AM": [
        ("englishWordsGr4.json", "englishWordsGr1.json"),
        ("englishWordsTE.json", "englishWordsGr1.json"),
        ("frenchWordsGr4.json", "frenchWordsGr1.json"),
        ("frenchWordsTE.json", "frenchWordsGr1.json"),
        ("spellingDragEnBM.json", "spellingDragEnGr1.json"),
        ("spellingDragFrBM.json", "spellingDragFrGr1.json"),
        ("poolKey=engSpellingDragPractice", "poolKey=engSpellingDrag"),
        ("poolKey=frSpellingDragPractice", "poolKey=frSpellingDrag"),
    ],
    "BM": [
        ("englishWordsGr1.json", "englishWordsGr4.json"),
        ("englishWordsTE.json", "englishWordsGr4.json"),
        ("frenchWordsGr1.json", "frenchWordsGr4.json"),
        ("frenchWordsTE.json", "frenchWordsGr4.json"),
        ("spellingDragEnGr1.json", "spellingDragEnBM.json"),
        ("spellingDragFrGr1.json", "spellingDragFrBM.json"),
        ("poolKey=engSpellingDragPractice", "poolKey=engSpellingDrag"),
        ("poolKey=frSpellingDragPractice", "poolKey=frSpellingDrag"),
    ],
    "TE": [
        ("englishWordsGr1.json", "englishWordsTE.json"),
        ("englishWordsGr4.json", "englishWordsTE.json"),
        ("frenchWordsGr1.json", "frenchWordsTE.json"),
        ("frenchWordsGr4.json", "frenchWordsTE.json"),
        ("spellingDragEnBM.json", "spellingDragEnGr1.json"),
        ("spellingDragFrBM.json", "spellingDragFrGr1.json"),
        ("poolKey=engSpellingDragPractice", "poolKey=engSpellingDrag"),
        ("poolKey=frSpellingDragPractice", "poolKey=frSpellingDrag"),
    ],
}

# Fallback templates when launch only exists in one profile.
STATIC_TEMPLATES = {
    "multiDigitAddSubtract": {
        "title": "Multi-Digit Add/Subtract",
        "launch": "multiDigitAddSubtract",
        "url": f"{GITHUB}multiDigitAddSubtract.html",
        "stars": 3,
        "webGame": True,
        "totalQuestions": 5,
    },
    "multiDigitMultiplication": {
        "title": "Multi-Digit Multiplication",
        "launch": "multiDigitMultiplication",
        "url": f"{GITHUB}multiDigitMultiplication.html",
        "stars": 3,
        "webGame": True,
        "totalQuestions": 5,
    },
    "timesTables": {
        "title": "Times Tables",
        "launch": "timesTables",
        "url": f"{GITHUB}timesTables.html?table=random",
        "stars": 3,
        "webGame": True,
    },
    "handwriting": {
        "title": "Handwriting Paper Snapshot",
        "launch": "handwriting",
        "url": f"{GITHUB}handwriting.html",
        "stars": 3,
        "webGame": True,
    },
    "printing": {"title": "Printing Practice", "launch": "printing", "stars": 3},
    "sightWords": {
        "title": "Sight Words",
        "launch": "sightWords",
        "stars": 1,
        "totalQuestions": 1,
    },
    "translation": {
        "title": "Translation",
        "launch": "translation",
        "stars": 3,
        "totalQuestions": 5,
    },
    "frenchStories": {
        "title": "French Stories",
        "launch": "frenchStories",
        "stars": 3,
        "totalQuestions": 5,
    },
    "duologicalGame": {
        "title": "Duological",
        "launch": "duologicalGame",
        "stars": 3,
        "totalQuestions": 5,
        "blockOutlines": True,
    },
    "storySequence": {
        "title": "Story Sequence",
        "launch": "storySequence",
        "url": f"{GITHUB}storySequence.html?file=storySequence.json",
        "stars": 3,
        "webGame": True,
    },
    "diagramLabeler": {
        "title": "Diagram Labeler",
        "launch": "diagramLabeler",
        "url": f"{GITHUB}diagramLabeler.html?diagram=circulatorySystem",
        "stars": 3,
        "webGame": True,
    },
    "conjugation": {
        "title": "Conjugation",
        "launch": "conjugation",
        "stars": 2,
        "totalQuestions": 5,
    },
    "conjugation_limparfait": {
        "title": "Conjugation",
        "launch": "conjugation_limparfait",
        "stars": 3,
        "totalQuestions": 5,
    },
    "gr3math": {"title": "Math", "launch": "gr3math", "stars": 3, "totalQuestions": 5},
    "gr3fractions": {
        "title": "Fractions",
        "launch": "gr3fractions",
        "stars": 3,
        "totalQuestions": 5,
    },
    "gr3mixedproblems": {
        "title": "Mixed Math",
        "launch": "gr3mixedproblems",
        "stars": 3,
        "totalQuestions": 5,
    },
    "gr3wordproblems": {
        "title": "Math Word Problems",
        "launch": "gr3wordproblems",
        "stars": 3,
        "totalQuestions": 5,
    },
    "gr3algebra": {
        "title": "Algebra",
        "launch": "gr3algebra",
        "stars": 3,
        "totalQuestions": 5,
    },
    "canadianMoneyHard": {
        "title": "Money Game",
        "launch": "canadianMoneyHard",
        "stars": 2,
        "totalQuestions": 5,
    },
    "canadianMoneyEasy": {
        "title": "Money Game",
        "launch": "canadianMoneyEasy",
        "stars": 3,
        "totalQuestions": 5,
    },
    "noviceKungFuVideos": {
        "title": "Kung Fu",
        "launch": "noviceKungFuVideos",
        "stars": 1,
        "videoSequence": "sequential",
    },
    "facileAlire2": {
        "title": "Facile A Lire 2",
        "launch": "facileAlire2",
        "stars": 3,
        "videoSequence": "sequential",
    },
    "uflivideos": {
        "title": "Ufli Home Videos",
        "launch": "uflivideos",
        "stars": 3,
        "videoSequence": "sequential",
    },
    "frenchUFLI": {
        "title": "French UFLI",
        "launch": "frenchUFLI",
        "stars": 3,
        "videoSequence": "sequential",
    },
    "ufliLessonData": {
        "title": "UFLI Words",
        "launch": "ufliLessonData",
        "stars": 3,
        "totalQuestions": 5,
        "blockOutlines": True,
    },
}


def is_game_task(task: dict) -> bool:
    launch = task.get("launch", "")
    if not launch or launch in EXCLUDE or launch.startswith("PL"):
        return False
    if task.get("chromePage"):
        return False
    return True


def adapt_for_profile(task: dict, profile: str) -> dict:
    out = copy.deepcopy(task)
    subs = PROFILE_SUBS.get(profile, [])
    for key in ("url",):
        if key in out and isinstance(out[key], str):
            for old, new in subs:
                out[key] = out[key].replace(old, new)
    if profile == "BM" and out.get("launch") == "timeTelling" and "url" in out:
        out["url"] = out["url"].replace("mode=easy", "mode=hard")
    if profile in ("AM", "TE") and out.get("launch") == "timeTelling" and "url" in out:
        out["url"] = re.sub(r"mode=hard", "mode=easy", out["url"])
    return out


def collect_templates() -> dict:
    templates = copy.deepcopy(STATIC_TEMPLATES)
    for path in CONFIG_DIR.glob("*_config.json"):
        cfg = json.loads(path.read_text(encoding="utf-8"))
        for sec in cfg.get("sections", []):
            if sec.get("id") not in ("required", "optional"):
                continue
            for task in sec.get("tasks", []):
                if not is_game_task(task):
                    continue
                launch = task["launch"]
                if launch not in templates:
                    templates[launch] = copy.deepcopy(task)
    return templates


def section_tasks(cfg: dict, section_id: str) -> list:
    for sec in cfg.get("sections", []):
        if sec.get("id") == section_id:
            return sec.setdefault("tasks", [])
    raise KeyError(section_id)


def launches_in(tasks: list) -> set:
    return {t["launch"] for t in tasks if is_game_task(t)}


def titles_in(tasks: list) -> set:
    return {t.get("title", "") for t in tasks if t.get("title")}


def make_title(base: str, section_id: str) -> str:
    if section_id == "optional":
        return base if "(Practice)" in base else f"{base} (Practice)"
    return base if "(Required)" not in base else base.replace(" (Required)", "")


def unique_title(base: str, section_id: str, used: set) -> str:
    title = make_title(base, section_id)
    if section_id == "required" and title in used:
        title = f"{base} (Required)"
    n = 2
    candidate = title
    while candidate in used:
        candidate = f"{title} {n}"
        n += 1
    return candidate


def practice_pool_key_fix(task: dict) -> None:
    url = task.get("url")
    if not isinstance(url, str):
        return
    url = url.replace("poolKey=engSpellingDragPracticePractice", "poolKey=engSpellingDragPractice")
    url = url.replace("poolKey=frSpellingDragPracticePractice", "poolKey=frSpellingDragPractice")
    if "poolKey=engSpellingDragPractice" not in url:
        url = url.replace("poolKey=engSpellingDrag", "poolKey=engSpellingDragPractice")
    if "poolKey=frSpellingDragPractice" not in url:
        url = url.replace("poolKey=frSpellingDrag", "poolKey=frSpellingDragPractice")
    task["url"] = url


def main():
    templates = collect_templates()
    all_launches = set(templates.keys())

    for profile, path in PROFILE_FILES.items():
        cfg = json.loads(path.read_text(encoding="utf-8"))
        req = section_tasks(cfg, "required")
        opt = section_tasks(cfg, "optional")
        req_launches = launches_in(req)
        opt_launches = launches_in(opt)

        added_req = []
        added_opt = []

        for launch in sorted(all_launches):
            tmpl = adapt_for_profile(templates[launch], profile)
            base_title = tmpl.get("title", launch)

            if launch not in req_launches:
                used = titles_in(req)
                title = unique_title(base_title, "required", used)
                task = copy.deepcopy(tmpl)
                task["title"] = title
                task["disable"] = DISABLE
                req.append(task)
                req_launches.add(launch)
                added_req.append(launch)

            if launch not in opt_launches:
                used = titles_in(opt)
                title = unique_title(base_title, "optional", used)
                task = copy.deepcopy(tmpl)
                task["title"] = title
                task["disable"] = DISABLE
                task["stars"] = 1
                if "totalQuestions" in task:
                    task["totalQuestions"] = 1
                practice_pool_key_fix(task)
                opt.append(task)
                opt_launches.add(launch)
                added_opt.append(launch)

        # Optional spelling-pool games use a separate game_indices key from required.
        for task in opt:
            if is_game_task(task):
                practice_pool_key_fix(task)

        cfg["version"] = "2026-06-05"
        path.write_text(json.dumps(cfg, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"{profile}: +{len(added_req)} required, +{len(added_opt)} optional")
        if added_req:
            print("  new required:", ", ".join(added_req))
        if added_opt:
            print("  new optional:", ", ".join(added_opt))


if __name__ == "__main__":
    main()
