# ==============================================================================
# NEXORA - Personal Memory Verification Test Suite
# Tests all 10 Personal Memory requirements and the exact conversational scenario
# ==============================================================================

$ErrorActionPreference = "Stop"
$testPort = 8089
$baseUrl = "http://127.0.0.1:$testPort"
$env:PORT = $testPort
$env:APP_MODE = "LOCAL"

Write-Host "=============================================================" -ForegroundColor Cyan
Write-Host "   NEXORA - Personal Memory Test Suite (Java 21 LTS)        " -ForegroundColor Cyan
Write-Host "=============================================================" -ForegroundColor Cyan

# Step 0: Compile backend
Write-Host "`n[Step 0] Compiling Java backend..." -ForegroundColor Yellow
& cmd.exe /c build.bat
if ($LASTEXITCODE -ne 0) {
    Write-Error "Build failed with exit code $LASTEXITCODE"
}

# Clean test memories directory before starting
$testMemDir = "data\memories"
if (Test-Path "data\memories\test_user.json") { Remove-Item "data\memories\test_user.json" -Force }
if (Test-Path "data\memories\user_alice.json") { Remove-Item "data\memories\user_alice.json" -Force }
if (Test-Path "data\memories\user_bob.json") { Remove-Item "data\memories\user_bob.json" -Force }

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

$serverProc = Start-NexoraServer

