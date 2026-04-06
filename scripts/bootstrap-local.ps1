param(
    [ValidateSet("auto", "canonical", "sample", "none")]
    [string]$CorpusMode = "auto",
    [switch]$OpenBrowser,
    [switch]$SkipDependencyInstall,
    [int]$PostgresTimeoutSeconds = 120,
    [int]$BackendTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$EnvFile = Join-Path $RepoRoot ".env"
$EnvExample = Join-Path $RepoRoot ".env.example"
$VenvDir = Join-Path $RepoRoot ".venv"
$VenvPython = Join-Path $VenvDir "Scripts\python.exe"
$BootstrapLogRoot = Join-Path $RepoRoot "data\logs\bootstrap"
$SessionTimestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$SessionLogDir = Join-Path $BootstrapLogRoot $SessionTimestamp
$BackendStdoutLog = Join-Path $SessionLogDir "backend-stdout.log"
$BackendStderrLog = Join-Path $SessionLogDir "backend-stderr.log"
$BackendPidFile = Join-Path $SessionLogDir "backend.pid"

function Write-Step {
    param([string]$Message)

    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Require-Command {
    param([string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "필수 명령을 찾을 수 없습니다: $Name"
    }
}

function Load-EnvFile {
    param([string]$Path)

    foreach ($entry in Get-Content $Path) {
        $line = $entry.Trim()
        if (-not $line -or $line.StartsWith("#")) {
            continue
        }

        $parts = $line -split "=", 2
        if ($parts.Count -ne 2) {
            continue
        }

        $name = $parts[0].Trim()
        $value = $parts[1].Trim()
        if (
            ($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))
        ) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        Set-Item -Path "Env:$name" -Value $value
    }
}

function Assert-PythonVersion {
    param([string]$PythonCommand)

    $versionText = & $PythonCommand -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')" 2>$null
    if (-not $versionText) {
        throw "Python 버전을 확인할 수 없습니다: $PythonCommand"
    }

    $parts = $versionText.Trim().Split(".")
    $major = [int]$parts[0]
    $minor = [int]$parts[1]
    if ($major -lt 3 -or ($major -eq 3 -and $minor -lt 12)) {
        throw "Python 3.12 이상이 필요합니다. 현재 감지된 버전: $versionText"
    }
}

function Ensure-Venv {
    if (-not (Test-Path $VenvPython)) {
        Write-Step ".venv 생성"
        & python -m venv $VenvDir
    }

    Assert-PythonVersion -PythonCommand $VenvPython

    if (-not $SkipDependencyInstall) {
        Write-Step "Python 의존성 설치"
        & $VenvPython -m pip install --upgrade pip
        & $VenvPython -m pip install -e (Join-Path $RepoRoot "pipeline")
    }
}

function Wait-ForPostgres {
    param([int]$TimeoutSeconds)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $status = docker inspect -f "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" query-forge-postgres 2>$null
        if ($LASTEXITCODE -eq 0 -and $status.Trim() -eq "healthy") {
            return
        }
        Start-Sleep -Seconds 2
    }

    throw "PostgreSQL 컨테이너가 $TimeoutSeconds초 안에 healthy 상태가 되지 않았습니다."
}

function Get-HealthUrl {
    $port = if ($env:BACKEND_PORT) { $env:BACKEND_PORT } else { "8080" }
    return "http://localhost:$port/actuator/health"
}

function Test-BackendHealth {
    param([string]$Url)

    try {
        $response = Invoke-RestMethod -Uri $Url -Method Get -TimeoutSec 5
        return $response.status -eq "UP"
    }
    catch {
        return $false
    }
}

function Wait-ForBackend {
    param(
        [string]$Url,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-BackendHealth -Url $Url) {
            return
        }
        Start-Sleep -Seconds 3
    }

    throw "Spring Boot 서버가 $TimeoutSeconds초 안에 기동되지 않았습니다. 로그를 확인하세요: $BackendStdoutLog"
}

