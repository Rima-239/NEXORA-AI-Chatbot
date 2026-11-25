# test_openrouter_free.ps1
$body = '{"messages":[{"role":"user","content":"Say Hello in 5 words."}],"model":"openrouter/free","provider":"PUBLIC","stream":false}'
try {
    $res = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/chat' -Method Post -Body $body -ContentType 'application/json' -TimeoutSec 30
    Write-Host "OpenRouter Status: SUCCESS" -ForegroundColor Green
    Write-Host "Model returned: $($res.model)" -ForegroundColor Green
    Write-Host "Reply: $($res.reply)" -ForegroundColor Cyan
} catch {
    Write-Host "Error: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails) {
        Write-Host "Details: $($_.ErrorDetails.Message)" -ForegroundColor Yellow
    }
}
