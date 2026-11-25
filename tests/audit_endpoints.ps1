# Audit all NEXORA REST endpoints
$base = "http://127.0.0.1:8080"
$endpoints = @(
    "/api/health",
    "/api/config",
    "/api/models",
    "/api/models/installed",
    "/api/models/catalog?freeOnly=true",
    "/api/models/settings",
    "/api/models/profiles",
    "/api/models/favorites",
    "/api/models/performance",
    "/api/models/hardware",
    "/api/memory?userId=test_audit_user"
)

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " NEXORA REST Endpoints Audit Report                      " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$passCount = 0
$failCount = 0

foreach ($ep in $endpoints) {
    try {
        $res = Invoke-RestMethod -Uri ($base + $ep) -Method Get -TimeoutSec 5
        $status = "200 OK"
        $jsonPreview = ($res | ConvertTo-Json -Compress -Depth 2)
        if ($jsonPreview.Length -gt 75) { $jsonPreview = $jsonPreview.Substring(0, 75) + "..." }
        Write-Host ("[API PASS] " + $ep.PadRight(35) + " -> " + $status + " | " + $jsonPreview) -ForegroundColor Green
        $passCount++
    } catch {
        Write-Host ("[API FAIL] " + $ep.PadRight(35) + " -> " + $_.Exception.Message) -ForegroundColor Red
        $failCount++
    }
}

# Test POST /api/chat
try {
    $chatBody = '{"message":"Ping test","model":"qwen2.5:3b","stream":false}'
    $chatRes = Invoke-RestMethod -Uri "$base/api/chat" -Method Post -Body $chatBody -ContentType "application/json" -TimeoutSec 20
    $chatPassed = ($chatRes.reply -ne $null)
    if ($chatPassed) {
        Write-Host ("[API PASS] " + "POST /api/chat (buffered)".PadRight(35) + " -> 200 OK | Model: " + $chatRes.model + ", Reply length: " + $chatRes.reply.Length) -ForegroundColor Green
        $passCount++
    } else {
        Write-Host ("[API FAIL] " + "POST /api/chat (buffered)".PadRight(35) + " -> Missing reply") -ForegroundColor Red
        $failCount++
    }
} catch {
    Write-Host ("[API FAIL] " + "POST /api/chat (buffered)".PadRight(35) + " -> " + $_.Exception.Message) -ForegroundColor Red
    $failCount++
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Total Audited: $($endpoints.Count + 1) | Passed: $passCount | Failed: $failCount" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
