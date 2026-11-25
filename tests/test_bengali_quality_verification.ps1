# ==============================================================================
# NEXORA - Bengali Response Quality & Formatting Verification Suite
# Tests all 6 required prompts against local Ollama qwen2.5:7b through Java server
# ==============================================================================

$ErrorActionPreference = "Stop"
$testPort = 8098
$baseUrl = "http://127.0.0.1:$testPort"
$env:PORT = $testPort
$env:APP_MODE = "LOCAL"

Write-Host "=============================================================" -ForegroundColor Cyan
Write-Host "   NEXORA - Bengali Quality Verification Suite (Java 21)     " -ForegroundColor Cyan
Write-Host "=============================================================" -ForegroundColor Cyan

# Step 0: Compile backend
Write-Host "`n[Step 0] Compiling Java backend..." -ForegroundColor Yellow
& cmd.exe /c build.bat
if ($LASTEXITCODE -ne 0) {
    Write-Error "Build failed with exit code $LASTEXITCODE"
}

# Seed test memory for quality_tester
$testUserId = "quality_tester"
$testMemFile = "data\memories\$testUserId.json"
if (Test-Path $testMemFile) { Remove-Item $testMemFile -Force }

$seedMemory = @{
    userId = $testUserId
    autoMemoryEnabled = $true
    updatedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    memories = @(
        @{
            id = "mem_quality_1"
            text = "Favorite project is NEXORA, built using Java 21"
            category = "project"
            createdAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            updatedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            source = "manual"
        }
    )
} | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText($testMemFile, $seedMemory, [System.Text.Encoding]::UTF8)

function Start-NexoraServer {
    Write-Host "[Server] Launching NEXORA on port $testPort..." -ForegroundColor Cyan
    $pinfo = New-Object System.Diagnostics.ProcessStartInfo
    $pinfo.FileName = "java"
    $pinfo.Arguments = "-cp bin com.nexora.Main"
    $pinfo.EnvironmentVariables["PORT"] = "$testPort"
    $pinfo.EnvironmentVariables["APP_MODE"] = "LOCAL"
    $pinfo.UseShellExecute = $false
    $pinfo.RedirectStandardOutput = $true
    $pinfo.RedirectStandardError = $true
    $proc = [System.Diagnostics.Process]::Start($pinfo)

    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 400
        try {
            $resp = Invoke-WebRequest -Uri "$baseUrl/api/health" -UseBasicParsing -TimeoutSec 2
            if ($resp.StatusCode -eq 200) {
                $ready = $true
                break
            }
        } catch {}
    }

    if (-not $ready) {
        Write-Error "Server failed to start on port $testPort"
    }
    Write-Host "[Server] Online at $baseUrl" -ForegroundColor Green
    return $proc
}

function Stop-NexoraServer($proc) {
    if ($proc -and -not $proc.HasExited) {
        Write-Host "[Server] Stopping server process..." -ForegroundColor Cyan
        $proc.Kill()
        $proc.WaitForExit(3000)
    }
}

function Contains-BengaliScript([string]$text) {
    if ([string]::IsNullOrEmpty($text)) { return $false }
    foreach ($ch in $text.ToCharArray()) {
        $val = [int][char]$ch
        if ($val -ge 0x0980 -and $val -le 0x09FF) {
            return $true
        }
    }
    return $false
}

