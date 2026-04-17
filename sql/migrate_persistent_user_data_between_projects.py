#!/usr/bin/env python3
"""
Copy persistent user_data fields from one Supabase project to another.

Intended cutover:
  old project = Baeren
  new project = Baeren3

Default mode is DRY RUN (no writes). Use --apply to write.

Copied fields:
  - pokemon_unlocked
  - coins_earned
  - game_indices
  - reward_apps
  - blacklisted_apps
  - white_listed_apps
  - kid_bank_balance

Usage (PowerShell example):
  python sql/migrate_persistent_user_data_between_projects.py `
    --old-url "https://ubrecfefhkcxwhohshdg.supabase.co" `
    --old-key "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVicmVjZmVmaGtjeHdob2hzaGRnIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjU5MjIzMjMsImV4cCI6MjA4MTQ5ODMyM30.3EikPtx_eKK6-apMTdk9aVr17IO1JauFGdvvbxqQ_bM" `
    --new-url "https://xvnosesmkoahykndbzgy.supabase.co" `
    --new-key "sb_publishable_cTe6HmJKa1VoXwZ43F8WUw_jUM0I3c3" `
    --profiles "AM,BM,TE"

Then apply:
  python sql/migrate_persistent_user_data_between_projects.py ... --apply
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from typing import Any, Dict, List, Optional
from urllib.parse import quote
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


FIELDS_TO_COPY = [
    "pokemon_unlocked",
    "coins_earned",
    "game_indices",
    "reward_apps",
    "blacklisted_apps",
    "white_listed_apps",
    "kid_bank_balance",
]


@dataclass
class SupabaseProject:
    url: str
    key: str

    @property
    def base_rest(self) -> str:
        return self.url.rstrip("/") + "/rest/v1"

    @property
    def headers(self) -> Dict[str, str]:
        return {
            "apikey": self.key,
            "Authorization": f"Bearer {self.key}",
            "Content-Type": "application/json",
        }


def http_json(
    method: str,
    url: str,
    headers: Dict[str, str],
    body_obj: Optional[Any] = None,
) -> Any:
    body_bytes = None
    if body_obj is not None:
        body_bytes = json.dumps(body_obj).encode("utf-8")

    req = Request(url=url, method=method, headers=headers, data=body_bytes)
    try:
        with urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            if not raw.strip():
                return None
            return json.loads(raw)
    except HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {e.code} for {method} {url}\n{detail}") from e
    except URLError as e:
        raise RuntimeError(f"Network error for {method} {url}: {e}") from e


def parse_profiles_arg(raw: str) -> List[str]:
    return [p.strip() for p in raw.split(",") if p.strip()]


def select_fields_clause() -> str:
    return ",".join(["profile"] + FIELDS_TO_COPY)


def fetch_source_rows(src: SupabaseProject, profiles: List[str]) -> List[Dict[str, Any]]:
    select_clause = select_fields_clause()
    if profiles:
        in_list = ",".join(profiles)
        profiles_filter = f"&profile=in.({quote(in_list, safe=',()')})"
    else:
        profiles_filter = ""

    url = f"{src.base_rest}/user_data?select={quote(select_clause, safe=',')}{profiles_filter}"
    rows = http_json("GET", url, src.headers)
    if not isinstance(rows, list):
        raise RuntimeError(f"Unexpected response from source user_data: {rows!r}")
    return rows


def build_payload_rows(source_rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    payload: List[Dict[str, Any]] = []
    for row in source_rows:
        profile = row.get("profile")
        if not profile:
            continue

        out: Dict[str, Any] = {"profile": profile}
        for field in FIELDS_TO_COPY:
            if field in row:
                out[field] = row.get(field)

        # Accept common typo key if present in source output
        if "kid_bank_balance" not in out and "kid_blank_balance" in row:
            out["kid_bank_balance"] = row.get("kid_blank_balance")

        payload.append(out)
    return payload


def upsert_target_rows(dst: SupabaseProject, payload_rows: List[Dict[str, Any]]) -> None:
    if not payload_rows:
        return

    url = f"{dst.base_rest}/user_data?on_conflict=profile"
    headers = dict(dst.headers)
    headers["Prefer"] = "resolution=merge-duplicates,return=representation"
    result = http_json("POST", url, headers, payload_rows)
    if not isinstance(result, list):
        raise RuntimeError(f"Unexpected upsert response from target: {result!r}")


def print_plan(rows: List[Dict[str, Any]]) -> None:
    if not rows:
        print("No rows found to copy.")
        return
    print(f"Profiles to copy: {len(rows)}")
    for row in rows:
        profile = row.get("profile")
        summary = {k: row.get(k) for k in FIELDS_TO_COPY}
        print(f"- {profile}: {json.dumps(summary, ensure_ascii=True)}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Copy persistent Supabase user_data fields between projects.")
    parser.add_argument("--old-url", required=True, help="Old project Supabase URL, e.g. https://xxx.supabase.co")
    parser.add_argument("--old-key", required=True, help="Old project service_role key")
    parser.add_argument("--new-url", required=True, help="New project Supabase URL, e.g. https://yyy.supabase.co")
    parser.add_argument("--new-key", required=True, help="New project service_role key")
    parser.add_argument(
        "--profiles",
        default="AM,BM,TE",
        help="Comma-separated profiles to migrate (default: AM,BM,TE). Use empty string to copy all.",
    )
    parser.add_argument("--apply", action="store_true", help="Actually write to target DB. Without this, dry run only.")
    args = parser.parse_args()

    profiles = parse_profiles_arg(args.profiles)
    old_proj = SupabaseProject(args.old_url, args.old_key)
    new_proj = SupabaseProject(args.new_url, args.new_key)

    print("Fetching source rows...")
    source_rows = fetch_source_rows(old_proj, profiles)
    payload_rows = build_payload_rows(source_rows)
    print_plan(payload_rows)

    if not args.apply:
        print("\nDry run complete. No writes made.")
        print("Re-run with --apply to write to target.")
        return 0

    print("\nApplying upsert to target...")
    upsert_target_rows(new_proj, payload_rows)
    print("Done.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)

