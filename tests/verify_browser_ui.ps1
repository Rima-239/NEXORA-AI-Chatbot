# NEXORA - Browser & Frontend UI Deep Verification Script
$ErrorActionPreference = "Continue"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " NEXORA Browser & Frontend UI Deep Verification          " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Syntax check frontend JavaScript with Node
Write-Host "`n[Check 1] Checking Vanilla JavaScript syntax with Node..." -ForegroundColor Yellow
$syntaxCheck = & node -c frontend/js/app.js 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "[PASS] frontend/js/app.js syntax is 100% valid (ES6+)." -ForegroundColor Green
} else {
    Write-Host "[FAIL] frontend/js/app.js syntax error: $syntaxCheck" -ForegroundColor Red
}

# 2. Fetch live index HTML from server
Write-Host "`n[Check 2] Fetching live HTML from http://127.0.0.1:8080/..." -ForegroundColor Yellow
$html = Invoke-RestMethod -Uri "http://127.0.0.1:8080/" -Method Get -TimeoutSec 5

$requiredDOMElements = @(
    "modelCenterModal",
    "btnHeaderModelSelect",
    "headerModelName",
    "quickModelDropdown",
    "mcTabInstalled",
    "mcTabCatalog",
    "mcTabProfiles",
    "mcTabPerformance",
    "btnProviderLocal",
    "btnProviderPublic",
    "mcOllamaStatusText",
    "mcOpenRouterStatusText",
    "mcHwBanner",
    "mcPullInput",
    "btnStartPull",
    "mcPullProgressBar",
    "mcInstalledList",
    "mcCatalogSearch",
    "mcCatalogFreeOnly",
    "mcCatalogList",
    "mcSettingsDefaultProvider",
    "mcSettingsDefaultLocalModel",
    "mcCurrentChatModelSelect",
    "mcProfilesList",
    "createProfileModal",
    "mcFavoritesList",
    "mcStatAvgDuration",
    "mcStatAvgTtft",
    "mcStatAvgTps",
    "mcPerfTableBody",
    "mcModelDetailsGrid",
    "confirmDeleteModelModal"
)

$domPassed = $true
foreach ($elemId in $requiredDOMElements) {
    if ($html -match "id=['`"]$elemId['`"]") {
        Write-Host "  [DOM OK] Element #$elemId found in live index.html" -ForegroundColor DarkGreen
    } else {
        Write-Host "  [DOM MISSING] Element #$elemId NOT found in live index.html" -ForegroundColor Red
        $domPassed = $false
    }
}

if ($domPassed) {
    Write-Host "[PASS] All $($requiredDOMElements.Count)/$($requiredDOMElements.Count) Model Center DOM elements present in live server HTML." -ForegroundColor Green
} else {
    Write-Host "[FAIL] Some DOM elements missing from index.html" -ForegroundColor Red
}

# 3. Check CSS classes in live CSS
Write-Host "`n[Check 3] Fetching live CSS from http://127.0.0.1:8080/css/nexora.css..." -ForegroundColor Yellow
$css = Invoke-RestMethod -Uri "http://127.0.0.1:8080/css/nexora.css" -Method Get -TimeoutSec 5

$requiredCSSClasses = @(
    ".mc-nav-tabs",
    ".mc-tab-btn",
    ".mc-card",
    ".mc-card-header",
    ".mc-suitability-pill",
    ".quick-model-menu",
    ".quick-model-item",
    ".mc-metrics-grid",
    ".mc-stat-card",
    ".mc-table",
    ".mc-pull-progress-card",
    ".mc-progress-fill",
    ".mc-models-grid",
    ".mc-profiles-grid"
)

$cssPassed = $true
foreach ($cssCls in $requiredCSSClasses) {
    $escaped = [regex]::Escape($cssCls)
    if ($css -match $escaped) {
        Write-Host "  [CSS OK] Class $cssCls found in live nexora.css" -ForegroundColor DarkGreen
    } else {
        Write-Host "  [CSS MISSING] Class $cssCls NOT found in live nexora.css" -ForegroundColor Red
        $cssPassed = $false
    }
}

if ($cssPassed) {
    Write-Host "[PASS] All $($requiredCSSClasses.Count)/$($requiredCSSClasses.Count) Model Center CSS classes present in live stylesheet." -ForegroundColor Green
} else {
    Write-Host "[FAIL] Some CSS classes missing from stylesheet." -ForegroundColor Red
}

Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host " Browser & Frontend UI Verification Complete             " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
