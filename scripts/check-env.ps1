param(
    [switch]$Strict,
    [int]$TimeoutMs = 1200
)

$required = @(
    'MYSQL_PASSWORD',
    'JWT_SECRET',
    'REDIS_PASSWORD'
)

$optional = @(
    'OPENAI_API_KEY',
    'ELASTICSEARCH_URIS',
    'MILVUS_HOST',
    'RABBITMQ_HOST',
    'PROMETHEUS_BASE_URL'
)

$missing = @()
$unreachable = @()

function Test-TcpEndpoint {
    param(
        [string]$Name,
        [string]$HostName,
        [int]$Port
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.ConnectAsync($HostName, $Port)
        if (-not $connect.Wait($TimeoutMs) -or -not $client.Connected) {
            throw 'connection timed out'
        }
        Write-Host "[OK] $Name reachable at ${HostName}:$Port" -ForegroundColor Green
    } catch {
        Write-Host "[WARN] $Name unavailable at ${HostName}:$Port" -ForegroundColor Yellow
        $script:unreachable += $Name
    } finally {
        $client.Dispose()
    }
}

function Resolve-EndpointFromUrl {
    param(
        [string]$Value,
        [string]$DefaultHost,
        [int]$DefaultPort
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return @($DefaultHost, $DefaultPort)
    }
    $normalized = $Value -replace '^jdbc:', ''
    try {
        $uri = [Uri]$normalized
        $port = if ($uri.IsDefaultPort) { $DefaultPort } else { $uri.Port }
        return @($uri.Host, $port)
    } catch {
        return @($DefaultHost, $DefaultPort)
    }
}

function Get-EnvOrDefault {
    param([string]$Name, [string]$DefaultValue)
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { return $DefaultValue }
    return $value
}

foreach ($name in $required) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        Write-Host "[FAIL] $name is not set" -ForegroundColor Red
        $missing += $name
    } else {
        Write-Host "[OK] $name is set" -ForegroundColor Green
    }
}

foreach ($name in $optional) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        Write-Host "[WARN] $name is not set" -ForegroundColor Yellow
    } else {
        Write-Host "[OK] $name is set" -ForegroundColor Green
    }
}

$mysql = Resolve-EndpointFromUrl $env:MYSQL_URL 'localhost' 3306
$postgres = Resolve-EndpointFromUrl $env:MEMORY_GRAPH_PG_URL 'localhost' 5432
$elasticsearch = Resolve-EndpointFromUrl $env:ELASTICSEARCH_URIS 'localhost' 9200
$prometheus = Resolve-EndpointFromUrl $env:PROMETHEUS_BASE_URL 'localhost' 9090
$minio = Resolve-EndpointFromUrl $env:MINIO_ENDPOINT 'localhost' 9000

Test-TcpEndpoint 'MySQL' $mysql[0] $mysql[1]
Test-TcpEndpoint 'PostgreSQL' $postgres[0] $postgres[1]
Test-TcpEndpoint 'Redis' (Get-EnvOrDefault 'REDIS_HOST' 'localhost') ([int](Get-EnvOrDefault 'REDIS_PORT' '6379'))
Test-TcpEndpoint 'RabbitMQ' (Get-EnvOrDefault 'RABBITMQ_HOST' 'localhost') ([int](Get-EnvOrDefault 'RABBITMQ_PORT' '5672'))
Test-TcpEndpoint 'Elasticsearch' $elasticsearch[0] $elasticsearch[1]
Test-TcpEndpoint 'Milvus' (Get-EnvOrDefault 'MILVUS_HOST' 'localhost') ([int](Get-EnvOrDefault 'MILVUS_PORT' '19530'))
Test-TcpEndpoint 'Prometheus' $prometheus[0] $prometheus[1]
Test-TcpEndpoint 'MinIO' $minio[0] $minio[1]

if (($missing.Count -gt 0 -or $unreachable.Count -gt 0) -and $Strict) {
    exit 1
}

if ($missing.Count -gt 0) {
    Write-Host "Environment is incomplete. Values were not printed." -ForegroundColor Yellow
} else {
    Write-Host "Required environment variables are present. Values were not printed." -ForegroundColor Green
}

if ($unreachable.Count -gt 0) {
    Write-Host "Unavailable services: $($unreachable -join ', ')" -ForegroundColor Yellow
}
