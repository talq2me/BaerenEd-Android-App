#!/usr/bin/env bash
# Full logical backup of Supabase Postgres via pg_dump.
# Credentials: local.properties (SUPABASE_URL + SUPABASE_DB_PASSWORD + SUPABASE_DB_POOLER_HOST).
# Run:  bash sql/backup_supabase_db.sh [--schema-only]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PROPERTIES_FILE="${PROPERTIES_FILE:-$REPO_ROOT/local.properties}"
SCHEMA_ONLY=0

for arg in "$@"; do
  case "$arg" in
    --schema-only) SCHEMA_ONLY=1 ;;
    -h|--help)
      echo "Usage: $0 [--schema-only]"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 1
      ;;
  esac
done

read_properties() {
  local file="$1" key="$2"
  [[ -f "$file" ]] || return 1
  grep -E "^${key}=" "$file" | tail -1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

if [[ ! -f "$PROPERTIES_FILE" ]]; then
  echo "Missing $PROPERTIES_FILE - add SUPABASE_DB_PASSWORD and SUPABASE_DB_POOLER_HOST (see sql/BACKUP.md)." >&2
  exit 1
fi

SUPABASE_DATABASE_URL="$(read_properties "$PROPERTIES_FILE" SUPABASE_DATABASE_URL || true)"
SUPABASE_URL="$(read_properties "$PROPERTIES_FILE" SUPABASE_URL || true)"
SUPABASE_DB_PASSWORD="$(read_properties "$PROPERTIES_FILE" SUPABASE_DB_PASSWORD || true)"
SUPABASE_DB_POOLER_HOST="$(read_properties "$PROPERTIES_FILE" SUPABASE_DB_POOLER_HOST || true)"
BACKUP_DIR="$(read_properties "$PROPERTIES_FILE" BACKUP_DIR || true)"
KEEP_DAYS="$(read_properties "$PROPERTIES_FILE" KEEP_DAYS || true)"

BACKUP_ROOT="${BACKUP_DIR:-$REPO_ROOT/backups/supabase}"
KEEP_DAYS="${KEEP_DAYS:-14}"
TIMESTAMP="$(date +%Y-%m-%d_%H%M%S)"

find_pg_dump() {
  if command -v pg_dump >/dev/null 2>&1; then
    command -v pg_dump
    return
  fi
  for candidate in \
    "/c/Program Files/PostgreSQL/17/bin/pg_dump.exe" \
    "/c/Program Files/PostgreSQL/16/bin/pg_dump.exe" \
    "/c/Program Files/PostgreSQL/15/bin/pg_dump.exe"
  do
    if [[ -x "$candidate" ]]; then
      echo "$candidate"
      return
    fi
  done
  return 1
}

PG_DUMP="$(find_pg_dump)" || {
  echo "pg_dump not found. Install PostgreSQL client tools first." >&2
  echo "  Windows: winget install PostgreSQL.PostgreSQL.17" >&2
  exit 1
}

USE_FULL_URL=0
if [[ -n "$SUPABASE_DATABASE_URL" ]]; then
  USE_FULL_URL=1
else
  if [[ -z "$SUPABASE_URL" ]]; then
    echo "SUPABASE_URL is empty in $PROPERTIES_FILE" >&2
    exit 1
  fi
  if [[ -z "$SUPABASE_DB_PASSWORD" ]]; then
    echo "SUPABASE_DB_PASSWORD is empty in $PROPERTIES_FILE (Dashboard -> Project Settings -> Database)." >&2
    exit 1
  fi
  if [[ -z "$SUPABASE_DB_POOLER_HOST" ]]; then
    echo "SUPABASE_DB_POOLER_HOST is empty in $PROPERTIES_FILE (Dashboard -> Connect -> Session pooler, host only)." >&2
    exit 1
  fi
  if [[ "$SUPABASE_URL" =~ https?://([^.]+)\.supabase\.co/?$ ]]; then
    PROJECT_REF="${BASH_REMATCH[1]}"
  else
    echo "Could not parse project ref from SUPABASE_URL: $SUPABASE_URL" >&2
    exit 1
  fi
  PG_USER="postgres.${PROJECT_REF}"
fi

KIND="full"
[[ "$SCHEMA_ONLY" -eq 1 ]] && KIND="schema"
OUT_DIR="$BACKUP_ROOT/$KIND"
OUT_FILE="$OUT_DIR/baeren_${KIND}_${TIMESTAMP}.dump"
mkdir -p "$OUT_DIR"

echo "Backing up Supabase ($KIND) -> $OUT_FILE"

DUMP_ARGS=(
  --format=custom
  --no-owner
  --no-privileges
  --file="$OUT_FILE"
)
[[ "$SCHEMA_ONLY" -eq 1 ]] && DUMP_ARGS+=(--schema-only)

if [[ "$USE_FULL_URL" -eq 1 ]]; then
  DUMP_ARGS=(--dbname="$SUPABASE_DATABASE_URL" "${DUMP_ARGS[@]}")
else
  export PGPASSWORD="$SUPABASE_DB_PASSWORD"
  DUMP_ARGS=(
    --host="$SUPABASE_DB_POOLER_HOST"
    --port=5432
    --username="$PG_USER"
    --dbname=postgres
    --no-password
    "${DUMP_ARGS[@]}"
  )
fi

if ! "$PG_DUMP" "${DUMP_ARGS[@]}"; then
  rm -f "$OUT_FILE"
  echo "pg_dump failed" >&2
  exit 1
fi
unset PGPASSWORD

SIZE_BYTES="$(wc -c < "$OUT_FILE" | tr -d ' ')"
if [[ "$SIZE_BYTES" -lt 1024 ]]; then
  rm -f "$OUT_FILE"
  echo "Backup file is suspiciously small. Check database password and pooler host." >&2
  exit 1
fi

SIZE_MB="$(awk "BEGIN { printf \"%.2f\", $SIZE_BYTES / 1048576 }")"
echo "Backup OK (${SIZE_MB} MB)"

PG_RESTORE="${PG_DUMP/pg_dump/pg_restore}"
if [[ -x "$PG_RESTORE" ]] || command -v pg_restore >/dev/null 2>&1; then
  pg_restore --list "$OUT_FILE" 2>/dev/null | head -3 | sed 's/^/  /' || true
fi

find "$OUT_DIR" -maxdepth 1 -name '*.dump' -type f -mtime +"$KEEP_DAYS" -print -delete

echo "Done. Kept backups from the last $KEEP_DAYS days in $OUT_DIR"
