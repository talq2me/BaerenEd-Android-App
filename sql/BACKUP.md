# Supabase Postgres backups

Periodic **logical** backups using `pg_dump`. Dumps are portable `.dump` files you can restore with `pg_restore` into Supabase or any Postgres host.

Credentials live in **`local.properties`** (same file as `SUPABASE_URL` / `SUPABASE_KEY`).

## One-time setup

### 1. Install `pg_dump`

```powershell
winget install PostgreSQL.PostgreSQL.17
```

If `pg_dump` is not on PATH, the script still finds it under `C:\Program Files\PostgreSQL\17\bin\`.

### 2. Add the connection string to `local.properties`

You already have `SUPABASE_URL` and `SUPABASE_KEY` for the app. For backups, add **one** line:

```properties
SUPABASE_DATABASE_URL=postgresql://postgres.xxxxx:[YOUR-PASSWORD]@aws-0-XXXX.pooler.supabase.com:5432/postgres
```

**Where to find it (current dashboard):**

1. Open your project: [Connect dialog](https://supabase.com/dashboard/project/xvnosesmkoahykndbzgy?showConnect=true).
2. Or click the green **Connect** button at the top of the project page.
3. Choose **Session pooler** (or type/method that shows port **5432** and a host like `….pooler.supabase.com`).
4. Copy the **URI** connection string.
5. If it still says `[YOUR-PASSWORD]`, replace that with your database password.

**Password** (only if the URI still has a placeholder):

1. Project sidebar → **Project Settings** (gear) → **Database**.
2. Under **Database password**, use **Reset database password** if you don’t remember it (you set this when the project was created; Supabase does not show the old password again).
3. Put the new password into the URI in place of `[YOUR-PASSWORD]`.

Do **not** use port **6543** (transaction pooler) for `pg_dump`.

Optional:

```properties
# BACKUP_DIR=C:/path/to/backups
# KEEP_DAYS=14
```

Gradle ignores `SUPABASE_DATABASE_URL`; it is only used by the backup scripts.

## Run a backup manually

**PowerShell** (recommended on Windows):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File sql/backup_supabase_db.ps1
```

**Git Bash / WSL:**

```bash
bash sql/backup_supabase_db.sh
```

Schema only (no row data):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File sql/backup_supabase_db.ps1 -SchemaOnly
```

Output goes to `backups/supabase/full/` (or `.../schema/`). Default retention: **14 days**.

## Schedule on Windows (Task Scheduler)

1. Open **Task Scheduler** → Create Task.
2. **Triggers:** Daily (e.g. 2:00 AM).
3. **Actions:** Start a program
   - Program: `powershell.exe`
   - Arguments: `-NoProfile -ExecutionPolicy Bypass -File "C:\Sandbox\Baeren\BaerenEd-Android-App-1\sql\backup_supabase_db.ps1"`
   - Start in: `C:\Sandbox\Baeren\BaerenEd-Android-App-1`
4. **Settings:** Run whether user is logged on or not (optional).

Test the task once with **Run** before relying on it.

## Monthly dump check (no restore)

Once a month, confirm the latest dump is readable:

```powershell
pg_restore --list backups\supabase\full\baeren_full_YYYY-MM-DD_HHMMSS.dump | Select-Object -First 20
```

You should see TOC entries (schemas, tables, `TABLE DATA public user_data`, etc.). That is enough for a routine monthly check.

## Optional: restore into a local empty database

Use this when you want to prove a dump actually restores (not needed every month). Replace `LOCAL_PASSWORD` with the password you set for the local PostgreSQL `postgres` user during install. Replace the dump filename with a real one under `backups\supabase\full\`.

If `psql` / `pg_restore` are not on PATH, use the full path, e.g. `"C:\Program Files\PostgreSQL\17\bin\psql.exe"`.

### 1. Create an empty database

```powershell
psql --dbname="postgresql://postgres:LOCAL_PASSWORD@localhost:5432/postgres" `
  -c "CREATE DATABASE baeren_restore_test;"
```

### 2. Restore into it

A full Supabase dump includes `auth` / `storage` / roles your local Postgres does not have. For a practical local check of app data, restore only the `public` schema:

```powershell
pg_restore --clean --if-exists --no-owner --no-privileges `
  --schema=public `
  --dbname="postgresql://postgres:LOCAL_PASSWORD@localhost:5432/baeren_restore_test" `
  backups\supabase\full\baeren_full_YYYY-MM-DD_HHMMSS.dump
```

Some role/extension warnings are normal on a plain local install. As long as `public` tables restore, the dump is usable.

### 3. Sample query (`user_data` is the main app table)

```powershell
psql --dbname="postgresql://postgres:LOCAL_PASSWORD@localhost:5432/baeren_restore_test" `
  -c "SELECT profile, last_updated, last_reset FROM public.user_data ORDER BY profile LIMIT 20;"
```

### 4. Drop the test database when done

```powershell
psql --dbname="postgresql://postgres:LOCAL_PASSWORD@localhost:5432/postgres" `
  -c "DROP DATABASE baeren_restore_test;"
```

## Restore back to the Supabase project (disaster recovery)

This writes into your **live** Supabase database. Prefer Session pooler URI (port **5432**) from `SUPABASE_DATABASE_URL` in `local.properties`.

`--clean --if-exists` drops existing objects before recreate. Use only when you intentionally want to overwrite the live DB from a dump.

```powershell
pg_restore --clean --if-exists --no-owner --no-privileges `
  --dbname="YOUR_SUPABASE_DATABASE_URL_FROM_local.properties" `
  backups\supabase\full\baeren_full_YYYY-MM-DD_HHMMSS.dump
```

Expect some warnings around managed Supabase schemas/roles; review errors carefully before treating the restore as complete. Afterward, spot-check in the SQL Editor, e.g. `SELECT count(*) FROM public.user_data;`.

## Troubleshooting

| Problem | Fix |
|--------|-----|
| `pg_dump: server version mismatch` | Install a newer PostgreSQL client. |
| Network / timeout | Use session pooler host (port 5432), not transaction pooler (6543). |
| Auth failed | Reset DB password in Dashboard; update `SUPABASE_DATABASE_URL`. |
| Tiny backup file | Wrong URI/password — script deletes bad files. |
