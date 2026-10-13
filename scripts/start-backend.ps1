param(
    [string]$Profile = 'idea'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

& "$PSScriptRoot\check-env.ps1"
& "$root\mvnw.cmd" "-Dspring-boot.run.profiles=$Profile" spring-boot:run
exit $LASTEXITCODE
