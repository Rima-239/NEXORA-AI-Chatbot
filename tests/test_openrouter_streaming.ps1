# test_openrouter_streaming.ps1
Add-Type -AssemblyName System.Net.Http

$client = [System.Net.Http.HttpClient]::new()
$content = [System.Net.Http.StringContent]::new('{"messages":[{"role":"user","content":"What is Java?"}],"model":"openrouter/free","provider":"PUBLIC","stream":true}', [System.Text.Encoding]::UTF8, "application/json")
$req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "http://127.0.0.1:8080/api/chat")
$req.Content = $content
$req.Headers.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new("text/event-stream"))

try {
    $httpRes = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    $stream = $httpRes.Content.ReadAsStreamAsync().Result
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)

    $accumulated = ""
    $chunks = 0
    while (-not $reader.EndOfStream) {
        $line = $reader.ReadLine()
        if ($line -eq "data: [DONE]") { break }
        if ($line -match '^data: (\{.*\})$') {
            $json = $matches[1] | ConvertFrom-Json
            if ($json.chunk) {
                $accumulated += $json.chunk
                $chunks++
            } elseif ($json.error) {
                Write-Host "Stream error: $($json.error)" -ForegroundColor Red
            }
        }
    }
    Write-Host "OpenRouter Streaming: SUCCESS" -ForegroundColor Green
    Write-Host "Chunks received: $chunks" -ForegroundColor Green
    Write-Host "Accumulated length: $($accumulated.Length)" -ForegroundColor Green
    Write-Host "Sample: $($accumulated.Substring(0, [math]::Min(100, $accumulated.Length)))..." -ForegroundColor Cyan
} catch {
    Write-Host "Streaming failed: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    $client.Dispose()
}
