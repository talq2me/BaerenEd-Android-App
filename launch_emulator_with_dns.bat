@echo off
REM Launch Android Emulator with DNS configuration for hotspot internet
REM This script finds an available AVD on this machine and launches it with Google DNS servers

echo Launching Android emulator with DNS configuration...
echo DNS Servers: 8.8.8.8, 8.8.4.4
echo.

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

REM Discover first available AVD on this machine
set AVD_NAME=
for /f "usebackq delims=" %%A in (`"%EMULATOR_PATH%" -list-avds 2^>nul`) do (
    if not defined AVD_NAME set AVD_NAME=%%A
)
if not defined AVD_NAME (
    echo ERROR: No AVDs found on this machine.
    echo Create an AVD in Android Studio: Tools -^> Device Manager, or run: "%EMULATOR_PATH%" -list-avds
    pause
    exit /b 1
)

echo Using AVD: %AVD_NAME%
echo.

REM Launch emulator with DNS configuration
"%EMULATOR_PATH%" -avd %AVD_NAME% -dns-server 8.8.8.8,8.8.4.4

pause
