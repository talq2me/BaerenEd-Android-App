# Supabase deploy (new project / branch)

1. Run **[`supabase_schema_create.sql`](supabase_schema_create.sql)** (extensions, tables, policies).
2. Run **[`supabase_functions_create.sql`](supabase_functions_create.sql)** (all `af_*` RPCs in dependency order).

Regenerate the functions bundle after editing any `sql/af_*.sql` file:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "c:\Sandbox\Baeren\BaerenEd-Android-App-1\sql\regen_deploy_all_functions.ps1"
```

Optional later cleanup: **[`refactor_drop_legacy_config_rpcs_AFTER_MIGRATION.sql`](refactor_drop_legacy_config_rpcs_AFTER_MIGRATION.sql)** when no client calls legacy config RPC names.
