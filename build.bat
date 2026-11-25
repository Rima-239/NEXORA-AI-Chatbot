@echo off
REM NEXORA Windows Build Script
REM Compiles Core Java 21 classes into bin/

echo [NEXORA] Compiling Core Java backend...
if not exist "bin" mkdir bin

javac -d bin -encoding UTF-8 -sourcepath src src/com/nexora/Main.java src/com/nexora/model/*.java src/com/nexora/server/*.java src/com/nexora/controller/*.java src/com/nexora/service/*.java src/com/nexora/util/*.java

if %ERRORLEVEL% equ 0 (
    echo [NEXORA] Build Successful! Output directory: bin/
) else (
    echo [NEXORA] Build Failed with error code %ERRORLEVEL%
    exit /b %ERRORLEVEL%
)
