param(
    [int]$Port = 5173
)

$ErrorActionPreference = 'Stop'
$frontend = Join-Path (Split-Path -Parent $PSScriptRoot) 'used_marketplace_frontend'
Set-Location $frontend

if (-not (Test-Path 'node_modules')) {
    npm ci
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

node node_modules/vite/bin/vite.js --host=127.0.0.1 "--port=$Port"
exit $LASTEXITCODE
