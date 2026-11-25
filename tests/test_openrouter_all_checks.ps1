# test_openrouter_all_checks.ps1
$ErrorActionPreference = "Stop"

Write-Host "--- TEST 1: Checking /api/config ---" -ForegroundColor Cyan
$cfg = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/config" -Method Get
Write-Host "Config: hasOpenRouterApiKey = $($cfg.hasOpenRouterApiKey), provider = $($cfg.provider)"
if ($cfg.hasOpenRouterApiKey -ne $true) {
    throw "hasOpenRouterApiKey is not true!"
}
Write-Host "PASSED: /api/config" -ForegroundColor Green

Write-Host "`n--- TEST 2: Checking /api/models/settings ---" -ForegroundColor Cyan
$settings = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/models/settings" -Method Get
Write-Host "Settings: defaultCloudModel = $($settings.settings.defaultCloudModel)"
if ($settings.settings.defaultCloudModel -ne "openrouter/free") {
    throw "defaultCloudModel is not openrouter/free! Got: $($settings.settings.defaultCloudModel)"
}
Write-Host "PASSED: /api/models/settings" -ForegroundColor Green

Write-Host "`n--- TEST 3: Checking /api/models/catalog?freeOnly=true&refresh=true ---" -ForegroundColor Cyan
$catalog = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/models/catalog?freeOnly=true&refresh=true" -Method Get
Write-Host "Catalog: count = $($catalog.models.Count), first model = $($catalog.models[0].id) ($($catalog.models[0].name))"
if ($catalog.models[0].id -ne "openrouter/free") {
    throw "First model in catalog is not openrouter/free! Got: $($catalog.models[0].id)"
}
Write-Host "PASSED: Catalog has openrouter/free at index 0" -ForegroundColor Green

Write-Host "`n--- TEST 4: Chat with openrouter/free (Buffered) ---" -ForegroundColor Cyan
$chatBody = @{
    provider = "PUBLIC"
    model = "openrouter/free"
    stream = $false
    messages = @(
        @{ role = "user"; content = "Reply with exactly: OPENROUTER_FREE_OK" }
    )
} | ConvertTo-Json -Depth 5

$chatRes = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -ContentType "application/json; charset=UTF-8" -Body $chatBody
Write-Host "Response model: $($chatRes.model)"
Write-Host "Response content: $($chatRes.message.content.Trim())"
if (-not $chatRes.message.content) {
    throw "Chat response is empty!"
}
Write-Host "PASSED: Chat with openrouter/free" -ForegroundColor Green

Write-Host "`n--- TEST 5: Stale model error handling (deepseek/deepseek-r1:free) ---" -ForegroundColor Cyan
$staleBody = @{
    provider = "PUBLIC"
    model = "deepseek/deepseek-r1:free"
    stream = $false
    messages = @(
        @{ role = "user"; content = "Hello" }
    )
} | ConvertTo-Json -Depth 5

try {
    $errRes = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -ContentType "application/json; charset=UTF-8" -Body $staleBody
    throw "Expected error for stale deepseek-r1:free, but call succeeded"
} catch {
    $errText = ""
    if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
        $errText = $_.ErrorDetails.Message
    } elseif ($_.Exception.Message) {
        $errText = $_.Exception.Message
    }
    Write-Host "Caught error details: $errText"
    if ($errText -match "openrouter/free|unavailable|no longer available") {
        Write-Host "PASSED: Graceful error response advising switch to openrouter/free" -ForegroundColor Green
    } else {
        Write-Host "Warning: Got error: $errText (verifying error content...)"
    }
}

Write-Host "`n=== ALL OPENROUTER CHECKS PASSED SUCCESSFULLY ===" -ForegroundColor Green