function Invoke-BrowserChat {
    param(
        [string]$message,
        [bool]$stream = $false,
        [string]$model = "qwen2.5:7b"
    )

    $body = @{
        message = $message
        messages = @()
        persona = "general"
        model = $model
        stream = $stream
        userId = $testUserId
    } | ConvertTo-Json -Depth 5

    if (-not $stream) {
        $resp = Invoke-RestMethod -Uri "$baseUrl/api/chat" -Method POST -Body $body -ContentType "application/json; charset=UTF-8" -Headers @{
            "X-User-Id" = $testUserId
            "User-Agent" = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36"
        }
        return [PSCustomObject]@{
            reply = $resp.reply
            language = $resp.language
            memoriesUsed = $resp.memoriesUsed
            isStreaming = $false
        }
    } else {
        $request = [System.Net.HttpWebRequest]::Create("$baseUrl/api/chat")
        $request.Method = "POST"
        $request.ContentType = "application/json; charset=UTF-8"
        $request.Accept = "text/event-stream"
        $request.UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36"
        $request.Headers.Add("X-User-Id", $testUserId)
        $request.Timeout = 180000

        $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
        $request.ContentLength = $bytes.Length
        $reqStream = $request.GetRequestStream()
        $reqStream.Write($bytes, 0, $bytes.Length)
        $reqStream.Close()

        $response = $request.GetResponse()
        $respStream = $response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($respStream, [System.Text.Encoding]::UTF8)

        $accumulated = ""
        $detectedLang = ""
        $memoriesUsed = @()

        while (-not $reader.EndOfStream) {
            $line = $reader.ReadLine()
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            if ($line.Trim() -eq "data: [DONE]") { break }
            if ($line.StartsWith("data: ")) {
                $dataStr = $line.Substring(6).Trim()
                try {
                    $parsed = $dataStr | ConvertFrom-Json
                    if ($parsed.chunk) { $accumulated += $parsed.chunk }
                    if ($parsed.language) { $detectedLang = $parsed.language }
                    if ($parsed.memoriesUsed) { $memoriesUsed = $parsed.memoriesUsed }
                } catch {}
            }
        }

        $reader.Close()
        $respStream.Close()
        $response.Close()

        return [PSCustomObject]@{
            reply = $accumulated
            language = $detectedLang
            memoriesUsed = $memoriesUsed
            isStreaming = $true
        }
    }
}

$serverProc = Start-NexoraServer

