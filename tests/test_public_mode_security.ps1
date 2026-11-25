# ==============================================================================
# NEXORA - Public Cloud Mode Security & Privacy Test Probe
# Verifies zero-leakage, 403 enforcement, and stateless client memory in PUBLIC mode
# ==============================================================================

$ProgressPreference = 'SilentlyContinue'
$port = 8085
$env:PORT = $port
$env:APP_MODE = "PUBLIC"

Write-Host "Starting NEXORA test server in PUBLIC mode on port $port..." -ForegroundColor Cyan
$pinfo = New-Object System.Diagnostics.ProcessStartInfo
$pinfo.FileName = "java"
$pinfo.Arguments = "-cp bin com.nexora.Main"
$pinfo.EnvironmentVariables["PORT"] = "$port"
$pinfo.EnvironmentVariables["APP_MODE"] = "PUBLIC"
$pinfo.UseShellExecute = $false
$pinfo.RedirectStandardOutput = $true
$pinfo.RedirectStandardError = $true
$pinfo.CreateNoWindow = $true

$process = [System.Diagnostics.Process]::Start($pinfo)
Start-Sleep -Seconds 4

$baseUrl = "http://127.0.0.1:$port"
$passed = 0
$total = 0

function Assert-Check($name, $condition, $msg) {
    $global:total++
    if ($condition) {
        $global:passed++
        Write-Host " [PASS] $name : $msg" -ForegroundColor Green
    } else {
        Write-Host " [FAIL] $name : $msg" -ForegroundColor Red
    }
}

try {
    # 1. Config endpoint reports PUBLIC mode
    $resConfig = Invoke-RestMethod -Uri "$baseUrl/api/config" -Method Get
    Assert-Check "1. App Config Mode" ($resConfig.mode -eq "PUBLIC") "Reported mode: $($resConfig.mode)"

    # 2. Installed models returns empty array without exposing local Ollama
    $resModels = Invoke-RestMethod -Uri "$baseUrl/api/models/installed" -Method Get
    Assert-Check "2. Installed Models Hidden" ($resModels.success -and $resModels.models.Count -eq 0) "Count: $($resModels.models.Count)"

    # 3. Pull endpoint returns 403 Forbidden
    $pullBlocked = $false
    try {
        Invoke-RestMethod -Uri "$baseUrl/api/models/pull" -Method Post -Body '{"model":"llama3"}' -ContentType "application/json"
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 403) { $pullBlocked = $true }
    }
    Assert-Check "3. Pull Endpoint 403" $pullBlocked "Ollama pull mutation rejected with HTTP 403"

    # 4. Delete endpoint returns 403 Forbidden
    $deleteBlocked = $false
    try {
        Invoke-RestMethod -Uri "$baseUrl/api/models/delete" -Method Post -Body '{"model":"qwen2.5:7b","confirmed":true}' -ContentType "application/json"
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 403) { $deleteBlocked = $true }
    }
    Assert-Check "4. Delete Endpoint 403" $deleteBlocked "Ollama delete mutation rejected with HTTP 403"

    # 5. Server Memory mutation returns 403 Forbidden
    $memoryBlocked = $false
    try {
        Invoke-RestMethod -Uri "$baseUrl/api/memory" -Method Post -Body '{"fact":"test user fact"}' -ContentType "application/json"
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 403) { $memoryBlocked = $true }
    }
    Assert-Check "5. Server Memory 403" $memoryBlocked "Server disk memory write rejected with HTTP 403"

    # 6. PWA assets check: icon-192.png, icon-512.png, manifest.json
    $resIcon192 = Invoke-WebRequest -Uri "$baseUrl/assets/icon-192.png" -Method Get -UseBasicParsing
    Assert-Check "6. icon-192.png Served" ($resIcon192.StatusCode -eq 200 -and $resIcon192.Headers["Content-Type"] -match "image/png") "Status 200, PNG Content-Type"

    $resIcon512 = Invoke-WebRequest -Uri "$baseUrl/assets/icon-512.png" -Method Get -UseBasicParsing
    Assert-Check "7. icon-512.png Served" ($resIcon512.StatusCode -eq 200 -and $resIcon512.Headers["Content-Type"] -match "image/png") "Status 200, PNG Content-Type"

    $resManifest = Invoke-RestMethod -Uri "$baseUrl/manifest.json" -Method Get
    $hasPngIcons = @($resManifest.icons | Where-Object { $_.src -eq "/assets/icon-192.png" }).Count -gt 0
    Assert-Check "8. Manifest PNG Icons" $hasPngIcons "Manifest contains 192x192 PNG icon"

} finally {
    Write-Host "`nStopping PUBLIC mode test server..." -ForegroundColor Cyan
    if ($process -and -not $process.HasExited) {
        $process.Kill()
    }
}

Write-Host "=========================================================="
Write-Host " PUBLIC MODE VERIFICATION: $passed / $total Passed" -ForegroundColor Yellow
Write-Host "=========================================================="
if ($passed -eq $total) { exit 0 } else { exit 1 }