try {
    # --------------------------------------------------------------------------
    # REQUIREMENT 1 & USER SCENARIO: First Conversation (Auto Memory Extraction)
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 1] Saving personal memory in Conversation 1..." -ForegroundColor Yellow
    $chat1Body = @{
        userId = "test_user"
        message = "My favorite project is NEXORA, and I am building it using Java 21."
        persona = "general"
        stream = $false
    } | ConvertTo-Json

    $chat1Resp = Invoke-RestMethod -Uri "$baseUrl/api/chat" -Method POST -Body $chat1Body -ContentType "application/json; charset=UTF-8" -Headers @{"X-User-Id" = "test_user"}
    Write-Host "AI Response 1: $($chat1Resp.reply)" -ForegroundColor Gray
    Write-Host "Memory Saved Flag: $($chat1Resp.memorySaved)" -ForegroundColor Gray

    # Verify memory via /api/memory
    $memResp1 = Invoke-RestMethod -Uri "$baseUrl/api/memory?userId=test_user" -Headers @{"X-User-Id" = "test_user"}
    Write-Host "Stored memories count: $($memResp1.count)" -ForegroundColor Cyan
    if ($memResp1.count -lt 1) {
        Write-Error "FAIL: Memory was not extracted and saved for test_user"
    }
    $firstMem = $memResp1.memories[0]
    Write-Host "Memory Text: $($firstMem.text)" -ForegroundColor Green
    Write-Host "Memory Category: $($firstMem.category)" -ForegroundColor Green

    if ($firstMem.text -notmatch "NEXORA" -or $firstMem.text -notmatch "Java 21") {
        Write-Error "FAIL: Memory text does not contain expected fact 'NEXORA' and 'Java 21'"
    }
    Write-Host "PASS: Test 1 - Personal memory saved via auto-extraction." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # REQUIREMENT 2 & USER SCENARIO: Second Conversation (New Chat Retrieval)
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 2] Cross-Chat Memory Retrieval in Conversation 2 (New Chat)..." -ForegroundColor Yellow
    # Notice: New conversation with empty messages list! Only the question is sent!
    $chat2Body = @{
        userId = "test_user"
        message = "Which programming language am I using for my favorite project?"
        messages = @()
        persona = "general"
        stream = $false
    } | ConvertTo-Json

    $chat2Resp = Invoke-RestMethod -Uri "$baseUrl/api/chat" -Method POST -Body $chat2Body -ContentType "application/json; charset=UTF-8" -Headers @{"X-User-Id" = "test_user"}
    Write-Host "AI Response 2: $($chat2Resp.reply)" -ForegroundColor Cyan

    $isJava21Mentioned = ($chat2Resp.reply -match "Java" -or $chat2Resp.reply -match "জাভা" -or $chat2Resp.reply -match "NEXORA" -or $chat2Resp.reply -match "নেক্সোরা") -and ($chat2Resp.reply -match "21" -or $chat2Resp.reply -match "২১")
    if (-not $isJava21Mentioned) {
        Write-Error "FAIL: AI response did not mention Java 21! Actual: $($chat2Resp.reply)"
    }
    Write-Host "PASS: Test 2 - Memory retrieved in new chat! AI answered with Java 21." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # REQUIREMENT 3 & USER SCENARIO: Persistence Across Application Restart
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 3] Application Restart Verification..." -ForegroundColor Yellow
    Stop-NexoraServer $serverProc
    Start-Sleep -Seconds 1

    # Verify memory file exists on disk
    $memFile = "data\memories\test_user.json"
    if (-not (Test-Path $memFile)) {
        Write-Error "FAIL: Memory file $memFile was not found on disk after server shutdown!"
    }
    Write-Host "Disk Verification: $memFile exists with content: $(Get-Content $memFile)" -ForegroundColor Gray

    # Restart Server
    $serverProc = Start-NexoraServer

    # Ask the question again in a 3rd new conversation
    $chat3Body = @{
        userId = "test_user"
        message = "Which programming language am I using for my favorite project?"
        messages = @()
        persona = "general"
        stream = $false
    } | ConvertTo-Json

    $chat3Resp = Invoke-RestMethod -Uri "$baseUrl/api/chat" -Method POST -Body $chat3Body -ContentType "application/json; charset=UTF-8" -Headers @{"X-User-Id" = "test_user"}
    Write-Host "AI Response 3 (Post-Restart): $($chat3Resp.reply)" -ForegroundColor Cyan

    $isJava21Mentioned3 = ($chat3Resp.reply -match "Java" -or $chat3Resp.reply -match "জাভা" -or $chat3Resp.reply -match "NEXORA" -or $chat3Resp.reply -match "নেক্সোরা") -and ($chat3Resp.reply -match "21" -or $chat3Resp.reply -match "২১")
    if (-not $isJava21Mentioned3) {
        Write-Error "FAIL: Post-restart AI response did not mention Java 21! Actual: $($chat3Resp.reply)"
    }
    Write-Host "PASS: Test 3 - Memory persists across restarts and answers correctly." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # REQUIREMENT 4: Update an existing memory
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 4] Updating an existing memory..." -ForegroundColor Yellow
    $memId = $firstMem.id
    $updateBody = @{
        id = $memId
        userId = "test_user"
        text = "Favorite project is NEXORA Core, built using Java 21 and Ollama"
        category = "project"
    } | ConvertTo-Json

    $updateResp = Invoke-RestMethod -Uri "$baseUrl/api/memory" -Method PUT -Body $updateBody -ContentType "application/json; charset=UTF-8" -Headers @{"X-User-Id" = "test_user"}
    if ($updateResp.memory.text -notmatch "Ollama") {
        Write-Error "FAIL: Update did not persist new text."
    }
    Write-Host "PASS: Test 4 - Existing memory updated successfully." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # REQUIREMENT 5: Delete an individual memory
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 5] Deleting an individual memory..." -ForegroundColor Yellow
    # Add a temporary memory to delete
    $addTempBody = @{
        userId = "test_user"
        text = "Temporary learning memory about Docker"
        category = "tool"
    } | ConvertTo-Json
    $tempMem = Invoke-RestMethod -Uri "$baseUrl/api/memory" -Method POST -Body $addTempBody -ContentType "application/json; charset=UTF-8" -Headers @{"X-User-Id" = "test_user"}
    $delId = $tempMem.memory.id

    $delResp = Invoke-RestMethod -Uri "$baseUrl/api/memory?id=$delId&userId=test_user" -Method DELETE -Headers @{"X-User-Id" = "test_user"}
    $memListAfterDel = Invoke-RestMethod -Uri "$baseUrl/api/memory?userId=test_user" -Headers @{"X-User-Id" = "test_user"}
    $ids = $memListAfterDel.memories | ForEach-Object { $_.id }
    if ($ids -contains $delId) {
        Write-Error "FAIL: Memory $delId was not deleted."
    }
    Write-Host "PASS: Test 5 - Individual memory deleted successfully." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # REQUIREMENT 6: Clear all memories
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 6] Clear all memories..." -ForegroundColor Yellow
    $clearResp = Invoke-RestMethod -Uri "$baseUrl/api/memory?clearAll=true&userId=test_user" -Method DELETE -Headers @{"X-User-Id" = "test_user"}
    $memListAfterClear = Invoke-RestMethod -Uri "$baseUrl/api/memory?userId=test_user" -Headers @{"X-User-Id" = "test_user"}
    if ($memListAfterClear.count -ne 0) {
        Write-Error "FAIL: Memories count is $($memListAfterClear.count), expected 0."
    }
    Write-Host "PASS: Test 6 - All memories cleared successfully." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # REQUIREMENT 7 & 8: Disable auto memory saving & confirm no new memories
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 7 & 8] Disable auto memory saving and confirm no extraction..." -ForegroundColor Yellow
    $disableBody = @{
        action = "set_settings"
        userId = "test_user"
        autoMemoryEnabled = $false
    } | ConvertTo-Json
    $disResp = Invoke-RestMethod -Uri "$baseUrl/api/memory" -Method POST -Body $disableBody -ContentType "application/json; charset=UTF-8" -Headers @{"X-User-Id" = "test_user"}
    if ($disResp.autoMemoryEnabled -ne $false) {
        Write-Error "FAIL: Auto memory setting was not set to false."
    }

    # Send a prompt that would otherwise be extracted
    $disabledChatBody = @{
        userId = "test_user"
        message = "My favorite project is ProjectX, and I am building it using Rust."
        stream = $false
    } | ConvertTo-Json
    $null = Invoke-RestMethod -Uri "$baseUrl/api/chat" -Method POST -Body $disabledChatBody -ContentType "application/json; charset=UTF-8" -Headers @{"X-User-Id" = "test_user"}

    $memListDisabled = Invoke-RestMethod -Uri "$baseUrl/api/memory?userId=test_user" -Headers @{"X-User-Id" = "test_user"}
    if ($memListDisabled.count -ne 0) {
        Write-Error "FAIL: Memory was extracted even though autoMemoryEnabled was false!"
    }
    Write-Host "PASS: Test 7 & 8 - Auto memory disabled verified; zero memories created." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # REQUIREMENT 9: Separate memory isolation for different users
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 9] Multi-user memory isolation..." -ForegroundColor Yellow
    # Add memory for user_alice
    $aliceBody = @{
        userId = "user_alice"
        text = "Favorite project is Project Alpha built in Python"
        category = "project"
    } | ConvertTo-Json
    $null = Invoke-RestMethod -Uri "$baseUrl/api/memory" -Method POST -Body $aliceBody -ContentType "application/json; charset=UTF-8" -Headers @{"X-User-Id" = "user_alice"}

    # Add memory for user_bob
    $bobBody = @{
        userId = "user_bob"
        text = "Favorite project is Project Beta built in Go"
        category = "project"
    } | ConvertTo-Json
    $null = Invoke-RestMethod -Uri "$baseUrl/api/memory" -Method POST -Body $bobBody -ContentType "application/json; charset=UTF-8" -Headers @{"X-User-Id" = "user_bob"}

    $aliceMems = Invoke-RestMethod -Uri "$baseUrl/api/memory?userId=user_alice" -Headers @{"X-User-Id" = "user_alice"}
    $bobMems = Invoke-RestMethod -Uri "$baseUrl/api/memory?userId=user_bob" -Headers @{"X-User-Id" = "user_bob"}

    if ($aliceMems.memories[0].text -notmatch "Project Alpha" -or $aliceMems.memories[0].text -match "Project Beta") {
        Write-Error "FAIL: Alice memory contaminated with Bob's memory!"
    }
    if ($bobMems.memories[0].text -notmatch "Project Beta" -or $bobMems.memories[0].text -match "Project Alpha") {
        Write-Error "FAIL: Bob memory contaminated with Alice's memory!"
    }

    # Verify separate physical files on disk
    if (-not (Test-Path "data\memories\user_alice.json") -or -not (Test-Path "data\memories\user_bob.json")) {
        Write-Error "FAIL: Separate JSON files were not created on disk for user_alice and user_bob!"
    }
    Write-Host "PASS: Test 9 - Multi-user memory strictly isolated on disk and in memory." -ForegroundColor Green

    # --------------------------------------------------------------------------
    # REQUIREMENT 10: Ollama and OpenRouter context integration verification
    # --------------------------------------------------------------------------
    Write-Host "`n[Test 10] AI Provider Memory Context Integration..." -ForegroundColor Yellow
    & java -cp bin tests/TestMemoryContext.java
    if ($LASTEXITCODE -ne 0) {
        Write-Error "FAIL: Test 10 Java context integration test failed with code $LASTEXITCODE"
    }
    Write-Host "PASS: Test 10 - Context integration verified for both Ollama and OpenRouter." -ForegroundColor Green

    Write-Host "`n=============================================================" -ForegroundColor Green
    Write-Host "  ALL 10 PERSONAL MEMORY TESTS PASSED SUCCESSFULLY!          " -ForegroundColor Green
    Write-Host "=============================================================" -ForegroundColor Green

} finally {
    Stop-NexoraServer $serverProc
    # Clean up test user files
    if (Test-Path "data\memories\test_user.json") { Remove-Item "data\memories\test_user.json" -Force }
    if (Test-Path "data\memories\user_alice.json") { Remove-Item "data\memories\user_alice.json" -Force }
    if (Test-Path "data\memories\user_bob.json") { Remove-Item "data\memories\user_bob.json" -Force }
}
