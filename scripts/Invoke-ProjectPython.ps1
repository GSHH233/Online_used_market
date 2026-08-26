[CmdletBinding(PositionalBinding = $true)]
param(
    [ValidateSet('system', 'deepeval', 'ragas', 'ragas64')]
    [string]$PythonEnvironment = 'system',

    [Parameter(Mandatory = $true, Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$PythonArguments
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'Initialize-ProjectShell.ps1') -PythonEnvironment $PythonEnvironment
& $env:PROJECT_PYTHON @PythonArguments
exit $LASTEXITCODE
