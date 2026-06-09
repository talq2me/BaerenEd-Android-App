# -*- coding: utf-8 -*-
"""
Build deduplicated schedule master files from profile configs.

Output: app/src/main/assets/config/schedule_master_{AM,BM,TE}.json

Each task gets a stable scheduleKey (launch + URL id). The weekly schedule editor
loads these masters so each task appears once per section.

Run: python scripts/generate_schedule_master.py
"""
from __future__ import annotations

import json
import os
import re
from urllib.parse import parse_qs, urlparse

BASE = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "config")
)
PROFILES = ("AM", "BM", "TE")


def schedule_key(task: dict, section_id: str) -> str:
    launch = (task.get("launch") or "").strip()
    url = (task.get("url") or "").strip()
    url_id = ""
    if url:
        try:
            q = parse_qs(urlparse(url).query)
            url_id = (
                (q.get("diagram") or [""])[0]
                or (q.get("file") or [""])[0]
                or (q.get("poolKey") or [""])[0]
            )
        except Exception:
            url_id = url
    title = (task.get("title") or task.get("label") or "").strip()
    if launch and url_id:
        return f"{section_id}|{launch}|{url_id}"
    if launch:
        return f"{section_id}|{launch}|{title}"
    return f"{section_id}|{title}"


def dedupe_tasks(tasks: list[dict], section_id: str) -> tuple[list[dict], list[str]]:
    out: list[dict] = []
    warnings: list[str] = []
    seen: set[str] = set()
    for t in tasks:
        title = (t.get("title") or "").strip()
        if not title and section_id != "checklist":
            continue
        key = schedule_key(t, section_id)
        if key in seen:
            warnings.append(f"Skipped duplicate key {key!r} (title={title!r})")
            continue
        seen.add(key)
        entry = dict(t)
        entry["scheduleKey"] = key
        out.append(entry)
    return out, warnings


def build_master(profile: str) -> dict:
    path = os.path.join(BASE, f"{profile}_config.json")
    with open(path, encoding="utf-8") as f:
        cfg = json.load(f)
    master = {
        "profile": profile,
        "version": cfg.get("version") or "",
        "source": f"{profile}_config.json",
        "required": [],
        "practice": [],
        "checklist": [],
    }
    all_warnings: list[str] = []
    for sec in cfg.get("sections") or []:
        sid = sec.get("id")
        if sid == "required" and sec.get("tasks"):
            tasks, w = dedupe_tasks(sec["tasks"], "required")
            master["required"] = tasks
            all_warnings.extend(w)
        elif sid == "optional" and sec.get("tasks"):
            tasks, w = dedupe_tasks(sec["tasks"], "practice")
            master["practice"] = tasks
            all_warnings.extend(w)
        elif sid == "checklist" and sec.get("items"):
            items, w = dedupe_tasks(sec["items"], "checklist")
            master["checklist"] = items
            all_warnings.extend(w)
    return master, all_warnings


def main() -> None:
    for profile in PROFILES:
        path = os.path.join(BASE, f"{profile}_config.json")
        if not os.path.isfile(path):
            print(f"Skip {profile}: no config")
            continue
        master, warnings = build_master(profile)
        out_path = os.path.join(BASE, f"schedule_master_{profile}.json")
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(master, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(
            f"Wrote {out_path}: "
            f"{len(master['required'])} required, "
            f"{len(master['practice'])} practice, "
            f"{len(master['checklist'])} checklist"
        )
        for w in warnings:
            print(f"  warn [{profile}]: {w}")


if __name__ == "__main__":
    main()
