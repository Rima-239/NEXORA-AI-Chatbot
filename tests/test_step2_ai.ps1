# NEXORA Phase 3: AI Personas and Actions Test Suite
$ErrorActionPreference = "Continue"

function Send-ChatRequest {
    param(
        [string]$TestName,
        [string]$Message,
        [string]$Persona = "general",
        [string]$Model = "qwen2.5:7b"
    )

    Write-Host "`n>>> [TEST] $TestName ($Persona, $Model) <<<" -ForegroundColor Yellow
    Write-Host "Prompt: $Message" -ForegroundColor Gray

    $payload = @{
        message = $Message
        persona = $Persona
        model = $Model
        stream = $false
        messages = @(
            @{ role = "user"; content = $Message }
        )
    } | ConvertTo-Json -Compress -Depth 5

    $tempFile = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tempFile, $payload, (New-Object System.Text.UTF8Encoding($false)))

    $startTime = Get-Date
    $rawResponse = & curl.exe -s --noproxy "*" --max-time 120 -X POST "http://127.0.0.1:8080/api/chat" `
        -H "Content-Type: application/json; charset=UTF-8" `
        --data-binary "@$tempFile"
    $duration = ((Get-Date) - $startTime).TotalSeconds

    if (Test-Path $tempFile) { Remove-Item $tempFile -Force }

    try {
        $json = $rawResponse | ConvertFrom-Json
        $replyText = $null
        if ($json.reply) { $replyText = $json.reply }
        elseif ($json.message -and $json.message.content) { $replyText = $json.message.content }
        elseif ($json.response) { $replyText = $json.response }

        if ($json.success -eq $true -and $replyText) {
            $preview = $replyText
            if ($preview.Length -gt 250) {
                $preview = $preview.Substring(0, 250) + "..."
            }
            Write-Host "[PASS] Received in $([math]::Round($duration, 2))s | Response snippet:" -ForegroundColor Green
            Write-Host "$preview" -ForegroundColor White
            return @{ Success = $true; Response = $replyText; Duration = $duration }
        } else {
            Write-Host "[FAIL] API returned success=false or missing reply: $($json.error)" -ForegroundColor Red
            return @{ Success = $false; Error = $json.error }
        }
    } catch {
        Write-Host "[FAIL] JSON parse error: $_`nRaw output: $rawResponse" -ForegroundColor Red
        return @{ Success = $false; Error = $rawResponse }
    }
}

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host " Testing AI Personas, Actions & Languages with Live Ollama " -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan

# 2.1 General Persona
$resGen = Send-ChatRequest -TestName "2.1 General Persona" `
    -Message "In 2 sentences, what is NEXORA's privacy mission?" `
    -Persona "general"

# 2.2 Coding Persona
$resCode = Send-ChatRequest -TestName "2.2 Coding Persona" `
    -Message "Write a Java 21 record for ChatMessage with role and content fields. Code only." `
    -Persona "coding"

# 2.3 Writing Persona
$resWrite = Send-ChatRequest -TestName "2.3 Writing Persona" `
    -Message "Draft a single punchy elevator pitch for NEXORA AI." `
    -Persona "writing"

# 2.4 Learning Persona
$resLearn = Send-ChatRequest -TestName "2.4 Learning Persona" `
    -Message "Explain what an HTTP SSE (Server-Sent Events) is in 2 simple bullet points." `
    -Persona "learning"

# 2.5 Quick Action: Explain
$resExplain = Send-ChatRequest -TestName "2.5 Quick Action: Explain" `
    -Message "Explain the difference between process and thread in 2 sentences." `
    -Persona "learning"

# 2.6 Quick Action: Summarize
$resSum = Send-ChatRequest -TestName "2.6 Quick Action: Summarize" `
    -Message "Summarize: NEXORA is a self-hosted AI chatbot that runs locally without cloud tracking or subscription fees. It gives users complete data privacy." `
    -Persona "general"

# 2.7 Quick Action: Translate
$resTrans = Send-ChatRequest -TestName "2.7 Quick Action: Translate" `
    -Message "Translate this slogan to Bengali: 'Think Smarter. Create Freely. Stay Private.'" `
    -Persona "general"

# 2.8 Quick Action: Improve Writing
$resImp = Send-ChatRequest -TestName "2.8 Quick Action: Improve Writing" `
    -Message "Improve writing: 'me want to make app fast and not share data to internet.'" `
    -Persona "writing"

# 2.9 Bengali Native Conversation
$resBengali = Send-ChatRequest -TestName "2.9 Bengali Native Conversation" `
    -Message "নেক্সোরা কী? সংক্ষেপে ২টি বাক্যে বাংলায় উত্তর দিন।" `
    -Persona "general"

Write-Host "`n====================================================" -ForegroundColor Cyan
Write-Host " Persona & Action Testing Complete " -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan
