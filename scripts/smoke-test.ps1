param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$FrontendUrl = 'http://localhost:5173',
    [int]$DocId = 19,
    [switch]$Strict
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$arguments = @(
    'tools/demo_preflight.py',
    '--base-url', $BaseUrl,
    '--frontend-url', $FrontendUrl,
    '--doc-id', $DocId
)

if ($Strict) {
    $arguments += '--strict'
}

python @arguments
exit $LASTEXITCODE
