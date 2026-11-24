# NEXORA - Master Automated Integration & Security Test Suite
$ErrorActionPreference = "Continue"

Write-Host "=============================================================" -ForegroundColor Cyan
Write-Host " NEXORA Master Test Suite: Running All Verification Checks   " -ForegroundColor Cyan
Write-Host "=============================================================" -ForegroundColor Cyan

# 1. Endpoints & Static Assets
Write-Host "`n>>> [SUITE 1/3] Server Health, Config, Models & Assets <<<" -ForegroundColor Yellow
& powershell -ExecutionPolicy Bypass -File .\tests\test_step1.ps1

# 2. PWA, CSS Themes & Screenshots
Write-Host "`n>>> [SUITE 2/3] PWA Compliance, Themes & Screenshots <<<" -ForegroundColor Yellow
& powershell -ExecutionPolicy Bypass -File .\tests\test_pwa_and_ui.ps1

# 3. Security, Rate Limiter & Path Guardrails
Write-Host "`n>>> [SUITE 3/3] Security, Guardrails & Preservation <<<" -ForegroundColor Yellow
& powershell -ExecutionPolicy Bypass -File .\tests\verify_security_precise.ps1

Write-Host "`n=============================================================" -ForegroundColor Cyan
Write-Host " All Master Verification Checks Finished                     " -ForegroundColor Cyan
Write-Host "=============================================================" -ForegroundColor Cyan
