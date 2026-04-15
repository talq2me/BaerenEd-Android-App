$base = Split-Path -Parent $MyInvocation.MyCommand.Path

# One function per .sql file; order respects dependencies (e.g. af_get_stars_to_minutes before trainer map RPCs).

$files = @(

  "af_get_stars_to_minutes.sql",

  "af_update_tasks_from_config_checklist_items.sql",

  "af_update_tasks_from_config_chores.sql",

  "af_update_tasks_from_config_practice.sql",

  "af_update_tasks_from_config_bonus.sql",

  "af_update_tasks_from_config_required.sql",

  "af_daily_reset.sql",

  "af_get_current_required_tasks.sql",

  "af_get_tasks_required.sql",

  "af_get_tasks_practice.sql",

  "af_get_tasks_bonus.sql",

  "af_get_battle_hub_counts.sql",

  "af_get_user_data.sql",

  "af_get_user_last_reset.sql",

  "af_get_user_last_updated.sql",

  "af_insert_user_data_profile.sql",

  "af_upsert_user_data_columns.sql",

  "af_get_settings_row.sql",

  "af_get_settings_last_updated.sql",

  "af_upsert_settings_row.sql",

  "af_get_device_row.sql",

  "af_upsert_device.sql",

  "af_upsert_image_upload.sql",

  "af_delete_image_uploads_ilike.sql",

  "af_get_image_upload_id.sql",

  "af_delete_image_upload_by_id.sql",

  "af_update_tasks_required.sql",

  "af_update_tasks_practice.sql",

  "af_update_tasks_bonus.sql",

  "af_update_task_completion.sql",

  "af_update_tasks_checklist_items.sql",

  "af_update_tasks_chores.sql",

  "af_update_game_index.sql",

  "af_update_pokemon_unlocked.sql",

  "af_update_berries_banked.sql",

  "af_reward_time_use.sql",

  "af_reward_time_pause.sql",

  "af_reward_time_expire.sql",

  "af_reward_time_add.sql"

)



function Get-PostgresDropArgList {
  param([string]$rawArgs)
  $s = ($rawArgs -replace '\s+DEFAULT\b[^,)]*', '').Trim()
  if ($s.Length -eq 0) { return "" }
  $parts = @()
  $depth = 0
  $cur = New-Object System.Text.StringBuilder
  foreach ($ch in $s.ToCharArray()) {
    if ($ch -eq '(') { $depth++ }
    elseif ($ch -eq ')') { $depth = [Math]::Max(0, $depth - 1) }
    if ($ch -eq ',' -and $depth -eq 0) {
      $parts += $cur.ToString()
      [void]$cur.Clear()
    } else {
      [void]$cur.Append($ch)
    }
  }
  if ($cur.Length -gt 0) { $parts += $cur.ToString() }
  $types = foreach ($p in $parts) {
    $t = $p.Trim() -replace '^\w+\s+', ''
    $t.Trim()
  }
  return ($types -join ', ')
}

$header = @"

-- =============================================================================

-- BaerenEd: Deploy all RPC/functions (drop + create)

-- Run after schema tables exist (see sql/supabase_schema_create.sql).

-- Regenerate from sql/regen_deploy_all_functions.ps1

-- =============================================================================



"@



$dropSet = [System.Collections.Generic.HashSet[string]]::new()

foreach ($f in $files) {

  $p = Join-Path $base $f

  if (-not (Test-Path $p)) { throw "Missing SQL file: $f" }

  $txt = [System.IO.File]::ReadAllText($p, [System.Text.UTF8Encoding]::new($false))

  $matches = [regex]::Matches($txt, '(?is)CREATE\s+OR\s+REPLACE\s+FUNCTION\s+([a-zA-Z0-9_\.]+)\s*\((.*?)\)')

  foreach ($m in $matches) {

    $name = $m.Groups[1].Value

    $rawArgs = ($m.Groups[2].Value -replace '\s+', ' ').Trim()

    $argList = Get-PostgresDropArgList $rawArgs

    [void]$dropSet.Add("DROP FUNCTION IF EXISTS $name($argList);")

  }

}



$sb = [System.Text.StringBuilder]::new()

[void]$sb.Append($header)

[void]$sb.Append("-- Drop existing function signatures first`n")

foreach ($d in ($dropSet | Sort-Object)) {

  [void]$sb.Append($d + "`n")

}

[void]$sb.Append("`n")



foreach ($f in $files) {

  [void]$sb.Append("-- -----------------------------------------------------------------------------`n")

  [void]$sb.Append("-- FILE: $f`n")

  [void]$sb.Append("-- -----------------------------------------------------------------------------`n")

  [void]$sb.Append([System.IO.File]::ReadAllText((Join-Path $base $f), [System.Text.UTF8Encoding]::new($false)))

  [void]$sb.Append("`n`n")

}



[System.IO.File]::WriteAllText((Join-Path $base "supabase_functions_create.sql"), $sb.ToString(), [System.Text.UTF8Encoding]::new($false))

Write-Host "Wrote supabase_functions_create.sql"

