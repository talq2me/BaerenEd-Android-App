# Split multi-function SQL into one file per function. Removes the source file after split.
$ErrorActionPreference = "Stop"
$base = Split-Path -Parent $MyInvocation.MyCommand.Path

function Get-CreateIndexes {
  param([string]$Text)
  $rx = [regex]::new("CREATE\s+OR\s+REPLACE\s+FUNCTION\s+([a-zA-Z0-9_]+)\s*\(", [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
  $list = @()
  foreach ($m in $rx.Matches($Text)) {
    $list += [PSCustomObject]@{ Index = $m.Index; Name = $m.Groups[1].Value }
  }
  return $list
}

function CloseAtDollarQuote {
  param([string]$Text, [int]$From, [int]$MaxEnd)
  $len = [Math]::Min($MaxEnd - $From, $Text.Length - $From)
  if ($len -le 0) { throw "Invalid range at $From" }
  $seg = $Text.Substring($From, $len)
  $d = [char]36
  $asMark = $seg.IndexOf("AS $d$d")
  if ($asMark -lt 0) { throw "Missing AS $$ after index $From" }
  $afterOpen = $asMark + 4
  $closeRel = $seg.IndexOf("$d$d;", $afterOpen)
  if ($closeRel -lt 0) { throw "Missing closing $$; after index $($From + $afterOpen)" }
  return $From + $closeRel + 3
}

function Get-GrantsForFunction {
  param([string]$FullText, [string]$FuncName)
  $grants = @()
  foreach ($line in ($FullText -split "`r?`n")) {
    if ($line -match "^\s*GRANT\s+EXECUTE\s+ON\s+FUNCTION\s+$([regex]::Escape($FuncName))\s*\(") {
      $grants += $line.Trim()
    }
  }
  return $grants
}

function Split-OneFile {
  param([string]$RelativePath)
  $path = Join-Path $base $RelativePath
  if (-not (Test-Path $path)) { Write-Warning "Missing $path"; return }
  $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false))
  $creates = @(Get-CreateIndexes $text)
  if ($creates.Count -le 1) {
    Write-Host "Skip (0/1 function): $RelativePath"
    return
  }
  Write-Host "Split $($creates.Count) functions from $RelativePath"
  $prevEnd = 0
  for ($i = 0; $i -lt $creates.Count; $i++) {
    $startCreate = $creates[$i].Index
    $name = $creates[$i].Name
    $nextCreate = if ($i + 1 -lt $creates.Count) { $creates[$i + 1].Index } else { $text.Length }
    $close = CloseAtDollarQuote -Text $text -From $startCreate -MaxEnd $nextCreate
    $gap = $text.Substring($prevEnd, $startCreate - $prevEnd).Trim()
    $body = $text.Substring($startCreate, $close - $startCreate).Trim()
    $grants = @(Get-GrantsForFunction $text $name)
    $grantBlock = if ($grants.Count -gt 0) { "`r`n" + ($grants -join "`r`n") + "`r`n" } else { "`r`n" }
    $header = "-- BaerenEd: $name`r`n-- (from $RelativePath)`r`n`r`n"
    $prefix = if ($gap.Length -gt 0) { $gap + "`r`n`r`n" } else { "" }
    $outPath = Join-Path $base "$name.sql"
    [System.IO.File]::WriteAllText($outPath, ($header + $prefix + $body + $grantBlock), [System.Text.UTF8Encoding]::new($false))
    Write-Host "  wrote $name.sql"
    $prevEnd = $close
    while ($prevEnd -lt $text.Length -and [char]::IsWhiteSpace($text[$prevEnd])) { $prevEnd++ }
  }
  Remove-Item $path -Force
  Write-Host "  removed $RelativePath"
}

$Target = "af_data_access_rpcs.sql"
if ($args.Count -gt 0) { $Target = $args[0] }
Split-OneFile $Target
Write-Host "Done."
