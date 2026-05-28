param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

function Test-JavaHome {
    param([string]$JavaHomePath)

    if ([string]::IsNullOrWhiteSpace($JavaHomePath)) {
        return $false
    }

    return Test-Path (Join-Path $JavaHomePath "bin\\java.exe")
}

function Find-JavaHome {
    $candidates = @()

    if (Test-JavaHome $env:JAVA_HOME) {
        return $env:JAVA_HOME
    }

    $roots = @(
        "C:\\Program Files\\Eclipse Adoptium",
        "C:\\Program Files\\Java",
        "C:\\Program Files\\Microsoft"
    )

    foreach ($root in $roots) {
        if (-not (Test-Path $root)) {
            continue
        }

        $candidates += Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-JavaHome $_.FullName } |
            Sort-Object Name -Descending |
            Select-Object -ExpandProperty FullName
    }

    return $candidates | Select-Object -First 1
}

$javaHome = Find-JavaHome

if (-not (Test-JavaHome $javaHome)) {
    throw "Unable to find a JDK. Set JAVA_HOME to a JDK 17+ install."
}

$env:JAVA_HOME = $javaHome

if (-not $env:Path.Split(';') -contains (Join-Path $javaHome "bin")) {
    $env:Path = "$(Join-Path $javaHome 'bin');$env:Path"
}

if (-not $env:GRADLE_USER_HOME) {
    $defaultGradleHome = Join-Path $env:USERPROFILE ".gradle"
    if (Test-Path $defaultGradleHome) {
        $env:GRADLE_USER_HOME = $defaultGradleHome
    } else {
        $fallbackHome = Join-Path $repoRoot ".gradle-user-home"
        New-Item -ItemType Directory -Force -Path $fallbackHome | Out-Null
        $env:GRADLE_USER_HOME = $fallbackHome
    }
}

& (Join-Path $repoRoot "gradlew.bat") @GradleArgs
exit $LASTEXITCODE
