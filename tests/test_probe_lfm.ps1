# test_probe_lfm.ps1
$b = @{
    provider = "PUBLIC"
    model = "liquid/lfm-3-1.7b:free"
    stream = $false
    messages = @(@{ role = "user"; content = "Say OK" })
} | ConvertTo-Json -Depth 5

try {
    $r = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/chat" -Method Post -ContentType "application/json; charset=UTF-8" -Body $b
    Write-Host "Success: $($r.message.content)" -ForegroundColor Green
} catch {
    if ($_.ErrorDetails) {
        Write-Host "Error Details: $($_.ErrorDetails.Message)" -ForegroundColor Red
    } else {
        Write-Host "Exception: $($_.Exception.Message)" -ForegroundColor Red
    }
}
