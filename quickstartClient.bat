@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem --- Immer vom Skriptordner aus arbeiten
pushd "%~dp0"

rem --- Codepage/Encoding sichern und auf UTF-8 umstellen (wichtig für Umlaute)
for /f "tokens=2 delims=:." %%A in ('chcp') do set "OLDCP=%%A"
set "OLDCP=%OLDCP: =%"
chcp 65001 >nul

set "MODE=GUI"
set "ARG=%~1"

rem === Hilfe anzeigen ===
if /I "%ARG%"=="-help"  goto :showHelp
if /I "%ARG%"=="--help" goto :showHelp
if /I "%ARG%"=="help"   goto :showHelp

rem === CLI-Modus erkennen (beide Schreibweisen akzeptieren) ===
if defined ARG (
    if /I "%ARG%"=="-nogui" (
        set "MODE=CLI"
    ) else if /I "%ARG%"=="noGUI" (
        set "MODE=CLI"
    ) else (
        echo Unbekannte Option: "%ARG%"
        goto :showHelp
    )
)

set "OUT_DIR=classes"
set "SRC_LIST=%OUT_DIR%\sources.txt"
set "EXITCODE=0"

if exist "%OUT_DIR%" rmdir /s /q "%OUT_DIR%"
mkdir "%OUT_DIR%" || goto :finalize

rem === Alle .java-Dateien sammeln (Pfadtrenner vereinheitlichen) ===
> "%SRC_LIST%" (
  for /r "src" %%F in (*.java) do (
    set "p=%%~fF"
    set "p=!p:\=/!"
    echo "!p!"
  )
) || (
    set "EXITCODE=1"
    goto :finalize
)

rem === Kompilieren ===
javac -J-Dfile.encoding=UTF-8 -d "%OUT_DIR%" @"%SRC_LIST%" || (
    set "EXITCODE=1"
    goto :finalize
)

rem === Starten ===
if /I "%MODE%"=="CLI" (
    java -cp "%OUT_DIR%" backend.client.Client
) else (
    java -cp "%OUT_DIR%" frontend.client.ClientMain
)
set "EXITCODE=%ERRORLEVEL%"
goto :finalize

:showHelp
echo.
echo Quickstart Optionen
echo   quickstartClient.bat            - startet die grafische Client-Anwendung.
echo   quickstartClient.bat -nogui     - startet den Konsolen-Client.  ^(Alias: noGUI^)
echo   quickstartClient.bat -help      - zeigt diese Hilfe direkt an.
echo.
echo Beispiel: quickstartClient.bat -nogui
set "EXITCODE=0"
goto :finalize

:finalize
rem --- Codepage wiederherstellen, Verzeichnis zuruecksetzen, sauber beenden
if defined OLDCP chcp %OLDCP% >nul
popd
exit /b %EXITCODE%

