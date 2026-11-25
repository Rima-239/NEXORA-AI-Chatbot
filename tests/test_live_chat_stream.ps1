# Test exact frontend request to /api/chat with stream: true
Add-Type -AssemblyName System.Net.Http

$client = [System.Net.Http.HttpClient]::new()
$content = [System.Net.Http.StringContent]::new('{"message":"Hello","messages":[{"role":"user","content":"Hello"}],"persona":"general","model":"qwen2.5:7b","provider":"LOCAL","stream":true,"userId":"test_live_user"}', [System.Text.Encoding]::UTF8, "application/json")

$req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "http://127.0.0.1:8080/api/chat")
$req.Content = $content
$req.Headers.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new("text/event-stream"))
$req.Headers.Add("X-User-Id", "test_live_user")

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$res = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
Write-Host "Status: $($res.StatusCode)"
Write-Host "Content-Type: $($res.Content.Headers.ContentType)"

$stream = $res.Content.ReadAsStreamAsync().Result
$reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)

$lines = 0
$chunks = 0
while (-not $reader.EndOfStream -and ($lines -lt 100)) {
    $line = $reader.ReadLine()
    $lines++
    Write-Host "LINE: $line"
    if ($line -match 'data: \{"chunk"') { $chunks++ }
}

Write-Host "Done reading stream. Elapsed: $($sw.ElapsedMilliseconds)ms, Chunks: $chunks"
$client.Dispose()
