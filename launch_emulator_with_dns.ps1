# Launch Android Emulator with DNS configuration for hotspot internet
# This script launches the Medium_Tablet_2 emulator with Google DNS servers

Write-Host "Launching Medium_Tablet_2 emulator with DNS configuration..." -ForegroundColor Green
Write-Host "DNS Servers: 8.8.8.8, 8.8.4.4" -ForegroundColor Cyan
Write-Host ""

# Try to find emulator in common locations
$emulatorPath = $null

# Check common Android SDK locations
$possiblePaths = @(
    "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe",
    "$env:USERPROFILE\AppData\Local\Android\Sdk\emulator\emulator.exe",
    "$env:ANDROID_HOME\emulator\emulator.exe",
    (Get-Command emulator -ErrorAction SilentlyContinue).Source
)

foreach ($path in $possiblePaths) {
    if ($path -and (Test-Path $path)) {
        $emulatorPath = $path
        break
    }
}

if (-not $emulatorPath) {
    Write-Host "ERROR: Could not find emulator.exe" -ForegroundColor Red
    Write-Host "Please set ANDROID_HOME or add Android SDK to PATH" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "You can also manually specify the path:" -ForegroundColor Yellow
    Write-Host '  $emulatorPath = "C:\path\to\emulator.exe"' -ForegroundColor Gray
    Write-Host '  & $emulatorPath -avd Medium_Tablet_2 -dns-server 8.8.8.8,8.8.4.4' -ForegroundColor Gray
    exit 1
}

Write-Host "Using emulator at: $emulatorPath" -ForegroundColor Green
Write-Host ""

# Launch emulator with DNS configuration
& $emulatorPath -avd Medium_Tablet_2 -dns-server 8.8.8.8,8.8.4.4
