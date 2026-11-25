# ==============================================================================
# NEXORA - Automatic Response Language Matching Verification Suite
# Tests all 6 required prompts, multi-turn switching, streaming, and memory recall
# ==============================================================================

$ErrorActionPreference = "Stop"
$testPort = 8096
$baseUrl = "http://127.0.0.1:$testPort"
$env:PORT = $testPort
$env:APP_MODE = "LOCAL"

Write-Host "=============================================================" -ForegroundColor Cyan
Write-Host "   NEXORA - Response Language Matching Test Suite (Java 21)   " -ForegroundColor Cyan
Write-Host "=============================================================" -ForegroundColor Cyan

# Step 0: Compile backend
Write-Host "`n[Step 0] Compiling Java backend..." -ForegroundColor Yellow
& cmd.exe /c build.bat
if ($LASTEXITCODE -ne 0) {
    Write-Error "Build failed with exit code $LASTEXITCODE"
}

# Setup test user memory file
$testUserId = "lang_tester"
$testMemFile = "data\memories\$testUserId.json"
if (Test-Path $testMemFile) { Remove-Item $testMemFile -Force }

# Pre-seed a memory to verify cross-language personal memory retrieval
$seedMemory = @{
    userId = $testUserId
    autoMemoryEnabled = $true
    updatedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    memories = @(
        @{
            id = "mem_seed_1"
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

    # Wait for server to become responsive
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

function Invoke-ChatRequest {
    param(
        [string]$message,
        [array]$messages = @(),
        [bool]$stream = $false
    )

    $body = @{
        message = $message
        messages = $messages
        persona = "general"
        model = "qwen2.5:3b"
        stream = $stream
        userId = $testUserId
    } | ConvertTo-Json -Depth 5

    if (-not $stream) {
        $resp = Invoke-RestMethod -Uri "$baseUrl/api/chat" -Method POST -Body $body -ContentType "application/json; charset=UTF-8" -Headers @{"X-User-Id" = $testUserId}
        return [PSCustomObject]@{
            reply = $resp.reply
            language = $resp.language
            memoriesUsed = $resp.memoriesUsed
        }
    } else {
        # SSE Streaming Request
        $request = [System.Net.HttpWebRequest]::Create("$baseUrl/api/chat")
        $request.Method = "POST"
        $request.ContentType = "application/json; charset=UTF-8"
        $request.Accept = "text/event-stream"
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
        }
    }
}

$serverProc = Start-NexoraServer

try {
    # --------------------------------------------------------------------------
    # TEST 1: "Explain what NEXORA is." -> Expected: English response
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 1] Input: 'Explain what NEXORA is.'" -ForegroundColor Yellow
    $res1 = Invoke-ChatRequest -message "Explain what NEXORA is." -stream $false
    Write-Host "Detected Language: $($res1.language)" -ForegroundColor Cyan
    Write-Host "Reply Snippet: $($res1.reply.Substring(0, [Math]::Min(120, $res1.reply.Length)))..." -ForegroundColor Gray

    if ($res1.language -ne "en") {
        Write-Error "FAIL: Test 1 expected language 'en' but got '$($res1.language)'"
    }
    if (Contains-BengaliScript $res1.reply) {
        Write-Error "FAIL: Test 1 expected English response but found Bengali script!"
    }
    Write-Host "PASS: Test 1 - Correctly detected English and responded in English." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # TEST 2: "NEXORA কী?" -> Expected: Bengali response in Bengali script
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 2] Input: 'NEXORA কী?'" -ForegroundColor Yellow
    $res2 = Invoke-ChatRequest -message "NEXORA কী?" -stream $false
    Write-Host "Detected Language: $($res2.language)" -ForegroundColor Cyan
    Write-Host "Reply Snippet: $($res2.reply.Substring(0, [Math]::Min(120, $res2.reply.Length)))..." -ForegroundColor Gray

    if ($res2.language -ne "bn") {
        Write-Error "FAIL: Test 2 expected language 'bn' but got '$($res2.language)'"
    }
    if (-not (Contains-BengaliScript $res2.reply)) {
        Write-Error "FAIL: Test 2 expected Bengali script in response but none was found!"
    }
    Write-Host "PASS: Test 2 - Correctly detected Bengali script and responded in Bengali script." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # TEST 3: "NEXORA ki?" -> Expected: Bengali response in Bengali script (Banglish)
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 3] Input: 'NEXORA ki?' (Banglish)" -ForegroundColor Yellow
    $res3 = Invoke-ChatRequest -message "NEXORA ki?" -stream $false
    Write-Host "Detected Language: $($res3.language)" -ForegroundColor Cyan
    Write-Host "Reply Snippet: $($res3.reply.Substring(0, [Math]::Min(120, $res3.reply.Length)))..." -ForegroundColor Gray

    if ($res3.language -ne "bn") {
        Write-Error "FAIL: Test 3 expected language 'bn' for Banglish input but got '$($res3.language)'"
    }
    if (-not (Contains-BengaliScript $res3.reply)) {
        Write-Error "FAIL: Test 3 expected Bengali script response for Banglish question!"
    }
    Write-Host "PASS: Test 3 - Correctly detected Banglish and responded in Bengali script." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # TEST 4: "Amar project Java 21 diye banano." -> Expected: Bengali response in Bengali script
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 4] Input: 'Amar project Java 21 diye banano.' (Banglish Statement)" -ForegroundColor Yellow
    $res4 = Invoke-ChatRequest -message "Amar project Java 21 diye banano." -stream $false
    Write-Host "Detected Language: $($res4.language)" -ForegroundColor Cyan
    Write-Host "Reply Snippet: $($res4.reply.Substring(0, [Math]::Min(120, $res4.reply.Length)))..." -ForegroundColor Gray

    if ($res4.language -ne "bn") {
        Write-Error "FAIL: Test 4 expected language 'bn' for Banglish statement but got '$($res4.language)'"
    }
    if (-not (Contains-BengaliScript $res4.reply)) {
        Write-Error "FAIL: Test 4 expected Bengali script response for Banglish statement!"
    }
    # Verify technical terms preserved
    if ($res4.reply -notmatch "Java" -and $res4.reply -notmatch "21") {
        Write-Host "Note: Technical terms Java 21 should be preserved in response." -ForegroundColor DarkYellow
    }
    Write-Host "PASS: Test 4 - Correctly detected Banglish statement and responded in Bengali script." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # TEST 5: "What is Personal Memory?" -> Expected: English response
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 5] Input: 'What is Personal Memory?'" -ForegroundColor Yellow
    $res5 = Invoke-ChatRequest -message "What is Personal Memory?" -stream $false
    Write-Host "Detected Language: $($res5.language)" -ForegroundColor Cyan
    Write-Host "Reply Snippet: $($res5.reply.Substring(0, [Math]::Min(120, $res5.reply.Length)))..." -ForegroundColor Gray

    if ($res5.language -ne "en") {
        Write-Error "FAIL: Test 5 expected language 'en' but got '$($res5.language)'"
    }
    if (Contains-BengaliScript $res5.reply) {
        Write-Error "FAIL: Test 5 expected English response but found Bengali script!"
    }
    Write-Host "PASS: Test 5 - Correctly detected English and responded in English." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # TEST 6: "আমার project সম্পর্কে তুমি কী জানো?" -> Expected: Bengali response with Memory
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 6] Input: 'আমার project সম্পর্কে তুমি কী জানো?' (Memory Retrieval in Bengali)" -ForegroundColor Yellow
    $res6 = Invoke-ChatRequest -message "আমার project সম্পর্কে তুমি কী জানো?" -stream $false
    Write-Host "Detected Language: $($res6.language)" -ForegroundColor Cyan
    Write-Host "Reply Snippet: $($res6.reply.Substring(0, [Math]::Min(140, $res6.reply.Length)))..." -ForegroundColor Gray
    Write-Host "Memories Injected: $($res6.memoriesUsed -join '; ')" -ForegroundColor Gray

    if ($res6.language -ne "bn") {
        Write-Error "FAIL: Test 6 expected language 'bn' but got '$($res6.language)'"
    }
    if (-not (Contains-BengaliScript $res6.reply)) {
        Write-Error "FAIL: Test 6 expected Bengali script response!"
    }
    # Verify personal memory facts were recalled
    $recalledMemory = ($res6.reply -match "NEXORA" -or $res6.reply -match "নেক্সোরা") -and ($res6.reply -match "Java" -or $res6.reply -match "জাভা" -or $res6.reply -match "21" -or $res6.reply -match "২১")
    if (-not $recalledMemory) {
        Write-Error "FAIL: Test 6 did not recall stored memory (NEXORA / Java 21) in Bengali response! Reply: $($res6.reply)"
    }
    Write-Host "PASS: Test 6 - Correctly retrieved Personal Memory and responded in Bengali script." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # TEST 7: Streaming Mode Language Matching
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 7] Streaming Mode Verification (English and Bengali)..." -ForegroundColor Yellow
    $streamEn = Invoke-ChatRequest -message "Explain what NEXORA is." -stream $true
    Write-Host "Stream English Lang: $($streamEn.language) | Contains Bengali: $(Contains-BengaliScript $streamEn.reply)" -ForegroundColor Cyan
    if ($streamEn.language -ne "en" -or (Contains-BengaliScript $streamEn.reply)) {
        Write-Error "FAIL: Streaming English request failed language rules!"
    }

    $streamBn = Invoke-ChatRequest -message "NEXORA ki?" -stream $true
    Write-Host "Stream Banglish Lang: $($streamBn.language) | Contains Bengali: $(Contains-BengaliScript $streamBn.reply)" -ForegroundColor Cyan
    if ($streamBn.language -ne "bn" -or (-not (Contains-BengaliScript $streamBn.reply))) {
        Write-Error "FAIL: Streaming Banglish request failed language rules!"
    }
    Write-Host "PASS: Test 7 - Streaming responses follow identical language matching rules." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # TEST 8: Multi-turn Chat Language Switching
    # Previous turn Bengali must NOT force English to be Bengali
    # Previous turn English must NOT force Bengali to be English
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 8] Multi-turn Language Switching in Same Conversation..." -ForegroundColor Yellow
    # Conversation history with previous Bengali turn
    $chatHistory = @(
        @{ role = "user"; content = "NEXORA কী?" },
        @{ role = "assistant"; content = "NEXORA হলো একটি বুদ্ধিমান AI অ্যাসিস্ট্যান্ট।" }
    )

    # Next user message in same chat is in English:
    $resTurn2 = Invoke-ChatRequest -message "Explain what NEXORA is." -messages $chatHistory -stream $false
    Write-Host "Turn 2 (English after Bengali): Lang=$($resTurn2.language) | Contains Bengali: $(Contains-BengaliScript $resTurn2.reply)" -ForegroundColor Cyan
    if ($resTurn2.language -ne "en" -or (Contains-BengaliScript $resTurn2.reply)) {
        Write-Error "FAIL: Multi-turn failed! Previous Bengali turn forced English message to respond in Bengali!"
    }

    # Add Turn 2 to history
    $chatHistory += @{ role = "user"; content = "Explain what NEXORA is." }
    $chatHistory += @{ role = "assistant"; content = $resTurn2.reply }

    # Turn 3 user message switches back to Banglish:
    $resTurn3 = Invoke-ChatRequest -message "Amar project Java 21 diye banano." -messages $chatHistory -stream $false
    Write-Host "Turn 3 (Banglish after English): Lang=$($resTurn3.language) | Contains Bengali: $(Contains-BengaliScript $resTurn3.reply)" -ForegroundColor Cyan
    if ($resTurn3.language -ne "bn" -or (-not (Contains-BengaliScript $resTurn3.reply))) {
        Write-Error "FAIL: Multi-turn failed! Previous English turn forced Banglish message to respond in English!"
    }
    Write-Host "PASS: Test 8 - Multi-turn language switching works dynamically per message." -ForegroundColor Green

    Write-Host "`n=============================================================" -ForegroundColor Green
    Write-Host "   ALL 8 LANGUAGE MATCHING TESTS PASSED SUCCESSFULLY!        " -ForegroundColor Green
    Write-Host "=============================================================" -ForegroundColor Green

} finally {
    Stop-NexoraServer $serverProc
    if (Test-Path $testMemFile) { Remove-Item $testMemFile -Force }
}
