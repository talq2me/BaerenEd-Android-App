@echo off
REM Launch Android Emulator with DNS configuration for hotspot internet
REM This script launches the Medium_Tablet_2 emulator with Google DNS servers

echo Launching Medium_Tablet_2 emulator with DNS configuration...
echo DNS Servers: 8.8.8.8, 8.8.4.4

REM Try to find emulator in common locations
set EMULATOR_PATH=
if exist "%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" (
    set EMULATOR_PATH=%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe
) else if exist "%USERPROFILE%\AppData\Local\Android\Sdk\emulator\emulator.exe" (
    set EMULATOR_PATH=%USERPROFILE%\AppData\Local\Android\Sdk\emulator\emulator.exe
) else if exist "%ANDROID_HOME%\emulator\emulator.exe" (
    set EMULATOR_PATH=%ANDROID_HOME%\emulator\emulator.exe
) else (
    echo ERROR: Could not find emulator.exe
    echo Please set EMULATOR_PATH manually or add Android SDK to PATH
    pause
    exit /b 1
)

echo Using emulator at: %EMULATOR_PATH%
echo.

REM Launch emulator with DNS configuration
"%EMULATOR_PATH%" -avd Medium_Tablet_2 -dns-server 8.8.8.8,8.8.4.4

pause
