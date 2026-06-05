#!/usr/bin/env python3
"""One-off migration: flat GameData ufliWordChains -> v2 chain JSON + irregular sidecars."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAINS_DIR = ROOT / "app/src/main/assets/ufliWordChains"
IRR_DIR = ROOT / "app/src/main/assets/ufliIrregularWords"


def analyze_step(from_w: str, to_w: str):
    from_w = from_w.lower()
    to_w = to_w.lower()
    if to_w == from_w:
        return None
    if len(to_w) == len(from_w) + 1:
        for i in range(len(from_w) + 1):
            if to_w[:i] + to_w[i + 1 :] == from_w:
                return {"type": "insert", "from": from_w, "to": to_w}
    if len(to_w) == len(from_w) - 1:
        for i in range(len(from_w)):
            if from_w[:i] + from_w[i + 1 :] == to_w:
                return {"type": "delete", "from": from_w, "to": to_w}
    if len(to_w) == len(from_w):
        start = 0
        while start < len(from_w) and from_w[start] == to_w[start]:
            start += 1
        end_f = len(from_w) - 1
        end_t = len(to_w) - 1
        while end_f >= start and end_t >= start and from_w[end_f] == to_w[end_t]:
            end_f -= 1
            end_t -= 1
        if start <= end_f:
            return {"type": "replace", "from": from_w, "to": to_w}
    return None


def is_seed_prompt(prompt: str) -> bool:
    p = prompt.lower()
    return bool(re.match(r"^(write|make) the word ", p))


def is_irregular(prompt: str) -> bool:
    return "irregular word" in prompt.lower()


def is_vocab(prompt: str) -> bool:
    return "build vocabulary" in prompt.lower()


def parse_from_prompt(prompt: str) -> str | None:
    m = re.search(r"Say (\w+)\.", prompt, re.I)
    if m:
        return m.group(1).lower()
    return None


def concept_from_stem(stem: str) -> str:
    # ufli-chain-dge-g1 -> dge; ufli-affix-ing-chain-g1 -> affix-ing
    name = stem
    for prefix in ("ufli-chain-", "ufli-"):
        if name.startswith(prefix):
            name = name[len(prefix) :]
            break
    if name.endswith("-g1"):
        name = name[:-3]
    if name.endswith("-chain"):
        name = name[:-6]
    return name or stem


def spell_item(item: dict) -> dict:
    return {
        "word": item["question"]["text"].lower(),
        "prompt": item["prompt"]["text"],
        "correctChoices": item.get("correctChoices", []),
        "extraChoices": item.get("extraChoices", []),
    }


def migrate_array(stem: str, data: list) -> tuple[dict, list]:
    groups = []
    current = None
    vocab_steps = []
    irregular = []
    last_to = None

    def flush_group():
        nonlocal current
        if current and (current.get("seed") or current.get("steps")):
            groups.append(current)
        current = None

    for item in data:
        prompt = item["prompt"]["text"]
        target = item["question"]["text"].lower().replace(" -> ?", "").strip()

        if is_irregular(prompt):
            irregular.append(item)
            continue

        if is_seed_prompt(prompt):
            flush_group()
            current = {
                "id": f"chain-{len(groups) + 1}",
                "seed": spell_item(item),
                "steps": [],
            }
            last_to = target
            continue

        from_w = parse_from_prompt(prompt) or last_to
        step_payload = spell_item(item) if False else None

        if is_vocab(prompt) or "build vocabulary" in prompt.lower():
            if from_w and analyze_step(from_w, target):
                vocab_steps.append(
                    {"from": from_w, "to": target, "prompt": prompt}
                )
            else:
                seed = spell_item(item)
                seed["type"] = "seed"
                vocab_steps.append(seed)
            last_to = target
            continue

        # Chain step (Say X. Change...)
        if from_w and analyze_step(from_w, target):
            step = {"from": from_w, "to": target, "prompt": prompt}
            if current is None:
                # Orphan step: spell-only group
                current = {"id": f"chain-{len(groups) + 1}", "steps": [step]}
            else:
                current.setdefault("steps", []).append(step)
        else:
            # Full spell step (affix-ing, etc.)
            seed = spell_item(item)
            seed["type"] = "seed"
            if current is None:
                flush_group()
                groups.append({"id": f"spell-{len(groups) + 1}", "steps": [seed]})
            else:
                current.setdefault("steps", []).append(seed)
        last_to = target

    flush_group()

    if vocab_steps:
        continues = last_to
        for g in reversed(groups):
            if g.get("steps"):
                last_step = g["steps"][-1]
                if "to" in last_step:
                    continues = last_step["to"]
                    break
                if last_step.get("type") == "seed":
                    continues = last_step["word"]
                    break
            if g.get("seed"):
                continues = g["seed"]["word"]
                break
        groups.append(
            {
                "id": "vocab",
                "continuesFrom": continues,
                "steps": vocab_steps,
            }
        )

    chain_doc = {
        "id": stem,
        "concept": concept_from_stem(stem),
        "lang": "eng",
        "groups": groups,
    }
    return chain_doc, irregular


def main():
    IRR_DIR.mkdir(parents=True, exist_ok=True)
    for path in sorted(CHAINS_DIR.glob("*.json")):
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict) and "groups" in data:
            print(f"skip (v2): {path.name}")
            continue
        if not isinstance(data, list):
            print(f"skip (unknown): {path.name}")
            continue
        stem = path.stem
        chain_doc, irregular = migrate_array(stem, data)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(chain_doc, f, indent=2, ensure_ascii=False)
            f.write("\n")
        print(f"migrated chain: {path.name} ({len(chain_doc['groups'])} groups)")
        if irregular:
            irr_name = stem.replace("ufli-chain-", "ufli-irr-", 1)
            if irr_name == stem:
                irr_name = f"ufli-irr-{concept_from_stem(stem)}-g1"
            irr_path = IRR_DIR / f"{irr_name}.json"
            with open(irr_path, "w", encoding="utf-8") as f:
                json.dump(irregular, f, indent=2, ensure_ascii=False)
                f.write("\n")
            print(f"  irregular: {irr_path.name} ({len(irregular)} words)")


if __name__ == "__main__":
    main()
