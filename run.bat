@echo off
REM NEXORA Windows Launch Script
REM Starts the Core Java 21 HTTP server

if not exist "bin\com\nexora\Main.class" (
    echo [NEXORA] Binaries not found. Building project first...
    call build.bat
    if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%
)

echo [NEXORA] Starting NEXORA server...
java -cp bin com.nexora.Main
