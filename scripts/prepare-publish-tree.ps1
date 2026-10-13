param(
    [Parameter(Mandatory)][string]$PublishRoot,
    [Parameter(Mandatory)][string]$BackupRoot
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$publish = [IO.Path]::GetFullPath($PublishRoot).TrimEnd('\')
$backup = [IO.Path]::GetFullPath($BackupRoot).TrimEnd('\')
if ($publish -eq $workspace -or $publish.StartsWith($workspace + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Publish tree must be an independent checkout outside the workspace.'
}
if (-not (Test-Path -LiteralPath (Join-Path $publish '.git')) -or
    -not (Test-Path -LiteralPath (Join-Path $backup 'backup-manifest.json'))) {
    throw 'A checkout and a verified backup manifest are required.'
}
$privatePattern = '(^tmp/|^output/|^eval/reports/|^\.deepeval/|^\.env$|^\.env\.(?!example$)|application-idea\.yml$|(^|/)node_modules/|(^|/)target/|^\.venv|^\.idea/|^\.run/)'
$paths = @(& git -C $workspace -c core.quotepath=false ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate source files.' }
$copied = @()
foreach ($relative in ($paths | Sort-Object -Unique)) {
    if ($relative -match $privatePattern) { continue }
    $source = [IO.Path]::GetFullPath((Join-Path $workspace $relative))
    $destination = [IO.Path]::GetFullPath((Join-Path $publish $relative))
    if (-not $source.StartsWith($workspace + '\', [StringComparison]::OrdinalIgnoreCase) -or
        -not $destination.StartsWith($publish + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unexpected path: $relative"
    }
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Force
    $sha = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $sha) {
        throw "Publish copy verification failed: $relative"
    }
    $copied += [pscustomobject]@{ RelativePath=$relative; Sha256=$sha }
}

# Preserve older remote copies of the archived materials too, instead of deleting them.
$manifest = Get-Content -LiteralPath (Join-Path $backup 'backup-manifest.json') -Raw | ConvertFrom-Json
foreach ($record in $manifest.ArchivedFiles) {
    $relative = $record.RelativePath
    $source = [IO.Path]::GetFullPath((Join-Path $publish $relative))
    $destination = [IO.Path]::GetFullPath((Join-Path (Join-Path $backup 'archived-remote-materials') $relative))
    if (-not $source.StartsWith($publish + '\', [StringComparison]::OrdinalIgnoreCase) -or
        -not $destination.StartsWith($backup + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Unsafe archive path.'
    }
    if (Test-Path -LiteralPath $source -PathType Leaf) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Move-Item -LiteralPath $source -Destination $destination
    }
}

# Keep the local profile out of the public index; the original workspace remains untouched.
& git -C $publish rm --cached --ignore-unmatch -- src/main/resources/application-idea.yml
if ($LASTEXITCODE -ne 0) { throw 'Cannot untrack machine-specific profile.' }
$copied | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $backup 'published-source-manifest.json') -Encoding utf8
Write-Output "CopiedPublicFiles=$($copied.Count)"
