@echo off
setlocal
cls
cd /d "%~dp0"

del /q "classes\*.class" >nul 2>&1

javac -cp "lib\sqlite-jdbc-3.50.1.0.jar" -d "classes" src\backend\server\*.java src\backend\client\*.java src\frontend\server\*.java >nul 2>&1 || goto :eof

java --enable-native-access=ALL-UNNAMED -cp "classes;lib\sqlite-jdbc-3.50.1.0.jar" frontend.server.ServerDashboardFrame

endlocal
exit /b
