# Project-local REA launcher. Installation is intentionally outside Android dependencies.
# Usage: .\tools\rea.ps1 inspect-android-package <apk> --json
$ErrorActionPreference = 'Stop'
$reaRoot = Join-Path (Split-Path $PSScriptRoot -Parent) '.codex-tmp/rea-tool'
$launcher = Join-Path $reaRoot 'node_modules/rea-agents/scripts/rea.mjs'
$jadxJar = Join-Path $reaRoot 'jadx-headless-mcp-0.7.1-all.jar'
if (-not (Test-Path -LiteralPath $launcher)) {
    throw 'REA is missing. Install rea-agents@6.4.0 under .codex-tmp/rea-tool first.'
}
if (-not (Test-Path -LiteralPath $jadxJar)) {
    throw 'The JADX 0.7.1 engine is missing from .codex-tmp/rea-tool.'
}
$previousJar = $env:REA_JADX_MCP_JAR
try {
    $env:REA_JADX_MCP_JAR = $jadxJar
    & node $launcher @args
    $reaExitCode = $LASTEXITCODE
} finally {
    $env:REA_JADX_MCP_JAR = $previousJar
}
exit $reaExitCode
