<#
.SYNOPSIS
Run opt-in PostgreSQL contracts against an isolated, temporary Docker Compose project.
.DESCRIPTION
Requires Docker Compose v2, Java 17 and Maven on PATH. Creates a random project,
loopback-only random port and random password. Does not read a developer's database
configuration. The tests additionally create and remove their own random schemas.
Without -KeepContainer, only this newly generated Compose project is removed.
Never use this script with a production database.
#>
[CmdletBinding()]
param(
    [string]$Image = 'postgres:16-alpine',
    [string]$TestFilter = 'RecommendationPostgresPersistenceTest,RecommendationExecutionLeasePostgresTest,RecommendationRecoveryProcessTest',
    [ValidateRange(10, 600)]
    [int]$StartupTimeoutSeconds = 60,
    [switch]$KeepContainer
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot 'postgres.compose.yml'
$repositoryDirectory = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$javaDirectory = Join-Path $repositoryDirectory 'java'
$projectName = 'ecom-pg-test-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$environmentNames = @(
    'ECOM_TEST_POSTGRES_IMAGE', 'ECOM_TEST_POSTGRES_PASSWORD',
    'ECOM_RUN_POSTGRES_TESTS', 'ECOM_POSTGRES_TEST_URL',
    'ECOM_POSTGRES_TEST_USER', 'ECOM_POSTGRES_TEST_PASSWORD'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$projectCreated = $false

function Assert-LastCommandSucceeded([string]$Operation) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Operation failed (exit code $LASTEXITCODE)."
    }
}

try {
    $null = Get-Command docker -ErrorAction Stop
    $null = Get-Command mvn -ErrorAction Stop
    $null = & docker info --format '{{.ServerVersion}}'
    Assert-LastCommandSucceeded 'Docker availability check'

    # An existing project is never adopted, even in the unlikely event of a name collision.
    $existingContainers = @(& docker ps --all --quiet --filter "label=com.docker.compose.project=$projectName")
    Assert-LastCommandSucceeded 'Compose project isolation check'
    if ($existingContainers.Count -ne 0) {
        throw 'Generated Compose project already exists; rerun to obtain a new project name.'
    }

    $env:ECOM_TEST_POSTGRES_IMAGE = $Image
    $env:ECOM_TEST_POSTGRES_PASSWORD = [Guid]::NewGuid().ToString('N')
    $projectCreated = $true
    & docker compose --project-name $projectName --file $composeFile up --detach --wait --wait-timeout $StartupTimeoutSeconds
    Assert-LastCommandSucceeded 'Isolated PostgreSQL startup'
    $containerId = (& docker compose --project-name $projectName --file $composeFile ps --quiet postgres).Trim()
    Assert-LastCommandSucceeded 'Test container lookup'
    if ($containerId -notmatch '^[a-f0-9]{12,64}$') {
        throw 'Expected exactly one isolated PostgreSQL container.'
    }
    $portBinding = (& docker port $containerId '5432/tcp').Trim()
    Assert-LastCommandSucceeded 'Test port lookup'
    if ($portBinding -notmatch '^127\.0\.0\.1:(\d+)$') {
        throw 'Refusing a PostgreSQL endpoint that is not exclusively bound to IPv4 loopback.'
    }
    $testPort = $Matches[1]
    $env:ECOM_RUN_POSTGRES_TESTS = 'true'
    $env:ECOM_POSTGRES_TEST_URL = "jdbc:postgresql://127.0.0.1:$testPort/ecom_validation"
    $env:ECOM_POSTGRES_TEST_USER = 'ecom_validation'
    $env:ECOM_POSTGRES_TEST_PASSWORD = $env:ECOM_TEST_POSTGRES_PASSWORD
    Write-Host "Isolated PostgreSQL: $projectName at 127.0.0.1:$testPort"
    Write-Host 'No external model API or application database is used.'

    Push-Location $javaDirectory
    try {
        & mvn -B "-Dtest=$TestFilter" test
        Assert-LastCommandSucceeded 'PostgreSQL contract tests'
    } finally {
        Pop-Location
    }
} finally {
    try {
        if ($projectCreated -and -not $KeepContainer) {
            # This invocation generated the project name and checked it did not already exist.
            # The dedicated Compose file has no external services, networks or persistent volumes.
            & docker compose --project-name $projectName --file $composeFile down --volumes
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "Could not remove isolated test project $projectName; no existing project was targeted."
            }
        } elseif ($projectCreated) {
            Write-Host "Kept isolated test project: $projectName"
            Write-Host 'Test data lives in tmpfs and is discarded when its container stops.'
        }
    } finally {
        foreach ($name in $environmentNames) {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        }
    }
}
