param(
    [string]$BackupRoot = 'F:\Online_used_marketplace_backups'
)

$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$backupBase = [IO.Path]::GetFullPath($BackupRoot).TrimEnd('\')
if ($backupBase.Equals($workspace, [StringComparison]::OrdinalIgnoreCase) -or
    $backupBase.StartsWith($workspace + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backup must be outside the project workspace.'
}
$backup = Join-Path $backupBase ('github-cleanup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
if (Test-Path -LiteralPath $backup) { throw 'Backup destination already exists.' }
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$snapshot = Join-Path $backup 'workspace-before-cleanup'

# Copy source, documents, reports and local configuration, excluding rebuildable dependencies.
& robocopy $workspace $snapshot /E /XJ /R:1 /W:1 /NP /NFL /NDL /NJH /NJS /XD `
    .git target node_modules .venv-deepeval .venv-ragas .venv-ragas64
if ($LASTEXITCODE -ge 8) { throw "Workspace backup failed: robocopy=$LASTEXITCODE" }
& git -C $workspace bundle create (Join-Path $backup 'repository-history.bundle') --all
if ($LASTEXITCODE -ne 0) { throw 'Git history backup failed.' }

$relativePaths = @(
    'docs/AGENT_LEARNING_GUIDE.md',
    'docs/INTERVIEW_DEMO_GUIDE.md',
    'docs/INTERVIEW_PROJECT_PREP_20260730.md',
    'docs/interview-agent-rag-review.md',
    'docs/PROJECT_FIVE_CHAINS_MEMORY_CARD.md',
    'docs/RESUME_HUIXUE_PROJECT_20260729.md',
    'docs/RESUME_OPTIMIZED_20260729.md',
    'docs/毕业设计选题说明.md',
    'tools/generate_optimized_resume.py',
    'tools/resume_metrics_summary.py',
    'pelican-bike.html'
)
$records = @()
foreach ($relative in $relativePaths) {
    $source = [IO.Path]::GetFullPath((Join-Path $workspace $relative))
    if (-not $source.StartsWith($workspace + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Source outside workspace: $relative"
    }
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }
    $copy = Join-Path $snapshot $relative
    $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    if (-not (Test-Path -LiteralPath $copy -PathType Leaf) -or
        (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash -ne $sourceHash) {
        throw "Backup verification failed: $relative"
    }
    $destination = [IO.Path]::GetFullPath((Join-Path (Join-Path $backup 'archived-personal-materials') $relative))
    if (-not $destination.StartsWith($backup + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Archive outside backup: $relative"
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Move-Item -LiteralPath $source -Destination $destination
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $sourceHash) {
        throw "Archive verification failed: $relative"
    }
    $records += [pscustomobject]@{ RelativePath = $relative; ArchivedPath = $destination; Sha256 = $sourceHash }
}
$manifest = [pscustomobject]@{
    CreatedAt = (Get-Date -Format o)
    Workspace = $workspace
    Backup = $backup
    HeadBeforeCleanup = (& git -C $workspace rev-parse HEAD)
    ArchivedFiles = $records
    ExcludedRebuildableDirectories = @('.git', 'target', 'node_modules', '.venv-deepeval', '.venv-ragas', '.venv-ragas64')
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $backup 'backup-manifest.json') -Encoding utf8
Write-Output "Backup=$backup"
Write-Output "ArchivedFiles=$($records.Count)"
