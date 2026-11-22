@echo off
rem Rolt een nieuwe versie uit: commit, tag en push. GitHub Actions bouwt daarna
rem de APK en zet hem in een Release; de app vindt hem via "Zoek naar updates".
rem Gebruik:  release.bat 1.0.1   of dubbelklikken (vraagt dan om de versie)
setlocal
cd /d "%~dp0"

set VERSION=%~1
if "%VERSION%"=="" (
  echo Laatste tags:
  git tag --sort=-v:refname 2>nul
  echo.
  set /p VERSION=Nieuwe versie ^(bijv. 1.0.1^): 
)
if "%VERSION%"=="" (
  echo Geen versie opgegeven.
  goto :einde
)

git rev-parse -q --verify "refs/tags/v%VERSION%" >nul 2>&1
if not errorlevel 1 (
  echo Tag v%VERSION% bestaat al. Kies een hoger versienummer.
  goto :einde
)

git add -A
git commit -m "Release %VERSION%"
git tag v%VERSION%
if errorlevel 1 goto :einde
git push
if errorlevel 1 (
  echo Push mislukt. Ben je ingelogd bij GitHub ^(bijv. via GitHub Desktop^)?
  goto :einde
)
git push origin v%VERSION%
if errorlevel 1 goto :einde
echo.
echo Tag v%VERSION% gepusht. Voortgang: Actions-tab van de repo op GitHub.

:einde
echo.
pause
