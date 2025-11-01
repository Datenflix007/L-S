@echo off
setlocal
cls
cd /d "%~dp0"
del /q "classes\*.class" 2>nul
javac -cp "lib\sqlite-jdbc-3.50.1.0.jar" -d "classes" src\backend\server\*.java src\backend\client\*.java
java --enable-native-access=ALL-UNNAMED -cp "classes;lib\sqlite-jdbc-3.50.1.0.jar" backend.server.dummy_App
del /q "classes\*.class" 2>nul
endlocal
