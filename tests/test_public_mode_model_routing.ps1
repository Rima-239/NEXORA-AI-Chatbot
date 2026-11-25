# ==============================================================================
# tests/test_public_mode_model_routing.ps1
# Comprehensive Verification Suite for NEXORA Public Deployment Model Routing
# Tests:
# 1. PUBLIC mode environment and config enforcement
# 2. Local model hiding in /api/models and /api/models/installed
# 3. Complete rejection of LOCAL provider and Ollama models in /api/chat (HTTP 400)
# 4. Model settings isolation in PUBLIC mode
# 5. Model mutations blocked (403 Forbidden)
# 6. OpenRouter free error handling (no circular recommendations, no local buttons)
# 7. Frontend JS validation for dropdown and retry logic
# ==============================================================================

$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Net.Http
$port = 8086
$baseUrl = "http://127.0.0.1:$port"

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host " NEXORA PUBLIC MODE MODEL ROUTING & SECURITY VERIFICATION SUITE " -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

$passed = 0
$total = 0

function Assert-Test($testName, $condition, $details) {
    $global:total++
    if ($condition) {
        $global:passed++
        Write-Host " [PASS] $testName" -ForegroundColor Green
        if ($details) { Write-Host "        $details" -ForegroundColor Gray }
    } else {
        Write-Host " [FAIL] $testName" -ForegroundColor Red
        if ($details) { Write-Host "        $details" -ForegroundColor DarkRed }
    }
}

Write-Host "`n>>> Starting dedicated NEXORA instance in PUBLIC mode on port $port..." -ForegroundColor Yellow
$pinfo = New-Object System.Diagnostics.ProcessStartInfo
$pinfo.FileName = "java"
$pinfo.Arguments = "-cp bin com.nexora.Main"
$pinfo.EnvironmentVariables["PORT"] = "$port"
$pinfo.EnvironmentVariables["APP_MODE"] = "PUBLIC"
$pinfo.UseShellExecute = $false
$pinfo.RedirectStandardOutput = $true
$pinfo.RedirectStandardError = $true
$pinfo.CreateNoWindow = $true

$serverProcess = [System.Diagnostics.Process]::Start($pinfo)
Start-Sleep -Seconds 4

