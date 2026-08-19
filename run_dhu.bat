@echo off
setlocal enabledelayedexpansion
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe

REM Pick a ready device and target it by transport_id. A bare "adb forward" fails with
REM "more than one device/emulator" when ADB lists more than one entry, so we target one
REM explicitly. We use transport_id rather than the serial because a wireless mDNS serial
REM can contain a SPACE (e.g. "..MS2Pb6 (2).._tcp"); that token-splits and breaks -s
REM targeting. transport_id is always a bare number, so it is space-safe.
REM
REM For each line we test for the space-delimited status word " device " (ready): the
REM substitution !LINE: device =! differs from !LINE! only when that exact token is
REM present, which excludes the "List of devices attached" header, the "device:<codename>"
REM field (no trailing space), and any offline/unauthorized entries. transport_id:<n> is
REM the last field, so stripping up to and including "transport_id:" leaves just the number.
REM We avoid both a pipe inside for/f (cmd mangles the leading-quoted adb path) and
REM `for %%t in (%%a)` (the space serial's "(2)" breaks the in(...) clause).
set TID=
for /f "tokens=*" %%a in ('"%ADB%" devices -l') do (
    set "LINE=%%a"
    if "!LINE: device =!" neq "!LINE!" if not defined TID set "TID=!LINE:*transport_id:=!"
)

if not defined TID (
    echo No ready ADB device found. Connect the phone ^(USB or wireless^) and retry.
    pause
    exit /b 1
)

echo Using device transport %TID%
"%ADB%" -t %TID% forward tcp:5277 tcp:5277

echo Starting DHU...
"%LOCALAPPDATA%\Android\Sdk\extras\google\auto\desktop-head-unit.exe" -c "%~dp0ioniq5.ini"
