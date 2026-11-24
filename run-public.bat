@echo off
setlocal
echo =============================================================
echo   NEXORA - Starting in Public Cloud Mode (OpenRouter)
echo =============================================================

if "%OPENROUTER_API_KEY%"=="" (
    echo [WARNING] OPENROUTER_API_KEY environment variable is not set.
    echo Please set your key using:
    echo   set OPENROUTER_API_KEY=your_openrouter_api_key_here
    echo.
)

set APP_MODE=PUBLIC
if "%PORT%"=="" set PORT=8080

java -cp bin com.nexora.Main
endlocal
