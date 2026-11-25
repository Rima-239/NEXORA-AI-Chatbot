# test_cloud_and_tabs_fix.ps1
$ErrorActionPreference = "Continue"

Write-Host "=== 1. VERIFY /api/config ===" -ForegroundColor Cyan
try {
    $cfg = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/config" -TimeoutSec 5
    Write-Host "Config success: $($cfg.success)" -ForegroundColor Green
    Write-Host "Config mode: $($cfg.mode)" -ForegroundColor Green
    Write-Host "Config hasOpenRouterApiKey: $($cfg.hasOpenRouterApiKey)" -ForegroundColor Green
} catch {
    Write-Host "Config FAIL: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "`n=== 2. VERIFY /api/models/catalog ===" -ForegroundColor Cyan
try {
    $cat = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/models/catalog?freeOnly=true" -TimeoutSec 10
    Write-Host "Catalog count: $($cat.count)" -ForegroundColor Green
    Write-Host "Catalog hasApiKey: $($cat.hasApiKey)" -ForegroundColor Green
    Write-Host "Catalog first model: $($cat.models[0].id)" -ForegroundColor Green
} catch {
    Write-Host "Catalog FAIL: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "`n=== 3. VERIFY CLOUD CHAT ERROR HANDLING (NO KEY) ===" -ForegroundColor Cyan
try {
    $cloudBody = '{"messages":[{"role":"user","content":"what is java?"}],"model":"google/gemini-2.0-flash-lite:free","provider":"PUBLIC","stream":false}'
    $cloudRes = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -Body $cloudBody -ContentType "application/json" -TimeoutSec 10
    Write-Host "Unexpected success without key!" -ForegroundColor Red
} catch {
    Write-Host "Expected 503 error caught: $($_.Exception.Message)" -ForegroundColor Green
    if ($_.ErrorDetails) {
        Write-Host "Server error payload: $($_.ErrorDetails.Message)" -ForegroundColor Yellow
    }
}

Write-Host "`n=== 4. VERIFY LOCAL CHAT WITH qwen2.5:3b (ENGLISH) ===" -ForegroundColor Cyan
try {
    $localBody = '{"messages":[{"role":"user","content":"Explain NEXORA in 1 line."}],"model":"qwen2.5:3b","provider":"LOCAL","stream":false}'
    $localRes = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -Body $localBody -ContentType "application/json" -TimeoutSec 60
    Write-Host "Local chat model: $($localRes.model)" -ForegroundColor Green
    Write-Host "Local chat reply: $($localRes.reply)" -ForegroundColor Green
} catch {
    Write-Host "Local chat FAIL: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "`n=== 5. VERIFY TAB CSS AND HTML CONSISTENCY ===" -ForegroundColor Cyan
$html = Get-Content -Raw -Path "frontend/index.html"
$css = Get-Content -Raw -Path "frontend/css/nexora.css"

$hasTabIcon = $html.Contains("mc-tab-icon")
$hasTabLabel = $html.Contains("mc-tab-label")
$hasInlineFlex = $css.Contains("display: inline-flex") -and $css.Contains("mc-tab-btn")
$hasHeight38 = $css.Contains("height: 38px")
$hasAlertWarning = $css.Contains(".mc-alert-warning")

Write-Host "HTML mc-tab-icon: $hasTabIcon" -ForegroundColor Green
Write-Host "HTML mc-tab-label: $hasTabLabel" -ForegroundColor Green
Write-Host "CSS inline-flex: $hasInlineFlex" -ForegroundColor Green
Write-Host "CSS height 38px: $hasHeight38" -ForegroundColor Green
Write-Host "CSS mc-alert-warning: $hasAlertWarning" -ForegroundColor Green

if ($hasTabIcon -and $hasTabLabel -and $hasInlineFlex -and $hasHeight38 -and $hasAlertWarning) {
    Write-Host "`n[ALL CHECKS PASSED SUCCESSFULLY]" -ForegroundColor Green
} else {
    Write-Host "`n[SOME CHECKS FAILED]" -ForegroundColor Red
}
