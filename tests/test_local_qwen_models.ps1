# ==============================================================================
# Verification Script for Local Qwen Models: qwen2.5:3b and qwen2.5:7b
# Tests direct Ollama API, NEXORA chat endpoint, streaming, and switching.
# ==============================================================================

$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Net.Http

Write-Host "=== 1. Checking Ollama Tags Endpoint ===" -ForegroundColor Cyan
$tags = Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/tags" -Method Get
$installedNames = $tags.models.name
Write-Host "Installed Models in Ollama: $($installedNames -join ', ')"

if ($installedNames -notcontains "qwen2.5:3b" -or $installedNames -notcontains "qwen2.5:7b") {
    Write-Host "[FATAL] Required Qwen models missing from Ollama!" -ForegroundColor Red
    exit 1
}

Write-Host "`n=== 2. Direct Ollama Generation: qwen2.5:3b ===" -ForegroundColor Cyan
$body3b = @{
    model = "qwen2.5:3b"
    prompt = "Reply with exactly: 'QWEN_3B_ONLINE'"
    stream = $false
} | ConvertTo-Json
$resOllama3b = Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/generate" -Method Post -Body $body3b -ContentType "application/json"
Write-Host "Response from qwen2.5:3b: $($resOllama3b.response.Trim())"

Write-Host "`n=== 3. Direct Ollama Generation: qwen2.5:7b ===" -ForegroundColor Cyan
$body7b = @{
    model = "qwen2.5:7b"
    prompt = "Reply with exactly: 'QWEN_7B_ONLINE'"
    stream = $false
} | ConvertTo-Json
$resOllama7b = Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/generate" -Method Post -Body $body7b -ContentType "application/json"
Write-Host "Response from qwen2.5:7b: $($resOllama7b.response.Trim())"

Write-Host "`n=== 4. NEXORA API Chat: qwen2.5:3b (Buffered) ===" -ForegroundColor Cyan
$nexora3b = @{
    message = "Hello from test, what is 2+2? Answer only with the number."
    model = "qwen2.5:3b"
    provider = "LOCAL"
    stream = $false
} | ConvertTo-Json
$resNexora3b = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -Body $nexora3b -ContentType "application/json"
Write-Host "NEXORA (qwen2.5:3b) Reply: $($resNexora3b.reply.Trim()) (Provider: $($resNexora3b.provider), Model: $($resNexora3b.model))"

Write-Host "`n=== 5. NEXORA API Chat: qwen2.5:7b (Buffered) ===" -ForegroundColor Cyan
$nexora7b = @{
    message = "Hello from test, what is 3+3? Answer only with the number."
    model = "qwen2.5:7b"
    provider = "LOCAL"
    stream = $false
} | ConvertTo-Json
$resNexora7b = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -Body $nexora7b -ContentType "application/json"
Write-Host "NEXORA (qwen2.5:7b) Reply: $($resNexora7b.reply.Trim()) (Provider: $($resNexora7b.provider), Model: $($resNexora7b.model))"

Write-Host "`n=== 6. NEXORA API Chat: qwen2.5:3b (SSE Streaming) ===" -ForegroundColor Cyan
$client = [System.Net.Http.HttpClient]::new()
$content3b = [System.Net.Http.StringContent]::new('{"message":"Say 1 2 3","model":"qwen2.5:3b","stream":true,"provider":"LOCAL"}', [System.Text.Encoding]::UTF8, "application/json")
$req3b = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "http://127.0.0.1:8080/api/chat")
$req3b.Content = $content3b
$req3b.Headers.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new("text/event-stream"))
$response3b = $client.SendAsync($req3b, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
$reader3b = [System.IO.StreamReader]::new($response3b.Content.ReadAsStreamAsync().Result, [System.Text.Encoding]::UTF8)
$streamChunks3b = 0
$doneFound3b = $false
$lineCount = 0
while (-not $reader3b.EndOfStream -and ($lineCount -lt 60)) {
    $line = $reader3b.ReadLine()
    $lineCount++
    if ($line -match "data: \{.*chunk.*\}") { $streamChunks3b++ }
    if ($line -match "data: \[DONE\]") { $doneFound3b = $true; break }
}
$reader3b.Close()
Write-Host "Streamed chunks received: $streamChunks3b, Terminal [DONE] found: $doneFound3b"

Write-Host "`n=== 7. NEXORA API Chat: qwen2.5:7b (SSE Streaming) ===" -ForegroundColor Cyan
$content7b = [System.Net.Http.StringContent]::new('{"message":"Say 4 5 6","model":"qwen2.5:7b","stream":true,"provider":"LOCAL"}', [System.Text.Encoding]::UTF8, "application/json")
$req7b = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "http://127.0.0.1:8080/api/chat")
$req7b.Content = $content7b
$req7b.Headers.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new("text/event-stream"))
$response7b = $client.SendAsync($req7b, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
$reader7b = [System.IO.StreamReader]::new($response7b.Content.ReadAsStreamAsync().Result, [System.Text.Encoding]::UTF8)
$streamChunks7b = 0
$doneFound7b = $false
$lineCount = 0
while (-not $reader7b.EndOfStream -and ($lineCount -lt 60)) {
    $line = $reader7b.ReadLine()
    $lineCount++
    if ($line -match "data: \{.*chunk.*\}") { $streamChunks7b++ }
    if ($line -match "data: \[DONE\]") { $doneFound7b = $true; break }
}
$reader7b.Close()
Write-Host "Streamed chunks received: $streamChunks7b, Terminal [DONE] found: $doneFound7b"

Write-Host "`n=== Local Qwen Models Verification Summary ===" -ForegroundColor Green
$allPassed = ($resOllama3b.response.Trim() -match "QWEN_3B_ONLINE") -and `
             ($resOllama7b.response.Trim() -match "QWEN_7B_ONLINE") -and `
             ($resNexora3b.reply.Trim() -match "4") -and `
             ($resNexora7b.reply.Trim() -match "6") -and `
             $doneFound3b -and $doneFound7b

if ($allPassed) {
    Write-Host "[SUCCESS] All local Ollama generation, streaming, and switching tests PASSED for both models!" -ForegroundColor Green
    exit 0
} else {
    Write-Host "[FAIL] Some local model assertions failed!" -ForegroundColor Red
    exit 1
}