try {
    # -------------------------------------------------------------------------
    # SECTION 1: Config & Health in PUBLIC Mode
    # -------------------------------------------------------------------------
    Write-Host "`n>>> [SECTION 1] Config & Health in PUBLIC Mode <<<" -ForegroundColor Yellow

    $config = Invoke-RestMethod -Uri "$baseUrl/api/config" -Method Get
    Assert-Test "1.1 App Mode is PUBLIC" ($config.mode -eq "PUBLIC") "Mode: $($config.mode)"
    Assert-Test "1.2 Provider is OpenRouter (Public Cloud)" ($config.provider -match "OpenRouter") "Provider: $($config.provider)"
    Assert-Test "1.3 Default model is openrouter/free" ($config.default_model -eq "openrouter/free") "Default: $($config.default_model)"
    Assert-Test "1.4 Secrets not exposed in /api/config" ($null -eq $config.apiKey -and $null -eq $config.OPENROUTER_API_KEY) "Keys safely concealed"

    # -------------------------------------------------------------------------
    # SECTION 2: Model Listing & Hiding in PUBLIC Mode
    # -------------------------------------------------------------------------
    Write-Host "`n>>> [SECTION 2] Local Model Hiding & Isolation <<<" -ForegroundColor Yellow

    $installed = Invoke-RestMethod -Uri "$baseUrl/api/models/installed" -Method Get
    Assert-Test "2.1 /api/models/installed reports endpoint disabled" ($installed.endpoint -eq "disabled") "Endpoint: $($installed.endpoint)"
    Assert-Test "2.2 /api/models/installed reports 0 models" ($installed.count -eq 0 -and $installed.models.Count -eq 0) "Models count: $($installed.models.Count)"

    $models = Invoke-RestMethod -Uri "$baseUrl/api/models" -Method Get
    $hasLocalInList = $false
    foreach ($m in $models.models) {
        if ($m -match "qwen" -or $m -match "llama" -and $m -notmatch "/") {
            $hasLocalInList = $true
        }
    }
    Assert-Test "2.3 /api/models hides local Ollama models" (-not $hasLocalInList) "Returned: $($models.models -join ', ')"

    # -------------------------------------------------------------------------
    # SECTION 3: Hard Rejection of Local Ollama in /api/chat
    # -------------------------------------------------------------------------
    Write-Host "`n>>> [SECTION 3] Rejection of Local Ollama in PUBLIC Mode <<<" -ForegroundColor Yellow

    # Test 3.1: Explicit provider = LOCAL
    $blockedLocalProv = $false
    $errMsgLocalProv = ""
    try {
        $body = @{
            provider = "LOCAL"
            model = "qwen2.5:7b"
            message = "Hello"
            stream = $false
        } | ConvertTo-Json
        Invoke-RestMethod -Uri "$baseUrl/api/chat" -Method Post -Body $body -ContentType "application/json"
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 400) {
            $blockedLocalProv = $true
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $errMsgLocalProv = $reader.ReadToEnd()
        }
    }
    Assert-Test "3.1 POST /api/chat with provider: LOCAL rejected with HTTP 400" $blockedLocalProv "Message: $errMsgLocalProv"

    # Test 3.2: Local model name specified with provider: PUBLIC
    $blockedLocalModel = $false
    $errMsgLocalModel = ""
    try {
        $body = @{
            provider = "PUBLIC"
            model = "qwen2.5:3b"
            message = "Hello"
            stream = $false
        } | ConvertTo-Json
        Invoke-RestMethod -Uri "$baseUrl/api/chat" -Method Post -Body $body -ContentType "application/json"
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 400) {
            $blockedLocalModel = $true
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $errMsgLocalModel = $reader.ReadToEnd()
        }
    }
    Assert-Test "3.2 POST /api/chat with local model 'qwen2.5:3b' rejected with HTTP 400" $blockedLocalModel "Message: $errMsgLocalModel"

    # Test 3.3: Streaming chat with LOCAL provider rejected immediately
    $client = [System.Net.Http.HttpClient]::new()
    $content = [System.Net.Http.StringContent]::new('{"message":"Hi","model":"qwen2.5:7b","stream":true,"provider":"LOCAL"}', [System.Text.Encoding]::UTF8, "application/json")
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$baseUrl/api/chat")
    $req.Content = $content
    $response = $client.SendAsync($req).Result
    Assert-Test "3.3 Streaming POST /api/chat with LOCAL provider rejected with HTTP 400" ($response.StatusCode.value__ -eq 400) "Status: $($response.StatusCode)"

    # -------------------------------------------------------------------------
    # SECTION 4: Model Settings & Mutation Protections in PUBLIC Mode
    # -------------------------------------------------------------------------
    Write-Host "`n>>> [SECTION 4] Settings & Mutation Protections <<<" -ForegroundColor Yellow

    $settings = Invoke-RestMethod -Uri "$baseUrl/api/models/settings" -Method Get
    Assert-Test "4.1 Settings defaultProvider is PUBLIC" ($settings.settings.defaultProvider -eq "PUBLIC") "Provider: $($settings.settings.defaultProvider)"
    Assert-Test "4.2 Settings defaultCloudModel is openrouter/free" ($settings.settings.defaultCloudModel -eq "openrouter/free") "Model: $($settings.settings.defaultCloudModel)"

    $pullBlocked = $false
    try {
        Invoke-RestMethod -Uri "$baseUrl/api/models/pull" -Method Post -Body '{"model":"llama3"}' -ContentType "application/json"
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 403) { $pullBlocked = $true }
    }
    Assert-Test "4.3 Model pull returns HTTP 403 Forbidden in PUBLIC mode" $pullBlocked "Pull blocked"

    $deleteBlocked = $false
    try {
        Invoke-RestMethod -Uri "$baseUrl/api/models/delete" -Method Post -Body '{"name":"qwen2.5:7b","confirmed":true}' -ContentType "application/json"
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 403) { $deleteBlocked = $true }
    }
    Assert-Test "4.4 Model delete returns HTTP 403 Forbidden in PUBLIC mode" $deleteBlocked "Delete blocked"

    # -------------------------------------------------------------------------
    # SECTION 5: Frontend Logic Static Audit
    # -------------------------------------------------------------------------
    Write-Host "`n>>> [SECTION 5] Frontend Script Logic Audit <<<" -ForegroundColor Yellow

    $appJs = Get-Content -Raw "frontend/js/app.js"

    # Check 5.1: renderQuickModelDropdown hides local models in PUBLIC mode
    $hasPublicDropdownGuard = $appJs -match "if \(!isPublicMode\)[\s\S]*?Installed Local Models"
    Assert-Test "5.1 renderQuickModelDropdown guards Installed Local Models" $hasPublicDropdownGuard "Dropdown hides local models in PUBLIC mode"

    # Check 5.2: selectQuickModel blocks LOCAL provider in PUBLIC mode
    $hasSelectGuard = $appJs -match "state\.providerInfo\.mode === 'PUBLIC' && provider === 'LOCAL'"
    Assert-Test "5.2 selectQuickModel blocks LOCAL provider in PUBLIC mode" $hasSelectGuard "Selection blocked with warning toast"

    # Check 5.3: showErrorBubble circular router button guard
    $hasCircularGuard = $appJs -match "isAlreadyOpenRouterFree = \(state\.selectedModel === 'openrouter/free'\)" -and
                        $appJs -match "showSwitchToRouterBtn = !isAlreadyOpenRouterFree"
    Assert-Test "5.3 showErrorBubble prevents circular openrouter/free suggestion" $hasCircularGuard "Does not suggest switching if already selected"

    # Check 5.4: showErrorBubble hides local model retry button in PUBLIC mode
    $hasLocalRetryGuard = $appJs -match "showSwitchToLocalBtn = !isPublicMode && isOpenRouterError"
    Assert-Test "5.4 showErrorBubble hides local model retry in PUBLIC mode" $hasLocalRetryGuard "Local retry button hidden on Render/PUBLIC"

    # Check 5.5: triggerSafePromptRetry rate limit cooldown
    $hasRetryCooldown = $appJs -match "consecutiveRateLimitRetries >= 3"
    Assert-Test "5.5 triggerSafePromptRetry enforces cooldown after 3 retries" $hasRetryCooldown "Prevents endless hammering"

} finally {
    Write-Host "`n>>> Stopping PUBLIC mode test server..." -ForegroundColor Yellow
    if ($serverProcess -and -not $serverProcess.HasExited) {
        $serverProcess.Kill()
    }
}

Write-Host "`n================================================================" -ForegroundColor Cyan
$resultColor = if ($passed -eq $total) { "Green" } else { "Red" }
Write-Host " RESULTS: $passed / $total Passed" -ForegroundColor $resultColor
Write-Host "================================================================" -ForegroundColor Cyan

if ($passed -eq $total) { exit 0 } else { exit 1 }
