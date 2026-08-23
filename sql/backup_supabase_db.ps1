# Full logical backup of Supabase Postgres via pg_dump.
# Credentials: local.properties (SUPABASE_URL + SUPABASE_DB_PASSWORD + SUPABASE_DB_POOLER_HOST).
# Run:  powershell -NoProfile -ExecutionPolicy Bypass -File sql/backup_supabase_db.ps1

param(
    [string]$PropertiesFile = (Join-Path (Split-Path $PSScriptRoot -Parent) "local.properties"),
    [switch]$SchemaOnly
)

$ErrorActionPreference = "Stop"

function Read-PropertiesFile {
    param([string]$Path)
    $vars = @{}
    if (-not (Test-Path $Path)) {
        throw "Missing $Path - add SUPABASE_DB_PASSWORD and SUPABASE_DB_POOLER_HOST (see sql/BACKUP.md)."
    }
    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq "" -or $line.StartsWith("#")) { return }
        $eq = $line.IndexOf("=")
        if ($eq -lt 1) { return }
        $key = $line.Substring(0, $eq).Trim()
        $val = $line.Substring($eq + 1).Trim()
        if ($val.StartsWith('"') -and $val.EndsWith('"')) { $val = $val.Substring(1, $val.Length - 2) }
        $vars[$key] = $val
    }
    return $vars
}

function Get-ProjectRef {
    param([string]$SupabaseUrl)
    if ($SupabaseUrl -match '^https?://([^.]+)\.supabase\.co/?$') {
        return $Matches[1]
    }
    throw "Could not parse project ref from SUPABASE_URL: $SupabaseUrl"
}

function Find-PgDump {
    $cmd = Get-Command pg_dump -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $candidates = @(
        "C:\Program Files\PostgreSQL\17\bin\pg_dump.exe",
        "C:\Program Files\PostgreSQL\16\bin\pg_dump.exe",
        "C:\Program Files\PostgreSQL\15\bin\pg_dump.exe"
    )
    foreach ($path in $candidates) {
        if (Test-Path $path) { return $path }
    }
    throw "pg_dump not found. Install PostgreSQL client tools, e.g.: winget install PostgreSQL.PostgreSQL.17"
}

$props = Read-PropertiesFile -Path $PropertiesFile

$useFullUrl = -not [string]::IsNullOrWhiteSpace($props["SUPABASE_DATABASE_URL"])
if ($useFullUrl) {
    $dbUrl = $props["SUPABASE_DATABASE_URL"]
    $pgHost = $null
    $pgUser = $null
    $pgPassword = $null
} else {
    $supabaseUrl = $props["SUPABASE_URL"]
    $pgPassword = $props["SUPABASE_DB_PASSWORD"]
    $pgHost = $props["SUPABASE_DB_POOLER_HOST"]

    if ([string]::IsNullOrWhiteSpace($supabaseUrl)) {
        throw "SUPABASE_URL is empty in $PropertiesFile"
    }
    if ([string]::IsNullOrWhiteSpace($pgPassword)) {
        throw "SUPABASE_DB_PASSWORD is empty in $PropertiesFile (Dashboard -> Project Settings -> Database)."
    }
    if ([string]::IsNullOrWhiteSpace($pgHost)) {
        throw "SUPABASE_DB_POOLER_HOST is empty in $PropertiesFile (Dashboard -> Connect -> Session pooler, host only)."
    }

    $projectRef = Get-ProjectRef -SupabaseUrl $supabaseUrl
    $pgUser = "postgres.$projectRef"
    $dbUrl = $null
}

$backupRoot = if ($props["BACKUP_DIR"]) {
    $props["BACKUP_DIR"]
} else {
    Join-Path (Split-Path $PSScriptRoot -Parent) "backups\supabase"
}
$keepDays = if ($props["KEEP_DAYS"]) { [int]$props["KEEP_DAYS"] } else { 14 }

$timestamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
$kind = if ($SchemaOnly) { "schema" } else { "full" }
$outDir = Join-Path $backupRoot $kind
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$outFile = Join-Path $outDir "baeren_$kind`_$timestamp.dump"
$pgDump = Find-PgDump

Write-Host "Backing up Supabase ($kind) -> $outFile"

$dumpArgs = @(
    "--format=custom",
    "--no-owner",
    "--no-privileges",
    "--file=$outFile"
)
if ($SchemaOnly) {
    $dumpArgs += "--schema-only"
}

if ($useFullUrl) {
    $dumpArgs = @("--dbname=$dbUrl") + $dumpArgs
} else {
    $env:PGPASSWORD = $pgPassword
    $dumpArgs = @(
        "--host=$pgHost",
        "--port=5432",
        "--username=$pgUser",
        "--dbname=postgres",
        "--no-password"
    ) + $dumpArgs
}

try {
    & $pgDump @dumpArgs
    if ($LASTEXITCODE -ne 0) {
        if (Test-Path $outFile) { Remove-Item $outFile -Force }
        throw "pg_dump failed with exit code $LASTEXITCODE"
    }
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}

$sizeMb = [math]::Round((Get-Item $outFile).Length / 1MB, 2)
if ($sizeMb -lt 0.01) {
    Remove-Item $outFile -Force
    throw "Backup file is suspiciously small ($sizeMb MB). Check database password and pooler host."
}

Write-Host "Backup OK ($sizeMb MB)"

$pgRestore = $pgDump -replace "pg_dump.exe", "pg_restore.exe" -replace "pg_dump", "pg_restore"
if (Test-Path $pgRestore) {
    & $pgRestore --list $outFile | Select-Object -First 3 | ForEach-Object { Write-Host "  $_" }
}

$cutoff = (Get-Date).AddDays(-$keepDays)
Get-ChildItem $outDir -Filter "*.dump" -File |
    Where-Object { $_.LastWriteTime -lt $cutoff } |
    ForEach-Object {
        Write-Host "Removing old backup: $($_.Name)"
        Remove-Item $_.FullName -Force
    }

Write-Host "Done. Kept backups from the last $keepDays days in $outDir"