function Start-BackendIfNeeded {
    param([string]$HealthUrl)

    if (Test-BackendHealth -Url $HealthUrl) {
        Write-Step "Spring Boot 서버가 이미 실행 중입니다"
        return $null
    }

    Write-Step "Spring Boot 서버 시작"
    New-Item -ItemType Directory -Force -Path $SessionLogDir | Out-Null

    $runnerScript = Join-Path $RepoRoot "scripts\run-backend.ps1"
    $process = Start-Process `
        -FilePath "powershell.exe" `
        -ArgumentList @(
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", $runnerScript
        ) `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $BackendStdoutLog `
        -RedirectStandardError $BackendStderrLog `
        -PassThru

    $process.Id | Set-Content $BackendPidFile
    return $process
}

function Invoke-Pipeline {
    param(
        [string[]]$Arguments,
        [string]$StepName
    )

    Write-Step $StepName
    & $VenvPython (Join-Path $RepoRoot "pipeline\cli.py") @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "파이프라인 명령 실패: $($Arguments -join ' ')"
    }
}

function Resolve-PathStrict {
    param([string]$RelativePath)

    $fullPath = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path $fullPath)) {
        throw "필수 파일이 없습니다: $RelativePath"
    }
    return $fullPath
}

function Ensure-SectionsArtifact {
    param(
        [string]$RawInput,
        [string]$SectionsOutput
    )

    if (Test-Path $SectionsOutput) {
        return
    }

    Invoke-Pipeline -StepName "정제 결과 생성" -Arguments @(
        "preprocess",
        "--input", $RawInput,
        "--output", $SectionsOutput
    )
}

function Ensure-ChunkArtifacts {
    param(
        [string]$SectionsInput,
        [string]$ChunksOutput,
        [string]$GlossaryOutput,
        [string]$RelationsOutput,
        [string]$VisualizationOutput,
        [switch]$ForceRebuild
    )

    if (-not $ForceRebuild -and (Test-Path $ChunksOutput) -and (Test-Path $GlossaryOutput)) {
        return
    }

    $outputDir = Split-Path -Parent $ChunksOutput
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

    Invoke-Pipeline -StepName "Chunk 및 glossary 산출물 생성" -Arguments @(
        "chunk-docs",
        "--input", $SectionsInput,
        "--output-chunks", $ChunksOutput,
        "--output-glossary", $GlossaryOutput,
        "--output-relations-sql", $RelationsOutput,
        "--output-visualization", $VisualizationOutput
    )
}

function Resolve-CorpusArtifacts {
    param([string]$Mode)

    $canonicalRaw = Join-Path $RepoRoot "data\raw\spring_docs_raw.jsonl"
    $canonicalSections = Join-Path $RepoRoot "data\processed\spring_docs_sections.jsonl"
    $canonicalChunks = Join-Path $RepoRoot "data\processed\chunks.jsonl"
    $canonicalGlossary = Join-Path $RepoRoot "data\processed\glossary_terms.jsonl"

    $sampleRaw = Resolve-PathStrict "data\raw\spring_docs_raw_dry_run.jsonl"
    $sampleSections = Resolve-PathStrict "data\processed\spring_docs_sections_dry_run.jsonl"
    $sampleOutputDir = Join-Path $RepoRoot "data\processed\bootstrap-sample"

    if ($Mode -eq "none") {
        return $null
    }

    $selectedMode = $Mode
    if ($Mode -eq "auto") {
        if (Test-Path $canonicalRaw) {
            $selectedMode = "canonical"
        }
        else {
            $selectedMode = "sample"
        }
    }

    if ($selectedMode -eq "canonical") {
        if (-not (Test-Path $canonicalRaw)) {
            throw "canonical corpus를 요청했지만 data/raw/spring_docs_raw.jsonl 이 없습니다."
        }

        Ensure-SectionsArtifact -RawInput $canonicalRaw -SectionsOutput $canonicalSections
        Ensure-ChunkArtifacts `
            -SectionsInput $canonicalSections `
            -ChunksOutput $canonicalChunks `
            -GlossaryOutput $canonicalGlossary `
            -RelationsOutput (Join-Path $RepoRoot "data\processed\chunk_neighbors.sql") `
            -VisualizationOutput (Join-Path $RepoRoot "data\processed\chunking_visualization.md")

        return @{
            Mode = "canonical"
            RawInput = $canonicalRaw
            SectionsInput = $canonicalSections
            ChunksInput = $canonicalChunks
            GlossaryInput = $canonicalGlossary
        }
    }

    if ($selectedMode -eq "sample") {
        New-Item -ItemType Directory -Force -Path $sampleOutputDir | Out-Null
        $sampleChunks = Join-Path $sampleOutputDir "chunks.jsonl"
        $sampleGlossary = Join-Path $sampleOutputDir "glossary_terms.jsonl"
        $sampleRelations = Join-Path $sampleOutputDir "chunk_neighbors.sql"
        $sampleVisualization = Join-Path $sampleOutputDir "chunking_visualization.md"

        Ensure-ChunkArtifacts `
            -SectionsInput $sampleSections `
            -ChunksOutput $sampleChunks `
            -GlossaryOutput $sampleGlossary `
            -RelationsOutput $sampleRelations `
            -VisualizationOutput $sampleVisualization `
            -ForceRebuild

        return @{
            Mode = "sample"
            RawInput = $sampleRaw
            SectionsInput = $sampleSections
            ChunksInput = $sampleChunks
            GlossaryInput = $sampleGlossary
        }
    }

    throw "지원하지 않는 CorpusMode 입니다: $Mode"
}

