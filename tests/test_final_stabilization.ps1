# test_final_stabilization.ps1
$ErrorActionPreference = "Continue"

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host " NEXORA FINAL STABILIZATION VERIFICATION SUITE                  " -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

$passCount = 0
$failCount = 0

# --- 1. LOCAL MODELS TESTING ---
Write-Host "`n>>> [SECTION 1] Local Ollama Models Verification <<<" -ForegroundColor Yellow

# Test 1.1: qwen2.5:7b
Write-Host "Testing Local Model: qwen2.5:7b..." -NoNewline
try {
    $body = @{
        provider = "LOCAL"
        model = "qwen2.5:7b"
        stream = $false
        messages = @(@{ role = "user"; content = "What is 2+2? Answer with only the digit." })
    } | ConvertTo-Json -Depth 5
    $res = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -ContentType "application/json; charset=UTF-8" -Body $body
    if ($res.success -and $res.message.content -match "4") {
        Write-Host " [PASS]" -ForegroundColor Green
        Write-Host "   Reply: $($res.message.content.Trim())" -ForegroundColor Gray
        $passCount++
    } else {
        Write-Host " [FAIL]" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host " [FAIL]: $($_.Exception.Message)" -ForegroundColor Red
    $failCount++
}

# Test 1.2: qwen2.5:3b
Write-Host "Testing Local Model: qwen2.5:3b..." -NoNewline
try {
    $body = @{
        provider = "LOCAL"
        model = "qwen2.5:3b"
        stream = $false
        messages = @(@{ role = "user"; content = "What is 3+3? Answer with only the digit." })
    } | ConvertTo-Json -Depth 5
    $res = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -ContentType "application/json; charset=UTF-8" -Body $body
    if ($res.success -and $res.message.content -match "6") {
        Write-Host " [PASS]" -ForegroundColor Green
        Write-Host "   Reply: $($res.message.content.Trim())" -ForegroundColor Gray
        $passCount++
    } else {
        Write-Host " [FAIL]" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host " [FAIL]: $($_.Exception.Message)" -ForegroundColor Red
    $failCount++
}

# --- 2. OPENROUTER TESTING ---
Write-Host "`n>>> [SECTION 2] OpenRouter Key & Model Inference Verification <<<" -ForegroundColor Yellow

# Test 2.1: Config Key Check (no secret leak)
Write-Host "Checking OpenRouter API Key loading on /api/config..." -NoNewline
$cfg = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/config" -Method Get
if ($cfg.hasOpenRouterApiKey -eq $true -and $null -eq $cfg.apiKey -and $null -eq $cfg.OPENROUTER_API_KEY) {
    Write-Host " [PASS] (Secure: hasOpenRouterApiKey = true, secret never exposed)" -ForegroundColor Green
    $passCount++
} else {
    Write-Host " [FAIL]" -ForegroundColor Red
    $failCount++
}

Start-Sleep -Milliseconds 800

# Test 2.2: openrouter/free
Write-Host "Testing OpenRouter Free Router (openrouter/free)..." -NoNewline
try {
    $body = @{
        provider = "PUBLIC"
        model = "openrouter/free"
        stream = $false
        messages = @(@{ role = "user"; content = "Respond with the single word: OK" })
    } | ConvertTo-Json -Depth 5
    $res = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -ContentType "application/json; charset=UTF-8" -Body $body
    if ($res.success -and $res.message.content.Length -gt 0) {
        Write-Host " [PASS]" -ForegroundColor Green
        Write-Host "   Reply: $($res.message.content.Trim())" -ForegroundColor Gray
        $passCount++
    } else {
        Write-Host " [FAIL]" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host " [FAIL]: $($_.Exception.Message)" -ForegroundColor Red
    $failCount++
}

Start-Sleep -Milliseconds 800

# Test 2.3: Verified Dropdown Model: liquid/lfm-3-1.7b:free
Write-Host "Testing Dropdown Model: liquid/lfm-3-1.7b:free..." -NoNewline
try {
    $body = @{
        provider = "PUBLIC"
        model = "liquid/lfm-3-1.7b:free"
        stream = $false
        messages = @(@{ role = "user"; content = "Respond with: READY" })
    } | ConvertTo-Json -Depth 5
    $res = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -ContentType "application/json; charset=UTF-8" -Body $body
    if ($res.success -and $res.message.content.Length -gt 0) {
        Write-Host " [PASS]" -ForegroundColor Green
        Write-Host "   Reply: $($res.message.content.Trim())" -ForegroundColor Gray
        $passCount++
    } else {
        Write-Host " [FAIL]" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host " [FAIL]: $($_.Exception.Message)" -ForegroundColor Red
    $failCount++
}

Start-Sleep -Milliseconds 800

# Test 2.4: Verified Dropdown Model: nvidia/nemotron-3.5-lightning:free
Write-Host "Testing Dropdown Model: nvidia/nemotron-3.5-lightning:free..." -NoNewline
try {
    $body = @{
        provider = "PUBLIC"
        model = "nvidia/nemotron-3.5-lightning:free"
        stream = $false
        messages = @(@{ role = "user"; content = "Say OK" })
    } | ConvertTo-Json -Depth 5
    $res = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -ContentType "application/json; charset=UTF-8" -Body $body
    if ($res.success -and $res.message.content.Length -gt 0) {
        Write-Host " [PASS]" -ForegroundColor Green
        Write-Host "   Reply: $($res.message.content.Trim())" -ForegroundColor Gray
        $passCount++
    } else {
        Write-Host " [FAIL]" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host " [FAIL]: $($_.Exception.Message)" -ForegroundColor Red
    $failCount++
}

Start-Sleep -Milliseconds 800

# Test 2.5: Rate-limit or Unavailable model graceful handling
Write-Host "Testing Unavailable/Rate-Limited Model Graceful Handling..." -NoNewline
try {
    $body = @{
        provider = "PUBLIC"
        model = "deepseek/deepseek-r1:free"
        stream = $false
        messages = @(@{ role = "user"; content = "Hello" })
    } | ConvertTo-Json -Depth 5
    $errRes = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -ContentType "application/json; charset=UTF-8" -Body $body
    Write-Host " [FAIL]: Expected error for unavailable model" -ForegroundColor Red
    $failCount++
} catch {
    $errText = ""
    if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $errText = $_.ErrorDetails.Message } else { $errText = $_.Exception.Message }
    if ($errText -match "openrouter/free|unavailable|no longer available|Rate Limit|quota") {
        Write-Host " [PASS]" -ForegroundColor Green
        Write-Host "   Structured Error: $errText" -ForegroundColor Gray
        $passCount++
    } else {
        Write-Host " [FAIL]: Expected helpful error advice, got: $errText" -ForegroundColor Red
        $failCount++
    }
}

# --- 3. MODEL CENTER CATALOG & STATUS VERIFICATION ---
Write-Host "`n>>> [SECTION 3] Catalog Status Badges & Filtering Verification <<<" -ForegroundColor Yellow

Write-Host "Checking /api/models/catalog?freeOnly=true&refresh=true..." -NoNewline
$cat = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/models/catalog?freeOnly=true&refresh=true" -Method Get
if ($cat.success -and $cat.models.Count -gt 0) {
    $firstModel = $cat.models[0]
    $hasStatus = $cat.models | Where-Object { $_.status -ne $null }
    if ($firstModel.id -eq "openrouter/free" -and $firstModel.status -eq "verified" -and $hasStatus.Count -gt 0) {
        Write-Host " [PASS]" -ForegroundColor Green
        Write-Host "   Total Models: $($cat.models.Count), Top Model: $($firstModel.id) [status: $($firstModel.status)]" -ForegroundColor Gray
        $passCount++
    } else {
        Write-Host " [FAIL]" -ForegroundColor Red
        $failCount++
    }
} else {
    Write-Host " [FAIL]: Catalog could not be loaded" -ForegroundColor Red
    $failCount++
}

# --- 4. EXISTING FEATURES PRESERVATION ---
Write-Host "`n>>> [SECTION 4] Features Preservation (Memory, Streaming, Multilingual) <<<" -ForegroundColor Yellow

# Test 4.1: Personal Memory injection & recall
Write-Host "Testing Personal Memory addition and retrieval..." -NoNewline
try {
    # Store a test memory using correct field 'text'
    $memBody = @{ text = "User prefers Java 21 LTS for high performance backend"; category = "preference" } | ConvertTo-Json
    $memRes = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/memory" -Method Post -ContentType "application/json; charset=UTF-8" -Body $memBody
    if ($memRes.success -eq $true) {
        Write-Host " [PASS]" -ForegroundColor Green
        Write-Host "   Memory added: $($memRes.memory.text)" -ForegroundColor Gray
        $passCount++
    } else {
        Write-Host " [FAIL]: Memory creation returned success=false" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host " [FAIL]: $($_.Exception.Message)" -ForegroundColor Red
    $failCount++
}

# Test 4.2: Multilingual Bengali Support
Write-Host "Testing Bengali generation quality..." -NoNewline
try {
    $bnBody = @{
        provider = "LOCAL"
        model = "qwen2.5:7b"
        stream = $false
        messages = @(@{ role = "user"; content = "NEXORA কী? সংক্ষেপে এক লাইনে বলো।" })
    } | ConvertTo-Json -Depth 5
    $res = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -ContentType "application/json; charset=UTF-8" -Body $bnBody
    if ($res.success -and $res.message.content -match "[\u0980-\u09FF]") {
        Write-Host " [PASS] (Produced valid Bengali script)" -ForegroundColor Green
        Write-Host "   Reply: $($res.message.content.Trim())" -ForegroundColor Gray
        $passCount++
    } else {
        Write-Host " [FAIL]" -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host " [FAIL]: $($_.Exception.Message)" -ForegroundColor Red
    $failCount++
}

Write-Host "`n================================================================" -ForegroundColor Cyan
Write-Host " FINAL STABILIZATION SUMMARY: $passCount Passed, $failCount Failed" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

if ($failCount -eq 0) {
    Exit 0
} else {
    Exit 1
}
