@echo off
REM Reset Android Emulator DNS settings to default (system DNS)
REM This script uses ADB commands to reset DNS settings on a running emulator

echo Resetting emulator DNS settings to default...
echo.

REM Check if ADB is available
where adb >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: ADB not found in PATH
    echo Please ensure Android SDK platform-tools is in your PATH
    echo.
    echo Trying common ADB locations...
    
    set ADB_PATH=
    if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        set ADB_PATH=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
    ) else if exist "%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe" (
        set ADB_PATH=%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe
    ) else if exist "%ANDROID_HOME%\platform-tools\adb.exe" (
        set ADB_PATH=%ANDROID_HOME%\platform-tools\adb.exe
    ) else (
        echo ERROR: Could not find adb.exe
        echo Please set ADB_PATH manually or add Android SDK platform-tools to PATH
        pause
        exit /b 1
    )
    
    set ADB=%ADB_PATH%
) else (
    set ADB=adb
)

echo Using ADB: %ADB%
echo.

REM Check if device is connected
%ADB% devices | findstr /C:"device" >nul
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: No Android device/emulator detected
    echo Please ensure an emulator is running or a device is connected
    pause
    exit /b 1
)

echo Device detected. Resetting DNS settings...
echo.

REM Reset DNS to use system default (clear any custom DNS settings)
REM Method 1: Use setprop to reset net.dns properties
echo Resetting DNS properties...
%ADB% shell "su -c 'setprop net.dns1 \"\" && setprop net.dns2 \"\" && setprop net.dns3 \"\" && setprop net.dns4 \"\"'"

if %ERRORLEVEL% NEQ 0 (
    echo Note: Root access may not be available. Trying alternative method...
    echo.
    echo Alternative: Restart the emulator without -dns-server parameter
    echo to use default DNS settings.
    echo.
    echo To reset DNS:
    echo 1. Close the current emulator
    echo 2. Launch emulator normally (without -dns-server parameter)
    echo 3. Or use: launch_emulator_default.bat
) else (
    echo DNS properties reset successfully.
    echo You may need to restart network connectivity for changes to take effect.
)

echo.
echo DNS reset complete!
echo.
pause
