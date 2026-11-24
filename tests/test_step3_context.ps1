# NEXORA Phase 3: Multi-turn Context & Streaming Test Suite
$ErrorActionPreference = "Continue"

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host " Testing Multi-Turn Context & SSE Streaming         " -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan

# 3.1 Multi-turn context conversation test
Write-Host "`n--- 3.1 Multi-Turn Conversational Memory Test ---" -ForegroundColor Yellow

$conversationMessages = [System.Collections.Generic.List[hashtable]]::new()

function Add-And-SendTurn {
    param(
        [int]$TurnNumber,
        [string]$UserPrompt
    )
    Write-Host "`n[Turn $TurnNumber] User: $UserPrompt" -ForegroundColor Cyan
    $conversationMessages.Add(@{ role = "user"; content = $UserPrompt })

    $payload = @{
        messages = $conversationMessages.ToArray()
        persona = "general"
        model = "qwen2.5:7b"
        stream = $false
    } | ConvertTo-Json -Compress -Depth 6

    $tempFile = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tempFile, $payload, (New-Object System.Text.UTF8Encoding($false)))

    $startTime = Get-Date
    $raw = & curl.exe -s --noproxy "*" --max-time 120 -X POST "http://127.0.0.1:8080/api/chat" `
        -H "Content-Type: application/json; charset=UTF-8" `
        --data-binary "@$tempFile"
    $duration = ((Get-Date) - $startTime).TotalSeconds
    Remove-Item $tempFile -Force

    $json = $raw | ConvertFrom-Json
    $reply = $json.reply
    Write-Host "[Turn $TurnNumber Reply ($([math]::Round($duration, 2))s)] AI: $reply" -ForegroundColor Green
    $conversationMessages.Add(@{ role = "assistant"; content = $reply })
    return $reply
}

$turn1 = Add-And-SendTurn -TurnNumber 1 -UserPrompt "Hello! My name is Tanvir and I am building an AI project with Java 21."
$turn2 = Add-And-SendTurn -TurnNumber 2 -UserPrompt "What is my name and what technology am I building with?"
$turn3 = Add-And-SendTurn -TurnNumber 3 -UserPrompt "Mention two major advantages of using that specific Java version for backend servers."

# 3.2 SSE Streaming Test
Write-Host "`n--- 3.2 Server-Sent Events (SSE) Streaming Test ---" -ForegroundColor Yellow
$streamPayload = @{
    message = "Count from 1 to 5, one number per line."
    persona = "general"
    model = "qwen2.5:7b"
    stream = $true
} | ConvertTo-Json -Compress

$streamTemp = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($streamTemp, $streamPayload, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Sending streaming request (expecting chunked text/event-stream)..." -ForegroundColor Gray
$rawStream = & curl.exe -s -N --noproxy "*" --max-time 60 -X POST "http://127.0.0.1:8080/api/chat" `
    -H "Content-Type: application/json; charset=UTF-8" `
    --data-binary "@$streamTemp"
Remove-Item $streamTemp -Force

Write-Host "`nReceived Stream Output Preview:" -ForegroundColor Green
$lines = $rawStream -split "`n"
$dataLines = $lines | Where-Object { $_ -like "data: *" }
Write-Host "Total SSE data events received: $($dataLines.Count)" -ForegroundColor Green
Write-Host "First 3 events:" -ForegroundColor Gray
$dataLines | Select-Object -First 3 | ForEach-Object { Write-Host "  $_" -ForegroundColor White }
Write-Host "Last event:" -ForegroundColor Gray
$dataLines | Select-Object -Last 1 | ForEach-Object { Write-Host "  $_" -ForegroundColor White }

# Check for [DONE] token
$hasDone = $rawStream.Contains("data: [DONE]")
Write-Host "Stream termination token 'data: [DONE]' received: $hasDone" -ForegroundColor $(if ($hasDone) { "Green" } else { "Red" })

Write-Host "`n====================================================" -ForegroundColor Cyan
Write-Host " Multi-turn & Streaming Testing Complete            " -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan
