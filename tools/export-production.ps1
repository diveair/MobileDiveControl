param(
    [Parameter(Mandatory = $true)]
    [string]$Destination
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$destinationPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination)

if (Test-Path -LiteralPath $destinationPath) {
    throw "Choose a new destination folder: $destinationPath"
}

$productionPaths = @(
    "app/src/main"
    "app/build.gradle.kts"
    "core/src/main"
    "core/build.gradle.kts"
    "gradle/libs.versions.toml"
    "gradle/wrapper/gradle-wrapper.jar"
    "gradle/wrapper/gradle-wrapper.properties"
    "gradlew"
    "gradlew.bat"
    "build.gradle.kts"
    "settings.gradle.kts"
    "gradle.properties"
    "compose_stability.conf"
    ".gitignore"
)

$sources = foreach ($relativePath in $productionPaths) {
    $source = Get-Item -LiteralPath (Join-Path $repoRoot $relativePath) -Force
    if ($source.PSIsContainer) {
        $sourcePrefix = $source.FullName + [IO.Path]::DirectorySeparatorChar
        if ($destinationPath.StartsWith($sourcePrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "The destination must be outside $($source.FullName)."
        }
    }
    [pscustomobject]@{
        Source = $source.FullName
        Target = Join-Path $destinationPath $relativePath
    }
}

New-Item -ItemType Directory -Path $destinationPath | Out-Null
foreach ($entry in $sources) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $entry.Target) | Out-Null
    Copy-Item -LiteralPath $entry.Source -Destination $entry.Target -Recurse -Force
}

Write-Output "Production source exported to $destinationPath"