function Import-Corpus {
    param([hashtable]$Artifacts)

    if (-not $Artifacts) {
        return
    }

    Write-Step "Corpus import 실행 ($($Artifacts.Mode))"
    Invoke-Pipeline -StepName "PostgreSQL import" -Arguments @(
        "import-corpus",
        "--db-host", $env:POSTGRES_HOST,
        "--db-port", $env:POSTGRES_PORT,
        "--db-name", $env:POSTGRES_DB,
        "--db-user", $env:POSTGRES_USER,
        "--db-password", $env:POSTGRES_PASSWORD,
        "--raw-input", $Artifacts.RawInput,
        "--sections-input", $Artifacts.SectionsInput,
        "--chunks-input", $Artifacts.ChunksInput,
        "--glossary-input", $Artifacts.GlossaryInput,
        "--created-by", "bootstrap-local.ps1",
        "--trigger-type", "manual"
    )
}

Set-Location $RepoRoot

Write-Step "사전 점검"
Require-Command "docker"
Require-Command "java"
Require-Command "python"
Assert-PythonVersion -PythonCommand "python"
docker version | Out-Null

if (-not (Test-Path $EnvFile)) {
    Write-Step ".env 생성"
    Copy-Item $EnvExample $EnvFile
}

Load-EnvFile -Path $EnvFile

if (-not $env:POSTGRES_HOST) {
    $env:POSTGRES_HOST = "localhost"
}
if (-not $env:SERVER_PORT) {
    $env:SERVER_PORT = if ($env:BACKEND_PORT) { $env:BACKEND_PORT } else { "8080" }
}
if (-not $env:QUERY_FORGE_REPO_ROOT) {
    $env:QUERY_FORGE_REPO_ROOT = $RepoRoot
}

New-Item -ItemType Directory -Force -Path $SessionLogDir | Out-Null
Ensure-Venv
$env:QUERY_FORGE_PYTHON = $VenvPython

Write-Step "PostgreSQL 컨테이너 실행"
docker compose up -d postgres | Out-Null
Wait-ForPostgres -TimeoutSeconds $PostgresTimeoutSeconds

$healthUrl = Get-HealthUrl
$backendProcess = Start-BackendIfNeeded -HealthUrl $healthUrl
Wait-ForBackend -Url $healthUrl -TimeoutSeconds $BackendTimeoutSeconds

$artifacts = Resolve-CorpusArtifacts -Mode $CorpusMode
Import-Corpus -Artifacts $artifacts

Write-Step "구동 완료"
Write-Host "관리자 UI: http://localhost:$($env:SERVER_PORT)/admin"
Write-Host "헬스 체크: $healthUrl"
Write-Host "세션 로그: $SessionLogDir"
if ($backendProcess) {
    Write-Host "백엔드 PID: $($backendProcess.Id)"
}
if ($artifacts) {
    Write-Host "적재 모드: $($artifacts.Mode)"
}

if ($OpenBrowser) {
    Start-Process "http://localhost:$($env:SERVER_PORT)/admin"
}