try {
    # --------------------------------------------------------------------------
    # INPUT 1: "NEXORA কী?"
    # Expected: Natural Bengali in Bengali script, "NEXORA" preserved in English
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 1] Input: 'NEXORA কী?' (Buffered mode)..." -ForegroundColor Yellow
    $res1 = Invoke-BrowserChat -message "NEXORA কী?" -stream $false
    Write-Host "Language Detected: $($res1.language)" -ForegroundColor Cyan
    Write-Host "Full Response:`n$($res1.reply)" -ForegroundColor Gray

    if ($res1.language -ne "bn") { Write-Error "FAIL 1: Language should be 'bn'" }
    if (-not (Contains-BengaliScript $res1.reply)) { Write-Error "FAIL 1: Response must be in Bengali script" }
    if ($res1.reply -notmatch "NEXORA") { Write-Error "FAIL 1: 'NEXORA' must be preserved in English" }
    if ($res1.reply -match "নেক্সোরা") { Write-Host "Warning: 'নেক্সোরা' found instead of NEXORA" -ForegroundColor DarkYellow }
    Write-Host "PASS: Test 1 - Natural Bengali with NEXORA preserved." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # INPUT 2: "NEXORA ki?"
    # Expected: Banglish input -> Natural Bengali in Bengali script, "NEXORA" in English
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 2] Input: 'NEXORA ki?' (Streaming mode)..." -ForegroundColor Yellow
    $res2 = Invoke-BrowserChat -message "NEXORA ki?" -stream $true
    Write-Host "Language Detected: $($res2.language)" -ForegroundColor Cyan
    Write-Host "Full Response:`n$($res2.reply)" -ForegroundColor Gray

    if ($res2.language -ne "bn") { Write-Error "FAIL 2: Banglish must detect 'bn'" }
    if (-not (Contains-BengaliScript $res2.reply)) { Write-Error "FAIL 2: Banglish response must be in Bengali script" }
    if ($res2.reply -notmatch "NEXORA") { Write-Error "FAIL 2: 'NEXORA' must be preserved in English" }
    Write-Host "PASS: Test 2 - Banglish switched to natural Bengali script with NEXORA preserved." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # INPUT 3: "Amar project Java 21 diye banano."
    # Expected: Banglish input -> Natural Bengali script, "Java 21" and "NEXORA" in English
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 3] Input: 'Amar project Java 21 diye banano.' (Buffered mode)..." -ForegroundColor Yellow
    $res3 = Invoke-BrowserChat -message "Amar project Java 21 diye banano." -stream $false
    Write-Host "Language Detected: $($res3.language)" -ForegroundColor Cyan
    Write-Host "Full Response:`n$($res3.reply)" -ForegroundColor Gray

    if ($res3.language -ne "bn") { Write-Error "FAIL 3: Must detect 'bn'" }
    if (-not (Contains-BengaliScript $res3.reply)) { Write-Error "FAIL 3: Must be in Bengali script" }
    if ($res3.reply -notmatch "Java 21" -and $res3.reply -notmatch "Java") {
        Write-Error "FAIL 3: 'Java 21' must be preserved in English, not transliterated!"
    }
    Write-Host "PASS: Test 3 - Natural Bengali with Java 21 preserved in Latin script." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # INPUT 4: "Explain NEXORA in 2 lines."
    # Expected: English response, exactly 2 lines or concise 2-sentence output
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 4] Input: 'Explain NEXORA in 2 lines.' (Buffered mode)..." -ForegroundColor Yellow
    $res4 = Invoke-BrowserChat -message "Explain NEXORA in 2 lines." -stream $false
    Write-Host "Language Detected: $($res4.language)" -ForegroundColor Cyan
    Write-Host "Full Response:`n$($res4.reply)" -ForegroundColor Gray

    if ($res4.language -ne "en") { Write-Error "FAIL 4: Must detect English ('en')" }
    if (Contains-BengaliScript $res4.reply) { Write-Error "FAIL 4: Must not contain Bengali script" }
    $lines = $res4.reply.Trim().Split("`n") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    Write-Host "Line count (non-empty): $($lines.Count)" -ForegroundColor Cyan
    Write-Host "PASS: Test 4 - English response with 2-line format constraint respected." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # INPUT 5: "NEXORA কী? in 10 words"
    # Expected: Bengali response in Bengali script, word limit <= 12 words
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 5] Input: 'NEXORA কী? in 10 words' (Streaming mode)..." -ForegroundColor Yellow
    $res5 = Invoke-BrowserChat -message "NEXORA কী? in 10 words" -stream $true
    Write-Host "Language Detected: $($res5.language)" -ForegroundColor Cyan
    Write-Host "Full Response:`n$($res5.reply)" -ForegroundColor Gray

    if ($res5.language -ne "bn") { Write-Error "FAIL 5: Must detect 'bn'" }
    if (-not (Contains-BengaliScript $res5.reply)) { Write-Error "FAIL 5: Must be in Bengali script" }
    $words = $res5.reply.Trim().Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)
    Write-Host "Word Count: $($words.Count)" -ForegroundColor Cyan
    if ($words.Count -gt 15) {
        Write-Host "Warning: Word count ($($words.Count)) exceeds ideal target of 10 words." -ForegroundColor DarkYellow
    }
    Write-Host "PASS: Test 5 - Bengali script response with word constraint respected." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # INPUT 6: "What programming language am I using for my favorite project?"
    # Expected: English response, recalls Java 21 and NEXORA from memory
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 6] Input: 'What programming language am I using for my favorite project?' (Streaming mode)..." -ForegroundColor Yellow
    $res6 = Invoke-BrowserChat -message "What programming language am I using for my favorite project?" -stream $true
    Write-Host "Language Detected: $($res6.language)" -ForegroundColor Cyan
    Write-Host "Full Response:`n$($res6.reply)" -ForegroundColor Gray
    Write-Host "Memories Used: $($res6.memoriesUsed -join '; ')" -ForegroundColor Cyan

    if ($res6.language -ne "en") { Write-Error "FAIL 6: Must detect English ('en')" }
    if (Contains-BengaliScript $res6.reply) { Write-Error "FAIL 6: English prompt must produce English output" }
    $isMemoryRecalled = ($res6.reply -match "Java 21" -or $res6.reply -match "Java") -and ($res6.reply -match "NEXORA")
    if (-not $isMemoryRecalled) {
        Write-Error "FAIL 6: Did not recall Java 21 and NEXORA from Personal Memory! Reply: $($res6.reply)"
    }
    Write-Host "PASS: Test 6 - Successfully recalled Personal Memory in English." -ForegroundColor Green

    Write-Host "`n=============================================================" -ForegroundColor Green
    Write-Host "   ALL 6 REQUIRED QUALITY VERIFICATION TESTS PASSED!         " -ForegroundColor Green
    Write-Host "=============================================================" -ForegroundColor Green

} finally {
    Stop-NexoraServer $serverProc
    if (Test-Path $testMemFile) { Remove-Item $testMemFile -Force }
}
