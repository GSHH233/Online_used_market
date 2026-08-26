[CmdletBinding()]
param(
    [ValidateSet('system', 'deepeval', 'ragas', 'ragas64')]
    [string]$PythonEnvironment = 'system'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$global:OutputEncoding = $utf8
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$env:ONLINE_USED_MARKETPLACE_ROOT = $projectRoot

$pythonPath = switch ($PythonEnvironment) {
    'deepeval' { Join-Path $projectRoot '.venv-deepeval\Scripts\python.exe' }
    'ragas' { Join-Path $projectRoot '.venv-ragas\Scripts\python.exe' }
    'ragas64' { Join-Path $projectRoot '.venv-ragas64\Scripts\python.exe' }
    'system' { (& python -c "import sys; print(sys.executable)").Trim() }
}

if (-not (Test-Path -LiteralPath $pythonPath -PathType Leaf)) {
    throw "Python interpreter not found: $pythonPath"
}

$env:PROJECT_PYTHON = $pythonPath
Write-Host "Project shell ready. Python=$env:PROJECT_PYTHON; Encoding=utf-8"

if ($MyInvocation.InvocationName -ne '.') {
    Write-Warning 'Run this script with a leading dot to preserve its environment variables in the current shell.'
}
