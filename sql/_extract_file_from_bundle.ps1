# Extract one -- FILE: name.sql -- section from supabase_functions_create.sql (content only, no FILE markers).
param(
  [string]$BundleName = "supabase_functions_create.sql",
  [string]$WantedFile = "af_data_access_rpcs.sql"
)
$ErrorActionPreference = "Stop"
$base = Split-Path -Parent $MyInvocation.MyCommand.Path
$bundlePath = Join-Path $base $BundleName
$text = [System.IO.File]::ReadAllText($bundlePath, [System.Text.UTF8Encoding]::new($false))
$rx = [regex]::new("(?ms)^--\s+FILE:\s*(\S+)\s*\r?\n(?:--\s*-+\s*\r?\n)?")
$ms = $rx.Matches($text)
$start = -1
$end = $text.Length
for ($i = 0; $i -lt $ms.Count; $i++) {
  $fn = $ms[$i].Groups[1].Value
  if ($fn -eq $WantedFile) {
    $start = $ms[$i].Index + $ms[$i].Length
    if ($i + 1 -lt $ms.Count) { $end = $ms[$i + 1].Index }
    break
  }
}
if ($start -lt 0) { throw "FILE section not found: $WantedFile" }
$chunk = $text.Substring($start, $end - $start).Trim()
$out = Join-Path $base $WantedFile
[System.IO.File]::WriteAllText($out, $chunk + "`r`n", [System.Text.UTF8Encoding]::new($false))
Write-Host "Wrote $out ($($chunk.Length) chars)"
