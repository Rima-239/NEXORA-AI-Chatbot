# NEXORA Phase 3 & 5: Server Health & Static Asset Verification
$ErrorActionPreference = "Continue"

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host " NEXORA Server Health & Static Asset Test Suite     " -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan

# Check if server is already responding
$isLive = $false
try {
    $preCheck = (& curl.exe -s --noproxy "*" -o NUL -w "%{http_code}" http://127.0.0.1:8080/api/health)
    if ($preCheck -eq "200") {
        $isLive = $true
        Write-Host "[INFO] NEXORA Java server is already running on port 8080." -ForegroundColor Green
    }
} catch {
    $isLive = $false
}

if (-not $isLive) {
    # Check if port is held by another process
    $existing = Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
    if ($existing) {
        Write-Host "[SETUP] Freeing port 8080 (PID: $($existing.OwningProcess))..." -ForegroundColor Yellow
        Stop-Process -Id $existing.OwningProcess -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 1
    }

    Write-Host "[SETUP] Starting Nexora Java Server..." -ForegroundColor Yellow
    $proc = Start-Process java -ArgumentList "-cp","bin","com.nexora.Main" -PassThru -NoNewWindow
    Start-Sleep -Seconds 2

    if ($proc.HasExited) {
        Write-Host "[FAIL] Java server failed to start!" -ForegroundColor Red
        exit 1
    }
    Write-Host "[PASS] Java server started with PID: $($proc.Id)" -ForegroundColor Green
}

Write-Host "`n--- Verifying Server Endpoints & Static Assets ---" -ForegroundColor Cyan
$healthRaw = (& curl.exe -s --noproxy "*" -i http://127.0.0.1:8080/api/health) -join "`n"
Write-Host "[TEST 1.1] GET /api/health:`n$healthRaw"

$configRaw = (& curl.exe -s --noproxy "*" -i http://127.0.0.1:8080/api/config) -join "`n"
Write-Host "[TEST 1.2] GET /api/config:`n$configRaw"

$modelsRaw = (& curl.exe -s --noproxy "*" -i http://127.0.0.1:8080/api/models) -join "`n"
Write-Host "[TEST 1.3] GET /api/models:`n$modelsRaw"

$indexHeader = (& curl.exe -s --noproxy "*" -I http://127.0.0.1:8080/) -join "`n"
Write-Host "[TEST 1.4] GET / (Static Index):`n$indexHeader"

$manifestHeader = (& curl.exe -s --noproxy "*" -I http://127.0.0.1:8080/manifest.json) -join "`n"
Write-Host "[TEST 1.5] GET /manifest.json:`n$manifestHeader"

$swHeader = (& curl.exe -s --noproxy "*" -I http://127.0.0.1:8080/sw.js) -join "`n"
Write-Host "[TEST 1.6] GET /sw.js:`n$swHeader"

Write-Host "`n====================================================" -ForegroundColor Cyan
Write-Host " Server & Static Assets Verification Complete       " -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan
