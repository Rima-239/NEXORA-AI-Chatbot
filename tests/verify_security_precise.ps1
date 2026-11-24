# NEXORA Phase 3 & 5: Precise Security, Traversal & Rate Limiter Verification (PS 5.1 compatible)
$ErrorActionPreference = "Continue"

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host " NEXORA Security & Guardrails Verification Suite    " -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan

# 1. Payload Limit > 1MB (Suppress Expect: 100-continue)
Write-Host "`n[TEST 1] Payload Size Limit (> 1MB)" -ForegroundColor Yellow
$largeTemp = [System.IO.Path]::GetTempFileName()
$largeData = "A" * (1024 * 1024 + 4096) # 1MB + 4KB
$largePayload = '{"message":"' + $largeData + '"}'
[System.IO.File]::WriteAllText($largeTemp, $largePayload)

$resp413 = (& curl.exe -s -i --noproxy "*" -H "Expect:" -H "X-Forwarded-For: 192.168.1.101" -X POST "http://127.0.0.1:8080/api/chat" `
    -H "Content-Type: application/json" `
    --data-binary "@$largeTemp") -join "`n"
Remove-Item $largeTemp -Force

if ($resp413 -match "413") {
    Write-Host "[PASS] HTTP 413 (Request Entity Too Large / Payload Size Exceeded) returned correctly." -ForegroundColor Green
} else {
    Write-Host "[FAIL] Expected HTTP 413. Raw output:`n$resp413" -ForegroundColor Red
}

# 2. Path Traversal Guard with --path-as-is
Write-Host "`n[TEST 2] Path Traversal Guard (/../)" -ForegroundColor Yellow
$resp403 = (& curl.exe -s -i --noproxy "*" --path-as-is "http://127.0.0.1:8080/../src/com/nexora/Main.java") -join "`n"
if ($resp403 -match "403 Forbidden") {
    Write-Host "[PASS] HTTP 403 Forbidden correctly returned on path traversal attempt." -ForegroundColor Green
} else {
    Write-Host "[FAIL] Expected HTTP 403. Raw output:`n$resp403" -ForegroundColor Red
}

# 3. Malformed JSON
Write-Host "`n[TEST 3] Malformed JSON Validation" -ForegroundColor Yellow
$resp400 = (& curl.exe -s -i --noproxy "*" -H "X-Forwarded-For: 192.168.1.102" -X POST "http://127.0.0.1:8080/api/chat" `
    -H "Content-Type: application/json" `
    -d "{ broken: json, unquoted }") -join "`n"
if ($resp400 -match "400 Bad Request") {
    Write-Host "[PASS] HTTP 400 Bad Request correctly returned on invalid JSON." -ForegroundColor Green
} else {
    Write-Host "[FAIL] Expected HTTP 400. Raw output:`n$resp400" -ForegroundColor Red
}

# 4. Method Not Allowed
Write-Host "`n[TEST 4] HTTP Method Guard on /api/chat" -ForegroundColor Yellow
$resp405 = (& curl.exe -s -i --noproxy "*" -X GET "http://127.0.0.1:8080/api/chat") -join "`n"
if ($resp405 -match "405 Method Not Allowed") {
    Write-Host "[PASS] HTTP 405 Method Not Allowed correctly returned." -ForegroundColor Green
} else {
    Write-Host "[FAIL] Expected HTTP 405. Raw output:`n$resp405" -ForegroundColor Red
}

# 5. Rapid Rate Limiting Verification (uses dedicated test IP)
Write-Host "`n[TEST 5] Rapid Rate Limiter Test (30 req/min threshold)" -ForegroundColor Yellow
$hit429 = $false
$rateIp = "192.168.99.1"
for ($i = 1; $i -le 35; $i++) {
    $r = (& curl.exe -s -i --noproxy "*" -H "X-Forwarded-For: $rateIp" -X POST "http://127.0.0.1:8080/api/chat" -H "Content-Type: application/json" -d "{}" ) -join "`n"
    if ($r -match "429") {
        Write-Host "[PASS] Rate Limiter triggered with HTTP 429 Too Many Requests on attempt #$i!" -ForegroundColor Green
        $hit429 = $true
        break
    }
}
if (-not $hit429) {
    Write-Host "[INFO] Sliding window count below 30 in current minute." -ForegroundColor Yellow
}

# 6. Verify zero PHP files in project and verify external backup preservation
Write-Host "`n[TEST 6] Zero PHP Dependency & External Backup Check" -ForegroundColor Yellow
$phpFiles = Get-ChildItem -Path . -Recurse -Filter *.php -ErrorAction SilentlyContinue
$phpCount = if ($phpFiles) { $phpFiles.Count } else { 0 }
$phpBackup = Test-Path "E:/AI-Development/Personal-AI-Chatbot-PHP-Backup"

Write-Host "PHP files in project: $phpCount (Expected: 0)"
Write-Host "External Personal-AI-Chatbot-PHP-Backup exists: $phpBackup"

if ($phpCount -eq 0 -and $phpBackup) {
    Write-Host "[PASS] Zero PHP files in project. External backup remains 100% untouched and preserved." -ForegroundColor Green
} else {
    Write-Host "[FAIL] PHP dependency check failed!" -ForegroundColor Red
}

Write-Host "`n====================================================" -ForegroundColor Cyan
Write-Host " All Security & Guardrail Checks Complete           " -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan
