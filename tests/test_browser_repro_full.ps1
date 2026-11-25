# ==============================================================================
# NEXORA - Browser Reproduction & Memory Verification Suite
# Simulates exact browser behavior: SSE streaming, user ID, static cache headers,
# multi-turn chat, new chat cross-retrieval, and post-restart persistence.
# ==============================================================================

$ErrorActionPreference = "Stop"
$testPort = 8095
$baseUrl = "http://127.0.0.1:$testPort"
$env:PORT = $testPort
$env:APP_MODE = "LOCAL"

Write-Host "=============================================================" -ForegroundColor Cyan
Write-Host "   NEXORA - Browser Flow Verification Test Suite (Java 21)   " -ForegroundColor Cyan
Write-Host "=============================================================" -ForegroundColor Cyan

# Step 0: Compile backend
Write-Host "`n[Step 0] Compiling Java backend..." -ForegroundColor Yellow
& cmd.exe /c build.bat
if ($LASTEXITCODE -ne 0) {
    Write-Error "Build failed with exit code $LASTEXITCODE"
}

# Clean test memory files
$testMemFile = "data\memories\browser_tester.json"
if (Test-Path $testMemFile) { Remove-Item $testMemFile -Force }

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

function Invoke-SSEChatRequest {
    param(
        [string]$userId,
        [string]$message,
        [array]$messages = @()
    )

    $jsonObj = @{
        message = $message
        messages = $messages
        persona = "general"
        model = "qwen2.5:7b"
        stream = $true
        userId = $userId
    }
    $jsonBody = $jsonObj | ConvertTo-Json -Depth 5

    $request = [System.Net.HttpWebRequest]::Create("$baseUrl/api/chat")
    $request.Method = "POST"
    $request.ContentType = "application/json; charset=UTF-8"
    $request.Accept = "text/event-stream"
    $request.Headers.Add("X-User-Id", $userId)
    $request.Timeout = 60000

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($jsonBody)
    $request.ContentLength = $bytes.Length
    $reqStream = $request.GetRequestStream()
    $reqStream.Write($bytes, 0, $bytes.Length)
    $reqStream.Close()

    $response = $request.GetResponse()
    $respStream = $response.GetResponseStream()
    $reader = New-Object System.IO.StreamReader($respStream, [System.Text.Encoding]::UTF8)

    $accumulated = ""
    $memorySaved = $false
    $memoriesUsed = @()

    while (-not $reader.EndOfStream) {
        $line = $reader.ReadLine()
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line.Trim() -eq "data: [DONE]") { break }
        if ($line.StartsWith("data: ")) {
            $dataStr = $line.Substring(6).Trim()
            try {
                $parsed = $dataStr | ConvertFrom-Json
                if ($parsed.chunk) {
                    $accumulated += $parsed.chunk
                }
                if ($null -ne $parsed.memorySaved) {
                    $memorySaved = [bool]$parsed.memorySaved
                }
                if ($parsed.memoriesUsed) {
                    $memoriesUsed = $parsed.memoriesUsed
                }
            } catch {}
        }
    }

    $reader.Close()
    $respStream.Close()
    $response.Close()

    return [PSCustomObject]@{
        reply = $accumulated
        memorySaved = $memorySaved
        memoriesUsed = $memoriesUsed
    }
}

$serverProc = Start-NexoraServer

try {
    # --------------------------------------------------------------------------
    # TEST 1: Cache-Control Headers for Static Files (Prevent Stale JS/CSS in Browser)
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 1] Inspecting Static File Cache Headers..." -ForegroundColor Yellow
    $appJsReq = Invoke-WebRequest -Uri "$baseUrl/js/app.js" -UseBasicParsing
    $cacheControl = $appJsReq.Headers["Cache-Control"]
    Write-Host "GET /js/app.js Cache-Control: $cacheControl" -ForegroundColor Cyan
    if ($cacheControl -notmatch "no-cache" -or $cacheControl -notmatch "no-store") {
        Write-Error "FAIL: /js/app.js must serve Cache-Control: no-cache, no-store, must-revalidate"
    }

    $swReq = Invoke-WebRequest -Uri "$baseUrl/sw.js" -UseBasicParsing
    if ($swReq.Content -notmatch "nexora-v1.2.0-memory") {
        Write-Error "FAIL: sw.js does not contain updated cache name 'nexora-v1.2.0-memory'"
    }
    Write-Host "PASS: Test 1 - Cache headers and Service Worker cache name verified." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # TEST 2: Chat 1 via SSE Streaming - Automatic Memory Extraction
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 2] Chat 1 (Streaming): User states favorite project and language..." -ForegroundColor Yellow
    $userMsg1 = "My favorite project is NEXORA, and I am building it using Java 21."
    $streamResult1 = Invoke-SSEChatRequest -userId "browser_tester" -message $userMsg1
    Write-Host "Streaming AI Response 1: $($streamResult1.reply)" -ForegroundColor Gray
    Write-Host "Stream Metadata memorySaved: $($streamResult1.memorySaved)" -ForegroundColor Cyan

    if (-not $streamResult1.memorySaved) {
        Write-Error "FAIL: Stream did not emit memorySaved: true metadata event"
    }

    # Verify memory file on disk
    if (-not (Test-Path $testMemFile)) {
        Write-Error "FAIL: Memory file $testMemFile was not created on disk"
    }
    $fileJson = Get-Content $testMemFile -Raw
    Write-Host "Memory file content: $fileJson" -ForegroundColor Gray
    if ($fileJson -notmatch "NEXORA" -or $fileJson -notmatch "Java 21") {
        Write-Error "FAIL: Memory file does not contain 'NEXORA' and 'Java 21'"
    }
    Write-Host "PASS: Test 2 - Memory extracted and saved during SSE streaming." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # TEST 3: Chat 2 (New Conversation, Streaming) - Recall Memory Across Chats
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 3] Chat 2 (New Chat tab, Streaming): Asking about language..." -ForegroundColor Yellow
    $userMsg2 = "Which programming language am I using for my favorite project?"
    # In a brand new chat, messages history is empty!
    $streamResult2 = Invoke-SSEChatRequest -userId "browser_tester" -message $userMsg2 -messages @()
    Write-Host "Streaming AI Response 2: $($streamResult2.reply)" -ForegroundColor Cyan
    Write-Host "Memories injected into prompt: $($streamResult2.memoriesUsed -join '; ')" -ForegroundColor Gray

    $isRecalled = ($streamResult2.reply -match "Java 21" -or $streamResult2.reply -match "Java" -or $streamResult2.reply -match "জাভা")
    if (-not $isRecalled) {
        Write-Error "FAIL: Chat 2 did not recall Java 21! Actual reply: $($streamResult2.reply)"
    }
    Write-Host "PASS: Test 3 - Memory recalled across conversations in streaming mode! AI answered: $($streamResult2.reply)" -ForegroundColor Green

    # --------------------------------------------------------------------------
    # TEST 4: Persistence Across Application Restart (Cold Restart)
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 4] Restarting NEXORA server to verify disk persistence..." -ForegroundColor Yellow
    Stop-NexoraServer $serverProc
    Start-Sleep -Seconds 1

    $serverProc = Start-NexoraServer

    Write-Host "Chat 3 (Post-Restart, Streaming): Inquiring about project and language..." -ForegroundColor Yellow
    $userMsg3 = "What is my favorite project and what language is it in?"
    $streamResult3 = Invoke-SSEChatRequest -userId "browser_tester" -message $userMsg3 -messages @()
    Write-Host "Streaming AI Response 3: $($streamResult3.reply)" -ForegroundColor Cyan

    $isRecalled3 = ($streamResult3.reply -match "NEXORA" -or $streamResult3.reply -match "নেক্সোরা") -and ($streamResult3.reply -match "Java" -or $streamResult3.reply -match "জাভা")
    if (-not $isRecalled3) {
        Write-Error "FAIL: Post-restart response did not recall NEXORA and Java! Actual: $($streamResult3.reply)"
    }
    Write-Host "PASS: Test 4 - Memory persisted after full application restart." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # TEST 5: User Isolation (Another user must NOT see browser_tester's memory)
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 5] Multi-user isolation verification..." -ForegroundColor Yellow
    $strangerResult = Invoke-SSEChatRequest -userId "stranger_user" -message "Which programming language am I using for my favorite project?" -messages @()
    Write-Host "Stranger response: $($strangerResult.reply)" -ForegroundColor Gray
    Write-Host "Stranger memories used count: $($strangerResult.memoriesUsed.Count)" -ForegroundColor Cyan
    if ($strangerResult.memoriesUsed.Count -ne 0) {
        Write-Error "FAIL: Stranger received memories from another user!"
    }
    Write-Host "PASS: Test 5 - Strict user isolation maintained." -ForegroundColor Green

    Write-Host "`n=============================================================" -ForegroundColor Green
    Write-Host "   ALL REAL-BROWSER REPRODUCTION TESTS PASSED (5/5)!         " -ForegroundColor Green
    Write-Host "=============================================================" -ForegroundColor Green

} finally {
    Stop-NexoraServer $serverProc
    # Clean up test user memory
    if (Test-Path $testMemFile) { Remove-Item $testMemFile -Force }
}
