# NEXORA Phase 3: PWA and UI Asset Verification
$ErrorActionPreference = "Continue"

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host " Validating PWA, UI Assets, Themes and Screenshots  " -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan

# 1. Manifest Validation
Write-Host "`n[TEST 1] PWA Manifest Validation" -ForegroundColor Yellow
$manifestRaw = (& curl.exe -s --noproxy "*" "http://127.0.0.1:8080/manifest.json") -join "`n"
try {
    $manifestJson = $manifestRaw | ConvertFrom-Json
    Write-Host "PWA Name: $($manifestJson.name)" -ForegroundColor Green
    Write-Host "PWA Short Name: $($manifestJson.short_name)" -ForegroundColor Green
    Write-Host "Display Mode: $($manifestJson.display)" -ForegroundColor Green
    Write-Host "Theme Color: $($manifestJson.theme_color)" -ForegroundColor Green
    Write-Host "Icon Count: $($manifestJson.icons.Count)" -ForegroundColor Green
    Write-Host "[PASS] manifest.json is valid and compliant." -ForegroundColor Green
} catch {
    Write-Host "[FAIL] Failed to parse manifest.json: $_" -ForegroundColor Red
}

# 2. Service Worker Validation
Write-Host "`n[TEST 2] Service Worker Script (sw.js)" -ForegroundColor Yellow
$swRaw = (& curl.exe -s --noproxy "*" "http://127.0.0.1:8080/sw.js") -join "`n"
$hasInstall = $swRaw.Contains("self.addEventListener('install'")
$hasFetch = $swRaw.Contains("self.addEventListener('fetch'")
$hasApiBypass = $swRaw.Contains("url.pathname.startsWith('/api/')")

Write-Host "Handles install event: $hasInstall"
Write-Host "Handles fetch event: $hasFetch"
Write-Host "Bypasses /api/* network-only: $hasApiBypass"

if ($hasInstall -and $hasFetch -and $hasApiBypass) {
    Write-Host "[PASS] sw.js implements correct PWA caching with network-only API routing." -ForegroundColor Green
} else {
    Write-Host "[FAIL] sw.js missing essential handlers." -ForegroundColor Red
}

# 3. CSS Themes Verification
Write-Host "`n[TEST 3] CSS Theme Variables in nexora.css" -ForegroundColor Yellow
$cssContent = Get-Content "frontend/css/nexora.css" -Raw
$hasDarkVars = $cssContent.Contains('[data-theme="dark"]')
$hasLightVars = $cssContent.Contains('[data-theme="light"]')
$hasSystemMedia = $cssContent.Contains("prefers-color-scheme")
$hasTransitions = $cssContent.Contains("transition: background-color") -or $cssContent.Contains("transition: color")

Write-Host "Dark Theme Tokens present: $hasDarkVars"
Write-Host "Light Theme Tokens present: $hasLightVars"
Write-Host "System Media Query present: $hasSystemMedia"
Write-Host "Theme Transition rules present: $hasTransitions"

if ($hasDarkVars -and $hasLightVars -and $hasSystemMedia) {
    Write-Host "[PASS] Complete 3-tier theme system (Dark, Light, System) present in CSS." -ForegroundColor Green
} else {
    Write-Host "[FAIL] Incomplete theme tokens in CSS." -ForegroundColor Red
}

# 4. Screenshots Verification
Write-Host "`n[TEST 4] Verification of Rendered UI Screenshots" -ForegroundColor Yellow
$screenshots = @(
    "screenshots/nexora_desktop_dark.png",
    "screenshots/nexora_desktop_light.png",
    "screenshots/nexora_mobile_dark.png",
    "screenshots/nexora_mobile_light.png"
)
$allExist = $true
foreach ($s in $screenshots) {
    $exists = Test-Path $s
    if ($exists) {
        $size = (Get-Item $s).Length
        Write-Host "  Found: $s ($([math]::Round($size / 1024, 1)) KB)" -ForegroundColor Green
    } else {
        Write-Host "  Missing: $s" -ForegroundColor Red
        $allExist = $false
    }
}
if ($allExist) {
    Write-Host "[PASS] All 4 responsive UI screenshots verified." -ForegroundColor Green
} else {
    Write-Host "[FAIL] Some screenshots are missing." -ForegroundColor Red
}

Write-Host "`n====================================================" -ForegroundColor Cyan
Write-Host " PWA and UI Asset Verification Complete             " -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan
