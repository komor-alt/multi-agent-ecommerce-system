param(
    [ValidateSet('SMOKE', 'PILOT', 'FULL')]
    [string]$Scope = 'SMOKE',
    [ValidateSet('CORE', 'FULL')]
    [string]$Arms = 'CORE',
    [int]$CallsPerCase = 14,
    [int]$MaxOutputTokens = 2048,
    [int]$Seed = 20261030,
    [string]$Schema = 'agent_benchmark',
    [string]$DatasourceUrl = 'jdbc:postgresql://127.0.0.1:5432/ecommerce_agent',
    [string]$DatasourceUsername = 'ecommerce',
    [string]$DatasourcePassword = 'ecommerce',
    [switch]$ConfirmPaidRun
)

$ErrorActionPreference = 'Stop'
if (-not $ConfirmPaidRun) {
    throw 'Paid benchmark is disabled. Re-run with -ConfirmPaidRun only after explicit authorization.'
}

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$java = Join-Path $repo 'java'
$dotenv = Join-Path $repo '.env'
if (-not (Test-Path -LiteralPath $dotenv)) { throw 'Local .env is required.' }
$gitStatus = @(& git -C $repo status --porcelain=v1)
if ($LASTEXITCODE -ne 0) { throw 'Unable to verify Git worktree state.' }
if ($gitStatus.Count -ne 0) { throw 'Git worktree must be clean.' }

$allowed = @('ECOM_LLM_API_KEY', 'ECOM_LLM_BASE_URL', 'ECOM_LLM_MODEL', 'ECOM_RECOMMEND_LLM_PROVIDER')
$previousAllowedEnvironment = @{}
foreach ($name in $allowed) {
    $previousAllowedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
Get-Content -LiteralPath $dotenv | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
        $parts = $line -split '=', 2
        $name = $parts[0].Trim()
        if (($allowed -contains $name) -and
                [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            $value = $parts[1].Trim().Trim('"').Trim("'")
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

if ([string]::IsNullOrWhiteSpace($env:ECOM_LLM_API_KEY)) { throw 'ECOM_LLM_API_KEY is missing.' }
if ([string]::IsNullOrWhiteSpace($env:ECOM_LLM_MODEL)) { throw 'ECOM_LLM_MODEL is missing.' }

$caseCount = switch ($Scope) { 'SMOKE' { 3 } 'PILOT' { 9 } default { 102 } }
$armCount = if ($Arms -eq 'CORE') { 2 } else { 6 }
$totalBudget = $caseCount * $armCount * $CallsPerCase

$benchmarkEnvironment = [ordered]@{
    ECOM_RUN_LIVE_AGENT_BENCHMARK = 'true'
    ECOM_AGENT_BENCHMARK_SCOPE = $Scope
    ECOM_AGENT_BENCHMARK_ARMS = $Arms
    ECOM_RUN_LIVE_RECOMMENDATION_E2E = 'true'
    ECOM_RECOMMEND_MODE = 'LLM'
    ECOM_RECOMMEND_LIVE_ENABLED = 'true'
    ECOM_RECOMMEND_MAX_LLM_CALLS = "$CallsPerCase"
    ECOM_SUPERVISOR_MAX_LLM_CALLS = "$CallsPerCase"
    ECOM_AGENT_BENCHMARK_MAX_TOTAL_LLM_CALLS = "$totalBudget"
    ECOM_AGENT_BENCHMARK_MAX_OUTPUT_TOKENS = "$MaxOutputTokens"
    ECOM_AGENT_BENCHMARK_SEED = "$Seed"
    ECOM_AGENT_BENCHMARK_SCHEMA = $Schema
    ECOM_RECOMMEND_LLM_TIMEOUT_MS = '30000'
    ECOM_AGENT_RUN_TIMEOUT = '90s'
    ECOM_EMBEDDING_LIVE_ENABLED = 'false'
    ECOM_EMBEDDING_MAX_CALLS = '0'
    ECOM_DEMO_SEED_ENABLED = 'false'
    ECOM_AGENT_RECOVERY_ENABLED = 'false'
    SPRING_DATASOURCE_URL = $DatasourceUrl
    SPRING_DATASOURCE_USERNAME = $DatasourceUsername
    SPRING_DATASOURCE_PASSWORD = $DatasourcePassword
    SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'org.postgresql.Driver'
    SPRING_DATA_REDIS_HOST = '127.0.0.1'
    SPRING_DATA_REDIS_PORT = '6379'
}
$previousEnvironment = @{}
foreach ($name in $benchmarkEnvironment.Keys) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $benchmarkEnvironment[$name], 'Process')
}

try {
    Write-Host "Live benchmark preflight: scope=$Scope arms=$Arms cases=$caseCount callsPerCase=$CallsPerCase totalBudget=$totalBudget schema=$Schema"
    Push-Location $java
    try {
        & mvn -B -Plive-agent-benchmark test
        if ($LASTEXITCODE -ne 0) {
            throw "Live benchmark Maven execution failed (exit code $LASTEXITCODE)."
        }
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $benchmarkEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
} finally {
    foreach ($name in $allowed) {
        [Environment]::SetEnvironmentVariable($name, $previousAllowedEnvironment[$name], 'Process')
    }
}
