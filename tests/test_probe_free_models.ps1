# test_probe_free_models.ps1
$ErrorActionPreference = "Continue"

Write-Host "Fetching all free models from NEXORA..." -ForegroundColor Cyan
$catalog = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/models/catalog?freeOnly=true&refresh=true" -Method Get
Write-Host "Discovered $($catalog.models.Count) free models:" -ForegroundColor Yellow

$results = @()

foreach ($m in $catalog.models) {
    $modelId = $m.id
    Write-Host "`nTesting model: $modelId ($($m.name))..." -ForegroundColor White
    $body = @{
        provider = "PUBLIC"
        model = $modelId
        stream = $false
        messages = @(
            @{ role = "user"; content = "Say OK" }
        )
    } | ConvertTo-Json -Depth 5

    $status = "UNKNOWN"
    $detail = ""
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    try {
        $res = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -ContentType "application/json; charset=UTF-8" -Body $body -TimeoutSec 20
        $sw.Stop()
        if ($res.success -and $res.message.content) {
            $status = "PASS"
            $contentSnippet = $res.message.content.Trim().Replace("`n", " ").Replace("`r", "")
            if ($contentSnippet.Length -gt 60) { $contentSnippet = $contentSnippet.Substring(0, 60) + "..." }
            $detail = "Response (${sw.ElapsedMilliseconds}ms): $contentSnippet"
            Write-Host "  -> PASS (${sw.ElapsedMilliseconds}ms): $contentSnippet" -ForegroundColor Green
        } else {
            $status = "FAIL"
            $detail = "Empty content or success=false"
            Write-Host "  -> FAIL: Empty content" -ForegroundColor Red
        }
    } catch {
        $sw.Stop()
        $status = "FAIL"
        $errText = ""
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $errText = $_.ErrorDetails.Message
        } else {
            $errText = $_.Exception.Message
        }
        $detail = "Error (${sw.ElapsedMilliseconds}ms): $errText"
        Write-Host "  -> FAIL: $errText" -ForegroundColor Red
    }

    $results += [PSCustomObject]@{
        ModelId = $modelId
        Name = $m.name
        Status = $status
        Detail = $detail
        ElapsedMs = $sw.ElapsedMilliseconds
    }

    # Small pause to avoid hitting rapid OpenRouter rate limits
    Start-Sleep -Milliseconds 800
}

Write-Host "`n========================================================" -ForegroundColor Cyan
Write-Host " PROBE RESULTS SUMMARY" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
$results | Format-Table -AutoSize ModelId, Status, ElapsedMs, Detail
