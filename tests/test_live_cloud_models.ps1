# ==============================================================================
# Live Cloud Model Testing Script for OpenRouter Free Tier
# Tests every candidate model with real API generation calls
# ==============================================================================

$ProgressPreference = 'SilentlyContinue'

$envFile = Get-Content ".env" -ErrorAction SilentlyContinue
$apiKey = ""
foreach ($line in $envFile) {
    if ($line -match "^OPENROUTER_API_KEY=(.+)$") {
        $apiKey = $matches[1].Trim()
    }
}

if (-not $apiKey) {
    Write-Host "[ERROR] OPENROUTER_API_KEY not found in .env" -ForegroundColor Red
    exit 1
}

Write-Host "Found API key: ...$($apiKey.Substring($apiKey.Length - 6))" -ForegroundColor Cyan

$modelsToTest = @(
    "openrouter/free",
    "liquid/lfm-3-1.7b:free",
    "nvidia/nemotron-3.5-lightning:free",
    "recursive/flint-v1.0:free",
    "nvidia/nemotron-3.5-super-vl:free",
    "meta-llama/llama-3.2-3b-instruct:free",
    "google/gemini-2.0-flash-lite:free",
    "deepseek/deepseek-r1:free",
    "mistralai/mistral-7b-instruct:free"
)

$headers = @{
    "Authorization" = "Bearer $apiKey"
    "HTTP-Referer" = "http://127.0.0.1:8080"
    "X-Title" = "NEXORA Local"
    "Content-Type" = "application/json"
}

$results = @()

foreach ($model in $modelsToTest) {
    Write-Host "`nTesting model: $model ..." -ForegroundColor Yellow
    $body = @{
        model = $model
        messages = @(
            @{ role = "user"; content = "Say 'TEST_OK' in one word." }
        )
        max_tokens = 20
        temperature = 0.1
    } | ConvertTo-Json -Compress

    $status = 0
    $reply = ""
    $err = ""
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    try {
        $response = Invoke-RestMethod -Uri "https://openrouter.ai/api/v1/chat/completions" -Method Post -Headers $headers -Body $body -TimeoutSec 20
        $sw.Stop()
        $status = 200
        $reply = $response.choices[0].message.content.Trim()
        Write-Host " -> SUCCESS (Status 200, Latency: $($sw.ElapsedMilliseconds)ms): $reply" -ForegroundColor Green
    } catch {
        $sw.Stop()
        if ($_.Exception.Response) {
            $status = $_.Exception.Response.StatusCode.value__
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $err = $reader.ReadToEnd()
            $reader.Close()
        } else {
            $status = 500
            $err = $_.Exception.Message
        }
        Write-Host " -> FAILED (Status $status): $err" -ForegroundColor Red
    }

    $results += [PSCustomObject]@{
        Model = $model
        Status = $status
        DurationMs = $sw.ElapsedMilliseconds
        Output = $reply
        Error = $err
    }
}

Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host " LIVE OPENROUTER MODEL TEST RESULTS SUMMARY" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
$results | Format-Table -Property Model, Status, DurationMs, Output
