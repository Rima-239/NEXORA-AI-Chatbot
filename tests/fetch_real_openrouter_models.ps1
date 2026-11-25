# fetch_real_openrouter_models.ps1
try {
    $res = Invoke-RestMethod -Uri "https://openrouter.ai/api/v1/models" -TimeoutSec 15
    $models = $res.data
    Write-Host "Total OpenRouter models returned: $($models.Count)" -ForegroundColor Green

    $freeModels = $models | Where-Object {
        $p = $_.pricing
        ($p -ne $null -and $p.prompt -eq 0 -and $p.completion -eq 0) -or ($_.id -match ':free$') -or ($_.id -eq 'openrouter/free')
    }
    Write-Host "Free models count: $($freeModels.Count)" -ForegroundColor Cyan
    Write-Host "`nSample of current free models:" -ForegroundColor Yellow
    $freeModels | Select-Object -First 15 | ForEach-Object {
        Write-Host "  - ID: $($_.id) | Name: $($_.name) | Context: $($_.context_length)"
    }
} catch {
    Write-Host "Error fetching models: $($_.Exception.Message)" -ForegroundColor Red
}
