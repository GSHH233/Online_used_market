$base = "http://127.0.0.1:8080"

Write-Host "[1/2] Write L2 memory..."
Invoke-RestMethod -Method Post -Uri "$base/api/ai/l2-memory/debug/write?userId=u1&sessionId=s1&memoryType=FACT&importance=0.8&content=用户偏好苹果手机，关注电池健康和发票" | ConvertTo-Json -Depth 8

Write-Host "[2/2] Query L2 memory..."
Invoke-RestMethod -Method Get -Uri "$base/api/ai/l2-memory/debug/query?userId=u1&sessionId=s1&q=苹果手机发票电池&topK=5&threshold=0.4" | ConvertTo-Json -Depth 8
