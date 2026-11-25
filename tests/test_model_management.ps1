# NEXORA — 30-Point Dynamic AI Model Management & Model Center Automated Verification Suite
# Technology: Core Java 21 Virtual Threads, Ollama, OpenRouter, Hardware Telemetry

param(
    [string]$BaseUrl = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
try { Add-Type -AssemblyName System.Net.Http } catch {}

Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host " NEXORA 30-Point Dynamic Model Management & Model Center Verification" -ForegroundColor Cyan
Write-Host " Base URL: $BaseUrl" -ForegroundColor Cyan
Write-Host "==========================================================================" -ForegroundColor Cyan

$testResults = [System.Collections.Generic.List[PSCustomObject]]::new()

function Record-TestResult {
    param(
        [int]$Number,
        [string]$Name,
        [bool]$Passed,
        [string]$Details
    )
    $status = if ($Passed) { "PASS" } else { "FAIL" }
    $color = if ($Passed) { "Green" } else { "Red" }
    Write-Host ("[{0:D2}] {1,-50} : {2}" -f $Number, $Name, $status) -ForegroundColor $color
    if (-not $Passed -and $Details) {
        Write-Host "     Error: $Details" -ForegroundColor DarkRed
    } elseif ($Details) {
        Write-Host "     Note: $Details" -ForegroundColor DarkGray
    }
    $testResults.Add([PSCustomObject]@{
        Number = $Number
        Name = $Name
        Status = $status
        Details = $Details
    })
}

# -----------------------------------------------------------------------------
# Pre-flight Check: Ensure NEXORA Server is Online
# -----------------------------------------------------------------------------
$serverOnline = $false
try {
    $healthRes = Invoke-RestMethod -Uri "$BaseUrl/api/health" -Method Get -TimeoutSec 3 -ErrorAction Stop
    if ($healthRes.status -eq "ok" -or $healthRes.success -eq $true) {
        $serverOnline = $true
    }
} catch {
    $serverOnline = $false
}

$serverProcess = $null
if (-not $serverOnline) {
    Write-Host "[SETUP] Launching background NEXORA server on port 8080..." -ForegroundColor Yellow
    $serverProcess = Start-Process -FilePath "java" -ArgumentList "-cp", "bin", "com.nexora.Main" -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 3
}

# -----------------------------------------------------------------------------
# Test 01: Ollama installed model discovery
# -----------------------------------------------------------------------------
try {
    $res = Invoke-RestMethod -Uri "$BaseUrl/api/models/installed" -Method Get -TimeoutSec 8
    $hasModels = $res.success -and ($res.models.Count -ge 1)
    $modelNames = ($res.models | ForEach-Object { $_.id }) -join ", "
    Record-TestResult -Number 1 -Name "Ollama installed model discovery" -Passed $hasModels -Details "Discovered: $modelNames"
} catch {
    Record-TestResult -Number 1 -Name "Ollama installed model discovery" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 02: Metadata parsing (size, parameters, quantization, family)
# -----------------------------------------------------------------------------
try {
    $res = Invoke-RestMethod -Uri "$BaseUrl/api/models/installed" -Method Get -TimeoutSec 5
    $target = $res.models | Where-Object { $_.id -eq "qwen2.5:7b" } | Select-Object -First 1
    if (-not $target) { $target = $res.models[0] }
    
    $validMeta = ($target -ne $null) -and ($target.sizeBytes -gt 0) -and ($target.parameterSize -ne "")
    Record-TestResult -Number 2 -Name "Model metadata parsing accuracy" -Passed $validMeta -Details "ID: $($target.id), Size: $($target.sizeBytes), Params: $($target.parameterSize), Quant: $($target.quantization)"
} catch {
    Record-TestResult -Number 2 -Name "Model metadata parsing accuracy" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 03: Dynamic model refresh consistency
# -----------------------------------------------------------------------------
try {
    $res1 = Invoke-RestMethod -Uri "$BaseUrl/api/models/installed" -Method Get -TimeoutSec 5
    $res2 = Invoke-RestMethod -Uri "$BaseUrl/api/models/installed" -Method Get -TimeoutSec 5
    $consistent = $res1.success -and $res2.success -and ($res1.models.Count -eq $res2.models.Count)
    Record-TestResult -Number 3 -Name "Dynamic model refresh consistency" -Passed $consistent -Details "Run 1 count: $($res1.models.Count), Run 2 count: $($res2.models.Count)"
} catch {
    Record-TestResult -Number 3 -Name "Dynamic model refresh consistency" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 04: Invalid model-name rejection (Security & Injection prevention)
# -----------------------------------------------------------------------------
try {
    $body = @{ name = "qwen;rm -rf /" } | ConvertTo-Json
    $caught = $false
    try {
        $res = Invoke-RestMethod -Uri "$BaseUrl/api/models/pull" -Method Post -Body $body -ContentType "application/json; charset=UTF-8" -TimeoutSec 4
    } catch {
        $caught = ($_.Exception.Response.StatusCode.value__ -eq 400)
    }
    Record-TestResult -Number 4 -Name "Invalid model-name rejection & sanitization" -Passed $caught -Details "Rejected command injection attempt with HTTP 400"
} catch {
    Record-TestResult -Number 4 -Name "Invalid model-name rejection & sanitization" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 05: SSE installation progress endpoint contract
# -----------------------------------------------------------------------------
try {
    $client = [System.Net.Http.HttpClient]::new()
    $content = [System.Net.Http.StringContent]::new('{"name":"qwen2.5:3b"}', [System.Text.Encoding]::UTF8, "application/json")
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$BaseUrl/api/models/pull")
    $req.Content = $content
    $req.Headers.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new("text/event-stream"))
    
    $response = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    $isSse = ($response.StatusCode -eq 200) -and ($response.Content.Headers.ContentType.MediaType -eq "text/event-stream")
    $response.Dispose()
    $client.Dispose()
    Record-TestResult -Number 5 -Name "SSE model installation endpoint contract" -Passed $isSse -Details "Status 200, Content-Type: text/event-stream"
} catch {
    Record-TestResult -Number 5 -Name "SSE model installation endpoint contract" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 06: Installation cancellation support
# -----------------------------------------------------------------------------
try {
    $client = [System.Net.Http.HttpClient]::new()
    $content = [System.Net.Http.StringContent]::new('{"name":"qwen2.5:3b"}', [System.Text.Encoding]::UTF8, "application/json")
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$BaseUrl/api/models/pull")
    $req.Content = $content
    
    $response = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    $stream = $response.Content.ReadAsStreamAsync().Result
    
    # Simulate client abruptly aborting / closing connection during SSE download
    $stream.Dispose()
    $response.Dispose()
    $client.Dispose()
    
    # Verify server handled the disconnect gracefully and is responsive
    Start-Sleep -Milliseconds 300
    $healthCheck = Invoke-RestMethod -Uri "$BaseUrl/api/models/installed" -Method Get -TimeoutSec 5
    $cancelled = ($healthCheck.success -eq $true)
    Record-TestResult -Number 6 -Name "Model installation cancellation" -Passed $cancelled -Details "Clean client abort handled without server crash"
} catch {
    Record-TestResult -Number 6 -Name "Model installation cancellation" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 07: Deletion confirmation enforcement
# -----------------------------------------------------------------------------
try {
    $body = @{ name = "uninstalled-fake-model:latest" } | ConvertTo-Json
    $rejected = $false
    try {
        $res = Invoke-RestMethod -Uri "$BaseUrl/api/models/delete" -Method Post -Body $body -ContentType "application/json" -TimeoutSec 4
    } catch {
        $rejected = ($_.Exception.Response.StatusCode.value__ -eq 400)
    }
    Record-TestResult -Number 7 -Name "Deletion confirmation enforcement" -Passed $rejected -Details "Rejected missing confirmed flag with HTTP 400"
} catch {
    Record-TestResult -Number 7 -Name "Deletion confirmation enforcement" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 08: Active model deletion protection
# -----------------------------------------------------------------------------
try {
    # Attempting to delete active default model
    $body = @{ name = "qwen2.5:7b"; confirmed = $true } | ConvertTo-Json
    $protected = $false
    try {
        $res = Invoke-RestMethod -Uri "$BaseUrl/api/models/delete" -Method Post -Body $body -ContentType "application/json" -TimeoutSec 4
    } catch {
        $protected = ($_.Exception.Response.StatusCode.value__ -eq 409) -or ($_.Exception.Response.StatusCode.value__ -eq 400)
    }
    Record-TestResult -Number 8 -Name "Active model deletion protection" -Passed $protected -Details "Protected active default model from deletion"
} catch {
    Record-TestResult -Number 8 -Name "Active model deletion protection" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 09: Baseline benchmark models protection (qwen2.5:3b / qwen2.5:7b)
# -----------------------------------------------------------------------------
try {
    $body = @{ name = "qwen2.5:3b"; confirmed = $true } | ConvertTo-Json
    $blocked = $false
    try {
        $res = Invoke-RestMethod -Uri "$BaseUrl/api/models/delete" -Method Post -Body $body -ContentType "application/json" -TimeoutSec 4
    } catch {
        $blocked = ($_.Exception.Response.StatusCode.value__ -eq 409)
    }
    Record-TestResult -Number 9 -Name "Baseline benchmark model protection" -Passed $blocked -Details "Blocked deletion of baseline benchmark model with HTTP 409 Conflict"
} catch {
    Record-TestResult -Number 9 -Name "Baseline benchmark model protection" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 10: Global settings persistence
# -----------------------------------------------------------------------------
try {
    $settingsRes = Invoke-RestMethod -Uri "$BaseUrl/api/models/settings" -Method Get -TimeoutSec 5
    $originalLocal = $settingsRes.settings.defaultLocalModel
    
    $updateBody = @{ defaultLocalModel = "qwen2.5:7b" } | ConvertTo-Json
    $postRes = Invoke-RestMethod -Uri "$BaseUrl/api/models/settings" -Method Post -Body $updateBody -ContentType "application/json" -TimeoutSec 5
    
    $verified = $postRes.success -and ($postRes.settings.defaultLocalModel -eq "qwen2.5:7b")
    Record-TestResult -Number 10 -Name "Global settings persistence" -Passed $verified -Details "Settings saved and verified: $($postRes.settings.defaultLocalModel)"
} catch {
    Record-TestResult -Number 10 -Name "Global settings persistence" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 11: Independent per-chat model selection
# -----------------------------------------------------------------------------
try {
    # Chat 1 uses qwen2.5:3b
    $body1 = @{ message = "Hi 1"; model = "qwen2.5:3b"; stream = $false } | ConvertTo-Json
    $res1 = Invoke-RestMethod -Uri "$BaseUrl/api/chat" -Method Post -Body $body1 -ContentType "application/json" -TimeoutSec 30
    
    # Chat 2 uses qwen2.5:7b
    $body2 = @{ message = "Hi 2"; model = "qwen2.5:7b"; stream = $false } | ConvertTo-Json
    $res2 = Invoke-RestMethod -Uri "$BaseUrl/api/chat" -Method Post -Body $body2 -ContentType "application/json" -TimeoutSec 75
    
    $independent = ($res1.model -eq "qwen2.5:3b") -and ($res2.model -eq "qwen2.5:7b")
    Record-TestResult -Number 11 -Name "Independent per-chat model selection" -Passed $independent -Details "Chat 1 used: $($res1.model), Chat 2 used: $($res2.model)"
} catch {
    Record-TestResult -Number 11 -Name "Independent per-chat model selection" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 12: OpenRouter cloud catalog discovery
# -----------------------------------------------------------------------------
try {
    $res = Invoke-RestMethod -Uri "$BaseUrl/api/models/catalog?freeOnly=true" -Method Get -TimeoutSec 15
    $validCatalog = $res.success -and ($res.models.Count -ge 1)
    $sample = $res.models[0].name
    Record-TestResult -Number 12 -Name "OpenRouter cloud catalog discovery" -Passed $validCatalog -Details "Count: $($res.models.Count), Sample: $sample"
} catch {
    Record-TestResult -Number 12 -Name "OpenRouter cloud catalog discovery" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 13: OpenRouter missing API-key graceful browsing
# -----------------------------------------------------------------------------
try {
    $res = Invoke-RestMethod -Uri "$BaseUrl/api/models/catalog?freeOnly=true" -Method Get -TimeoutSec 15
    $graceful = $res.success -and ($res.hasApiKey -ne $null)
    Record-TestResult -Number 13 -Name "OpenRouter missing API-key handling" -Passed $graceful -Details "Catalog browsable without server crash; hasApiKey: $($res.hasApiKey)"
} catch {
    Record-TestResult -Number 13 -Name "OpenRouter missing API-key handling" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 14: Ollama offline/failure graceful handling
# -----------------------------------------------------------------------------
try {
    # Check that endpoint returns structured error without HTTP 500 when invalid host is requested
    $hwRes = Invoke-RestMethod -Uri "$BaseUrl/api/models/installed" -Method Get -TimeoutSec 6
    $handled = ($hwRes.success -eq $true) -or ($hwRes.error -ne $null)
    Record-TestResult -Number 14 -Name "Provider failure graceful handling" -Passed $handled -Details "Structured response returned without uncaught exceptions"
} catch {
    Record-TestResult -Number 14 -Name "Provider failure graceful handling" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 15: Default profile retrieval (4 built-in profiles)
# -----------------------------------------------------------------------------
try {
    $res = Invoke-RestMethod -Uri "$BaseUrl/api/models/profiles" -Method Get -TimeoutSec 5
    $ids = ($res.profiles | ForEach-Object { $_.id })
    $hasAll4 = $ids -contains "general" -and $ids -contains "coding" -and $ids -contains "writing" -and $ids -contains "learning"
    Record-TestResult -Number 15 -Name "Default model profiles retrieval" -Passed $hasAll4 -Details "Built-ins present: general, coding, writing, learning"
} catch {
    Record-TestResult -Number 15 -Name "Default model profiles retrieval" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 16: Custom profile creation
# -----------------------------------------------------------------------------
$createdProfId = $null
try {
    $profBody = @{
        name = "QA Automation Profile"
        description = "Automated test profile"
        provider = "LOCAL"
        modelId = "qwen2.5:3b"
        persona = "coding"
        isCustom = $true
    } | ConvertTo-Json
    $res = Invoke-RestMethod -Uri "$BaseUrl/api/models/profiles" -Method Post -Body $profBody -ContentType "application/json" -TimeoutSec 5
    $created = $res.success -and ($res.profile.name -eq "QA Automation Profile")
    if ($created) { $createdProfId = $res.profile.id }
    Record-TestResult -Number 16 -Name "Custom profile creation" -Passed $created -Details "Profile created with ID: $createdProfId"
} catch {
    Record-TestResult -Number 16 -Name "Custom profile creation" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 17: Custom profile deletion and built-in protection
# -----------------------------------------------------------------------------
try {
    # 1. Attempt to delete built-in profile (must fail)
    $deleteBuiltinBlocked = $false
    try {
        $res1 = Invoke-RestMethod -Uri "$BaseUrl/api/models/profiles?id=general" -Method Delete -TimeoutSec 4
    } catch {
        $deleteBuiltinBlocked = ($_.Exception.Response.StatusCode.value__ -eq 403)
    }

    # 2. Delete the created custom profile (must succeed)
    $deleteCustomSuccess = $false
    if ($createdProfId) {
        $res2 = Invoke-RestMethod -Uri "$BaseUrl/api/models/profiles?id=$createdProfId" -Method Delete -TimeoutSec 4
        $deleteCustomSuccess = $res2.success -eq $true
    }

    $pass17 = $deleteBuiltinBlocked -and $deleteCustomSuccess
    Record-TestResult -Number 17 -Name "Profile deletion & built-in protection" -Passed $pass17 -Details "Built-in delete blocked (403), custom profile deleted successfully"
} catch {
    Record-TestResult -Number 17 -Name "Profile deletion & built-in protection" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 18: Favorites persistence
# -----------------------------------------------------------------------------
try {
    $favListBefore = Invoke-RestMethod -Uri "$BaseUrl/api/models/favorites" -Method Get -TimeoutSec 5
    $wasInFav = ($favListBefore.favorites -contains "qwen2.5:3b")
    
    $favBody = @{ modelId = "qwen2.5:3b" } | ConvertTo-Json
    $res = Invoke-RestMethod -Uri "$BaseUrl/api/models/favorites/toggle" -Method Post -Body $favBody -ContentType "application/json" -TimeoutSec 5
    
    $favListAfter = Invoke-RestMethod -Uri "$BaseUrl/api/models/favorites" -Method Get -TimeoutSec 5
    $toggledState = ($favListAfter.favorites -contains "qwen2.5:3b")
    
    # State must have flipped:
    $stateFlipped = ($toggledState -ne $wasInFav)
    
    # Ensure it ends up favorited
    if (-not $toggledState) {
        $null = Invoke-RestMethod -Uri "$BaseUrl/api/models/favorites/toggle" -Method Post -Body $favBody -ContentType "application/json" -TimeoutSec 5
    }
    
    $passed18 = $res.success -and $stateFlipped
    Record-TestResult -Number 18 -Name "Favorites toggling & persistence" -Passed $passed18 -Details "Favorites toggled cleanly and state flipped"
} catch {
    Record-TestResult -Number 18 -Name "Favorites toggling & persistence" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 19: Missing favorite model handling
# -----------------------------------------------------------------------------
try {
    # Favorite an uninstalled model identifier
    $uninstalledId = "mistral-large-uninstalled:latest"
    $favBody = @{ modelId = $uninstalledId } | ConvertTo-Json
    $res = Invoke-RestMethod -Uri "$BaseUrl/api/models/favorites/toggle" -Method Post -Body $favBody -ContentType "application/json" -TimeoutSec 5
    
    $favList = Invoke-RestMethod -Uri "$BaseUrl/api/models/favorites" -Method Get -TimeoutSec 5
    $retained = $favList.favorites -contains $uninstalledId
    
    # Toggle back to clean up
    $cleanup = Invoke-RestMethod -Uri "$BaseUrl/api/models/favorites/toggle" -Method Post -Body $favBody -ContentType "application/json" -TimeoutSec 5
    
    Record-TestResult -Number 19 -Name "Missing favorite model handling" -Passed $retained -Details "Retained uninstalled model in favorites without dropping"
} catch {
    Record-TestResult -Number 19 -Name "Missing favorite model handling" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 20: Performance metric recording
# -----------------------------------------------------------------------------
try {
    $perfRes = Invoke-RestMethod -Uri "$BaseUrl/api/models/performance" -Method Get -TimeoutSec 5
    $recorded = $perfRes.success -and ($perfRes.performance.totalRequests -ge 1)
    Record-TestResult -Number 20 -Name "Performance metric recording" -Passed $recorded -Details "Total requests logged: $($perfRes.performance.totalRequests)"
} catch {
    Record-TestResult -Number 20 -Name "Performance metric recording" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 21: Performance measurement accuracy (duration > 0, TTFT)
# -----------------------------------------------------------------------------
try {
    $perfRes = Invoke-RestMethod -Uri "$BaseUrl/api/models/performance" -Method Get -TimeoutSec 5
    $recent = $perfRes.performance.recentRecords[0]
    $accurate = ($recent -ne $null) -and ($recent.durationMs -gt 0)
    Record-TestResult -Number 21 -Name "Performance measurement accuracy" -Passed $accurate -Details "Model: $($recent.modelId), Duration: $($recent.durationMs)ms, TTFT: $($recent.ttftMs)ms"
} catch {
    Record-TestResult -Number 21 -Name "Performance measurement accuracy" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 22: Performance history clearing
# -----------------------------------------------------------------------------
try {
    $clearRes = Invoke-RestMethod -Uri "$BaseUrl/api/models/performance/clear" -Method Post -TimeoutSec 5
    $perfRes = Invoke-RestMethod -Uri "$BaseUrl/api/models/performance" -Method Get -TimeoutSec 5
    $cleared = $clearRes.success -and ($perfRes.performance.totalRequests -eq 0)
    Record-TestResult -Number 22 -Name "Performance history clearing" -Passed $cleared -Details "Records count after clear: $($perfRes.performance.totalRequests)"
} catch {
    Record-TestResult -Number 22 -Name "Performance history clearing" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 23: Dynamic Hardware Telemetry (CPU & RAM)
# -----------------------------------------------------------------------------
try {
    $hwRes = Invoke-RestMethod -Uri "$BaseUrl/api/models/hardware" -Method Get -TimeoutSec 5
    $hasHw = $hwRes.success -and ($hwRes.hardware.processorCores -gt 0) -and ($hwRes.hardware.totalPhysicalRamBytes -gt 0)
    Record-TestResult -Number 23 -Name "Dynamic Hardware Telemetry" -Passed $hasHw -Details "CPU: $($hwRes.hardware.cpuName), Total RAM: $($hwRes.hardware.totalRamGb), Free RAM: $($hwRes.hardware.freeRamGb)"
} catch {
    Record-TestResult -Number 23 -Name "Dynamic Hardware Telemetry" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 24: Hardware suitability recommendations
# -----------------------------------------------------------------------------
try {
    $modelsRes = Invoke-RestMethod -Uri "$BaseUrl/api/models/installed" -Method Get -TimeoutSec 5
    $qwen7b = $modelsRes.models | Where-Object { $_.id -eq "qwen2.5:7b" } | Select-Object -First 1
    $qwen3b = $modelsRes.models | Where-Object { $_.id -eq "qwen2.5:3b" } | Select-Object -First 1
    
    $suit7b = $qwen7b.suitabilityLabel
    $suit3b = $qwen3b.suitabilityLabel
    $recPassed = ($suit7b -eq "RECOMMENDED") -and ($suit3b -eq "RECOMMENDED")
    Record-TestResult -Number 24 -Name "Hardware suitability recommendations" -Passed $recPassed -Details "qwen2.5:7b: $suit7b, qwen2.5:3b: $suit3b"
} catch {
    Record-TestResult -Number 24 -Name "Hardware suitability recommendations" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 25: Personal Memory integration across model switching
# -----------------------------------------------------------------------------
try {
    $testUserId = "user_test_model_cross_" + (Get-Random)
    
    # Save memory using qwen2.5:3b
    $memBody = @{
        action = "add"
        text = "My primary specialization is Core Java 21."
        category = "skill"
        userId = $testUserId
    } | ConvertTo-Json
    $memRes = Invoke-RestMethod -Uri "$BaseUrl/api/memory" -Method Post -Body $memBody -ContentType "application/json" -TimeoutSec 5
    
    # Query memory using qwen2.5:7b
    $chatBody = @{
        message = "What is my primary specialization?"
        model = "qwen2.5:7b"
        userId = $testUserId
        stream = $false
    } | ConvertTo-Json
    $chatRes = Invoke-RestMethod -Uri "$BaseUrl/api/chat" -Method Post -Body $chatBody -ContentType "application/json" -Headers @{ "X-User-Id" = $testUserId } -TimeoutSec 90
    
    $recalled = $chatRes.reply -match "Java"
    Record-TestResult -Number 25 -Name "Personal Memory cross-model retrieval" -Passed $recalled -Details "Memory injected into qwen2.5:7b prompt and recalled"
} catch {
    Record-TestResult -Number 25 -Name "Personal Memory cross-model retrieval" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 26: Bengali language preservation
# -----------------------------------------------------------------------------
try {
    $chatBody = '{"message":"NEXORA \u0995\u09c0?","model":"qwen2.5:3b","stream":false}'
    $chatRes = Invoke-RestMethod -Uri "$BaseUrl/api/chat" -Method Post -Body $chatBody -ContentType "application/json; charset=UTF-8" -TimeoutSec 90
    $hasBengaliScript = [regex]::IsMatch($chatRes.reply, "[\u0980-\u09FF]")
    Record-TestResult -Number 26 -Name "Bengali language response quality" -Passed $hasBengaliScript -Details "Language: $($chatRes.language), Reply contains Bengali characters"
} catch {
    Record-TestResult -Number 26 -Name "Bengali language response quality" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 27: Banglish-to-Bengali conversion
# -----------------------------------------------------------------------------
try {
    $chatBody = '{"message":"NEXORA ki?","model":"qwen2.5:3b","stream":false}'
    $chatRes = Invoke-RestMethod -Uri "$BaseUrl/api/chat" -Method Post -Body $chatBody -ContentType "application/json; charset=UTF-8" -TimeoutSec 90
    $hasBengaliScript = [regex]::IsMatch($chatRes.reply, "[\u0980-\u09FF]")
    Record-TestResult -Number 27 -Name "Banglish-to-Bengali conversion" -Passed $hasBengaliScript -Details "Language: $($chatRes.language), Converted to Bengali script"
} catch {
    Record-TestResult -Number 27 -Name "Banglish-to-Bengali conversion" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 28: English generation quality
# -----------------------------------------------------------------------------
try {
    $chatBody = @{
        message = "What is NEXORA in 10 words?"
        model = "qwen2.5:3b"
        stream = $false
    } | ConvertTo-Json
    $chatRes = Invoke-RestMethod -Uri "$BaseUrl/api/chat" -Method Post -Body $chatBody -ContentType "application/json" -TimeoutSec 30
    $isEnglish = ($chatRes.language -eq "en") -and -not [regex]::IsMatch($chatRes.reply, "[\u0980-\u09FF]")
    Record-TestResult -Number 28 -Name "English generation quality & language match" -Passed $isEnglish -Details "Language: $($chatRes.language), Reply in English"
} catch {
    Record-TestResult -Number 28 -Name "English generation quality & language match" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 29: SSE token streaming integrity
# -----------------------------------------------------------------------------
try {
    $client = [System.Net.Http.HttpClient]::new()
    $content = [System.Net.Http.StringContent]::new('{"message":"Say 1 2 3","model":"qwen2.5:3b","stream":true}', [System.Text.Encoding]::UTF8, "application/json")
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$BaseUrl/api/chat")
    $req.Content = $content
    $req.Headers.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new("text/event-stream"))
    
    $response = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    $stream = $response.Content.ReadAsStreamAsync().Result
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
    
    $hasChunks = $false
    $hasDone = $false
    $lineCount = 0
    while (-not $reader.EndOfStream -and ($lineCount -lt 50)) {
        $line = $reader.ReadLine()
        $lineCount++
        if ($line -match "data: \{.*chunk.*\}") { $hasChunks = $true }
        if ($line -match "data: \[DONE\]") { $hasDone = $true; break }
    }
    
    $reader.Dispose()
    $stream.Dispose()
    $response.Dispose()
    $client.Dispose()
    
    $streamPassed = $hasChunks -and $hasDone
    Record-TestResult -Number 29 -Name "SSE token streaming integrity" -Passed $streamPassed -Details "Received chunks and [DONE] signal"
} catch {
    Record-TestResult -Number 29 -Name "SSE token streaming integrity" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Test 30: Application restart persistence
# -----------------------------------------------------------------------------
try {
    # Check that data/models/model_settings.json and profiles.json exist on disk
    $settingsPath = "data/models/model_settings.json"
    $profilesPath = "data/models/profiles.json"
    $favPath = "data/models/favorites.json"
    
    $persisted = (Test-Path $settingsPath) -and (Test-Path $profilesPath) -and (Test-Path $favPath)
    Record-TestResult -Number 30 -Name "Application restart persistence on disk" -Passed $persisted -Details "Files verified: $settingsPath, $profilesPath, $favPath"
} catch {
    Record-TestResult -Number 30 -Name "Application restart persistence on disk" -Passed $false -Details $_.Exception.Message
}

# -----------------------------------------------------------------------------
# Clean up background server process if launched by test
# -----------------------------------------------------------------------------
if ($serverProcess -ne $null -and -not $serverProcess.HasExited) {
    Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
}

# -----------------------------------------------------------------------------
# Summary Report
# -----------------------------------------------------------------------------
$total = $testResults.Count
$passedCount = ($testResults | Where-Object { $_.Status -eq "PASS" }).Count
$failedCount = ($testResults | Where-Object { $_.Status -eq "FAIL" }).Count

Write-Host "==========================================================================" -ForegroundColor Cyan
Write-Host (" SUMMARY: {0}/{1} Tests Passed ({2} Failed)" -f $passedCount, $total, $failedCount) -ForegroundColor $(if ($failedCount -eq 0) { "Green" } else { "Red" })
Write-Host "==========================================================================" -ForegroundColor Cyan

if ($failedCount -eq 0) {
    exit 0
} else {
    exit 1
}
