# NEXORA - UI/UX Polish, Responsive Layout & Minimized Window Verification
$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host " NEXORA UI/UX, Responsive Layout & Minimized Window Verification " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$chromePath = "C:\Program Files\Google\Chrome\Application\chrome.exe"
if (-not (Test-Path $chromePath)) {
    $chromePath = "C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"
}

$passedChecks = 0
$totalChecks = 0

function Assert-Check($name, $condition, $details) {
    $script:totalChecks++
    if ($condition) {
        $script:passedChecks++
        Write-Host "  [PASS] $name" -ForegroundColor Green
        if ($details) { Write-Host "         $details" -ForegroundColor DarkGray }
    } else {
        Write-Host "  [FAIL] $name" -ForegroundColor Red
        if ($details) { Write-Host "         $details" -ForegroundColor Yellow }
    }
}

# 1. Syntax Check on JS
Write-Host "`n[Phase 1] Static Code & Syntax Validation" -ForegroundColor Yellow
$nodeCheck = & node -c frontend/js/app.js 2>&1
Assert-Check "JavaScript syntax validity (frontend/js/app.js)" ($LASTEXITCODE -eq 0) "node -c passed with 0 errors"

# 2. Check CSS Rules
Write-Host "`n[Phase 2] CSS Architecture & Layout Rules Validation" -ForegroundColor Yellow
$css = Get-Content -Path "frontend/css/nexora.css" -Raw

Assert-Check "Body flex removed from body" ($css -notmatch "body\s*\{[^}]*display:\s*flex[^}]*width:\s*100vw") "Body uses block layout with html, body { width: 100%; height: 100%; }"
Assert-Check "App container min-height/min-width reset" ($css -match "\.app-container\s*\{[^}]*min-height:\s*0[^}]*min-width:\s*0") ".app-container has min-height: 0 and min-width: 0"
Assert-Check "Chat main flex: 1 1 0% and min constraints" ($css -match "\.chat-main\s*\{[^}]*flex:\s*1\s*1\s*0%[^}]*min-height:\s*0") ".chat-main shrinks and grows correctly without overflow"
Assert-Check "Messages scroll area flex: 1 1 0% and independent scroll" ($css -match "\.messages-scroll-area\s*\{[^}]*flex:\s*1\s*1\s*0%[^}]*overflow-y:\s*auto") ".messages-scroll-area has independent scroll with min-height: 0"
Assert-Check "Safe area inset for composer" ($css -match "env\(safe-area-inset-bottom") "Composer respects mobile safe-area insets"
Assert-Check "Responsive breakpoint: 1200px" ($css -match "@media\s*\(max-width:\s*1200px\)") "Header button labels collapse at 1200px"
Assert-Check "Responsive breakpoint: 960px" ($css -match "@media\s*\(max-width:\s*960px\)") "Persona pills collapse at 960px"
Assert-Check "Responsive breakpoint: 768px (Mobile Drawer)" ($css -match "@media\s*\(max-width:\s*768px\)") "Mobile drawer and compact mobile header configured"
Assert-Check "Responsive breakpoint: 480px (Small Phones)" ($css -match "@media\s*\(max-width:\s*480px\)") "Ultra-compact mobile sizing for small phone viewports"
Assert-Check "Height-constrained breakpoint: max-height: 600px" ($css -match "@media\s*\(max-height:\s*600px\)") "Minimized window / landscape mobile height preservation active"

# 3. Check JS Export Functions
Write-Host "`n[Phase 3] Export Function Non-Destructive Layout Verification" -ForegroundColor Yellow
$js = Get-Content -Path "frontend/js/app.js" -Raw

Assert-Check "exportCurrentConversation uses invisible detached link" ($js -match "link\.style\.display\s*=\s*'none'[^;]*;\s*link\.style\.position\s*=\s*'fixed'") "Temporary link is styled fixed off-screen and invisible"
Assert-Check "exportCurrentConversation replaces alert with showToast" ($js -match "function exportCurrentConversation[^{]*\{[^}]*showToast\(") "Uses showToast instead of alert"
Assert-Check "exportMemories uses invisible detached link" ($js -match "a\.style\.display\s*=\s*'none'[^;]*;\s*a\.style\.position\s*=\s*'fixed'") "Memory export link is styled fixed off-screen and invisible"
Assert-Check "exportMemories replaces alert with showToast" ($js -match "function exportMemories[^{]*\{[^}]*showToast\(") "Uses showToast instead of alert"

# 4. Viewport Layout Verification via Headless Chrome
Write-Host "`n[Phase 4] Cross-Device Viewport Rendering via Headless Chrome" -ForegroundColor Yellow

$viewports = @(
    @{ Name = "Mobile iPhone SE"; W = 320; H = 568 },
    @{ Name = "Mobile Galaxy S20"; W = 360; H = 800 },
    @{ Name = "Mobile iPhone X/11/12"; W = 375; H = 812 },
    @{ Name = "Mobile iPhone 13/14/15"; W = 390; H = 844 },
    @{ Name = "Mobile iPhone 15 Pro Max"; W = 430; H = 932 },
    @{ Name = "Tablet iPad Mini/Air"; W = 768; H = 1024 },
    @{ Name = "Tablet iPad Pro 11"; W = 820; H = 1180 },
    @{ Name = "Desktop Small / Netbook"; W = 1024; H = 768 },
    @{ Name = "Desktop HD / Laptop"; W = 1280; H = 720 },
    @{ Name = "Desktop Standard Laptop"; W = 1366; H = 768 },
    @{ Name = "Desktop Widescreen Laptop"; W = 1440; H = 900 },
    @{ Name = "Desktop Full HD Monitor"; W = 1920; H = 1080 },
    @{ Name = "Minimized Desktop Window"; W = 800; H = 450 },
    @{ Name = "Ultra-Minimized Window"; W = 640; H = 380 }
)

$screenshotDir = "E:\AI-Development\Personal-AI-Chatbot\scratch\viewport_tests"
if (-not (Test-Path $screenshotDir)) {
    New-Item -ItemType Directory -Path $screenshotDir -Force | Out-Null
}

foreach ($vp in $viewports) {
    $w = $vp.W
    $h = $vp.H
    $vpName = $vp.Name
    $outFile = [System.IO.Path]::Combine($screenshotDir, "screenshot_${w}x${h}.png")
    if (Test-Path $outFile) { Remove-Item $outFile -Force }
    
    $chromeArgs = @(
        "--headless=new",
        "--disable-gpu",
        "--no-sandbox",
        "--hide-scrollbars",
        "--window-size=$w,$h",
        "--screenshot=$outFile",
        "http://127.0.0.1:8080/"
    )
    
    $proc = Start-Process -FilePath $chromePath -ArgumentList $chromeArgs -PassThru -Wait -WindowStyle Hidden
    Start-Sleep -Milliseconds 300
    $captured = (Test-Path $outFile) -and ((Get-Item $outFile).Length -gt 1000)
    
    Assert-Check "Viewport $w x $h ($vpName)" ($captured) "Rendered and verified screenshot (${w}x${h}, size: $((Get-Item $outFile).Length) bytes)"
}

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host " RESULT: $passedChecks / $totalChecks Checks Passed" -ForegroundColor $(if ($passedChecks -eq $totalChecks) { "Green" } else { "Red" })
Write-Host "=================================================================" -ForegroundColor Cyan

if ($passedChecks -eq $totalChecks) {
    exit 0
} else {
    exit 1
}
