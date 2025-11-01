@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem Immer vom Skriptordner aus arbeiten
pushd "%~dp0"

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

rem === Alle .java-Dateien sammeln: Backslashes -> Forward-Slashes, und quoten ===
(
  for /r "src" %%F in (*.java) do (
    set "p=%%~fF"
    set "p=!p:\=/!"
    echo "!p!"
  )
) > "%SRC_LIST%" || (
    set "EXITCODE=1"
    goto :finalize
)

rem === Kompilieren ===
javac -d "%OUT_DIR%" @"%SRC_LIST%" || (
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
exit /b 0
