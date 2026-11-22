@echo off
rem Bouwt signed release-APK's op deze pc. Resultaat: dist\VideoAudioConverter-<versie>-<abi>.apk
rem Gebruik:  build-local.bat            (versie 1.0.0)
rem           build-local.bat 1.2.3
setlocal
cd /d "%~dp0"

set VERSION=%1
if "%VERSION%"=="" set VERSION=1.0.0

rem Java: Android Studio levert een JDK mee (JBR)
if "%JAVA_HOME%"=="" (
  if exist "C:\Program Files\Android\Android Studio\jbr" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)
if "%JAVA_HOME%"=="" (
  for /d %%D in ("%USERPROFILE%\.jdks\jbr-*") do set "JAVA_HOME=%%D"
)
if "%JAVA_HOME%"=="" (
  echo JAVA_HOME is niet ingesteld en Android Studio JBR is niet gevonden.
  exit /b 1
)

rem Android SDK-locatie
if not exist local.properties (
  if exist "%LOCALAPPDATA%\Android\Sdk" (
    set "SDKPATH=%LOCALAPPDATA%\Android\Sdk"
    setlocal enabledelayedexpansion
    echo sdk.dir=!SDKPATH:\=/!> local.properties
    endlocal
  ) else (
    echo Android SDK niet gevonden. Open het project eenmaal in Android Studio of maak local.properties met sdk.dir=...
    exit /b 1
  )
)

if not exist keystore.properties (
  echo keystore.properties ontbreekt. Zet keystore\release.jks en keystore.properties in deze map ^(zie README^).
  exit /b 1
)

call gradlew.bat assembleRelease -PversionName=%VERSION%
if errorlevel 1 exit /b 1

if not exist dist mkdir dist
for %%A in (arm64-v8a armeabi-v7a x86_64) do (
  copy /y app\build\outputs\apk\release\app-%%A-release.apk dist\VideoAudioConverter-%VERSION%-%%A.apk >nul
)
echo.
echo Klaar: dist\VideoAudioConverter-%VERSION%-*.apk
echo   arm64-v8a = telefoon, x86_64 = BlueStacks/emulator
