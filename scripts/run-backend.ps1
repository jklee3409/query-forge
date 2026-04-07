param()

$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$EnvFile = Join-Path $RepoRoot ".env"

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

if (Test-Path $EnvFile) {
    Load-EnvFile -Path $EnvFile
}

if (-not $env:QUERY_FORGE_REPO_ROOT) {
    $env:QUERY_FORGE_REPO_ROOT = $RepoRoot
}

Set-Location $RepoRoot
& (Join-Path $RepoRoot "backend\gradlew.bat") -p (Join-Path $RepoRoot "backend") bootRun
