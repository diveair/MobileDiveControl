[CmdletBinding()]
param(
    [string]$Device,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [int]$DwellMs = 180,
    [int]$PreviewTimeoutMs = 1500,
    [int]$RebindTimeoutMs = 8000,
    [int]$MaxResponsivePreviewMs = 500,
    [int]$TimeoutMinutes = 30,
    [switch]$NoRestore,
    [string[]]$Modes,
    [string]$OutputRoot
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $PSScriptRoot 'artifacts\camera-stress'
}
$packageName = 'com.mobiledivecontrol'
$componentName = 'com.mobiledivecontrol/.MainActivity'
$adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$javaHomePath = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$apkPath = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'

if (-not (Test-Path -LiteralPath $adbPath)) {
    throw "ADB was not found at $adbPath"
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$AllowFailure
    )
    $output = & $script:adbPath -s $script:Device @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb $($Arguments -join ' ') failed ($exitCode): $($output -join [Environment]::NewLine)"
    }
    return $output
}

if ([string]::IsNullOrWhiteSpace($Device)) {
    $connected = @(& $adbPath devices |
        Select-String -Pattern '^([^\s]+)\s+device$' |
        ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($connected.Count -ne 1) {
        throw "Expected exactly one connected Android device; found $($connected.Count). Use -Device SERIAL."
    }
    $Device = $connected[0]
}

if (-not $SkipBuild) {
    $previousJavaHome = $env:JAVA_HOME
    try {
        $env:JAVA_HOME = $javaHomePath
        & (Join-Path $PSScriptRoot 'gradlew.bat') :app:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw 'Debug APK build failed.' }
    } finally {
        $env:JAVA_HOME = $previousJavaHome
    }
}

if (-not (Test-Path -LiteralPath $apkPath)) {
    throw "Debug APK does not exist: $apkPath"
}

if (-not $SkipInstall) {
    Invoke-Adb -Arguments @('install', '-r', '-d', $apkPath) | Write-Host
}

# These are engineering-run prerequisites, not mocked permission state. Unsupported grants are
# recorded and ignored so the same script remains useful across Android API levels.
$runtimePermissions = @(
    'android.permission.BLUETOOTH_SCAN',
    'android.permission.BLUETOOTH_CONNECT',
    'android.permission.CAMERA',
    'android.permission.RECORD_AUDIO',
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION'
)
foreach ($permission in $runtimePermissions) {
    Invoke-Adb -Arguments @('shell', 'pm', 'grant', $packageName, $permission) -AllowFailure | Out-Null
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outputDirectory = Join-Path $OutputRoot "host-$timestamp"
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$logcatFile = Join-Path $outputDirectory 'logcat.txt'
$logcatErrorFile = Join-Path $outputDirectory 'logcat-stderr.txt'
$samplesFile = Join-Path $outputDirectory 'host-samples.csv'
$exitInfoFile = Join-Path $outputDirectory 'process-exit-info.txt'
$gfxInfoFile = Join-Path $outputDirectory 'gfxinfo-framestats.txt'
$thermalFile = Join-Path $outputDirectory 'thermal-final.txt'
$memInfoFile = Join-Path $outputDirectory 'meminfo-final.txt'

'epoch_ms,pid,battery_temperature_c,thermal_status,total_pss_kb,cpu_line,responsive' |
    Set-Content -LiteralPath $samplesFile -Encoding utf8

Invoke-Adb -Arguments @('logcat', '-c') | Out-Null
Invoke-Adb -Arguments @('shell', 'dumpsys', 'gfxinfo', $packageName, 'reset') -AllowFailure | Out-Null
Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $packageName) | Out-Null

$logcatArguments = @('-s', $Device, 'logcat', '-v', 'threadtime')
$logcatProcess = Start-Process -FilePath $adbPath -ArgumentList $logcatArguments `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput $logcatFile `
    -RedirectStandardError $logcatErrorFile

$restoreValue = if ($NoRestore) { 'false' } else { 'true' }
$launchArguments = @(
    'shell', 'am', 'start', '-W', '-n', $componentName,
    '--ez', 'camera_stress_test', 'true',
    '--el', 'camera_stress_dwell_ms', $DwellMs.ToString(),
    '--el', 'camera_stress_preview_timeout_ms', $PreviewTimeoutMs.ToString(),
    '--el', 'camera_stress_rebind_timeout_ms', $RebindTimeoutMs.ToString(),
    '--el', 'camera_stress_max_responsive_preview_ms', $MaxResponsivePreviewMs.ToString(),
    '--ez', 'camera_stress_restore', $restoreValue
)
$requestedModes = @($Modes | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($requestedModes.Count -gt 0) {
    $launchArguments += @('--es', 'camera_stress_modes', ($requestedModes -join ','))
}
$launchOutput = Invoke-Adb -Arguments $launchArguments
$launchOutput | Set-Content -LiteralPath (Join-Path $outputDirectory 'launch.txt') -Encoding utf8

$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$started = $false
$completed = $false
$processExited = $false
$lastPid = ''

try {
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        $pidText = (Invoke-Adb -Arguments @('shell', 'pidof', $packageName) -AllowFailure | Out-String).Trim()
        if (-not [string]::IsNullOrWhiteSpace($pidText)) {
            $started = $true
            $lastPid = ($pidText -split '\s+')[0]
        } elseif ($started) {
            $processExited = $true
        }

        $batteryText = Invoke-Adb -Arguments @('shell', 'dumpsys', 'battery') -AllowFailure | Out-String
        $temperature = ''
        if ($batteryText -match '(?m)^\s*temperature:\s*(-?\d+)') {
            $temperature = ([double]$Matches[1] / 10.0).ToString('0.0', [Globalization.CultureInfo]::InvariantCulture)
        }

        $thermalText = Invoke-Adb -Arguments @('shell', 'dumpsys', 'thermalservice') -AllowFailure | Out-String
        $thermalStatus = ''
        if ($thermalText -match '(?im)thermal status:\s*(\d+)') {
            $thermalStatus = $Matches[1]
        } elseif ($thermalText -match '(?im)mStatus\s*=\s*(\d+)') {
            $thermalStatus = $Matches[1]
        }

        $totalPss = ''
        $cpuLine = ''
        if (-not [string]::IsNullOrWhiteSpace($lastPid)) {
            $memText = Invoke-Adb -Arguments @('shell', 'dumpsys', 'meminfo', $packageName) -AllowFailure | Out-String
            if ($memText -match '(?im)^\s*TOTAL PSS:\s*(\d+)') {
                $totalPss = $Matches[1]
            } elseif ($memText -match '(?im)^\s*TOTAL\s+(\d+)') {
                $totalPss = $Matches[1]
            }
            $cpuText = Invoke-Adb -Arguments @('shell', 'dumpsys', 'cpuinfo') -AllowFailure
            $cpuLine = ($cpuText | Where-Object { $_ -match [regex]::Escape($packageName) } | Select-Object -First 1)
        }

        $epochMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $safeCpu = ('{0}' -f $cpuLine).Replace('"', '""').Trim()
        $responsive = if ($processExited) { 'false' } else { 'true' }
        "$epochMs,$lastPid,$temperature,$thermalStatus,$totalPss,`"$safeCpu`",$responsive" |
            Add-Content -LiteralPath $samplesFile -Encoding utf8

        if (Test-Path -LiteralPath $logcatFile) {
            $tail = Get-Content -LiteralPath $logcatFile -Tail 160 -ErrorAction SilentlyContinue | Out-String
            if ($tail -match 'CAMERA_STRESS_COMPLETE') {
                $completed = $true
                break
            }
            if ($tail -match 'CAMERA_STRESS_START') {
                $started = $true
            }
            # A foreground service can restart the package quickly enough that pidof never has
            # an empty sample. The fatal marker is authoritative even when a new PID already
            # exists, so stop immediately and preserve the original crash tail.
            if ($tail -match 'FATAL EXCEPTION|ANR in com\.mobiledivecontrol|CAMERA_STRESS_UNCAUGHT') {
                $processExited = $true
                break
            }
        }
        # Start-Process redirection can buffer its file on Windows. Query the device's ring buffer
        # directly as the authoritative completion/crash signal so a completed run never idles to
        # the host timeout while its report is already waiting on-device.
        $stressLog = Invoke-Adb -Arguments @(
            'shell', 'logcat', '-d', '-v', 'brief', 'CameraStress:I', '*:S'
        ) -AllowFailure | Out-String
        if ($stressLog -match 'CAMERA_STRESS_COMPLETE') {
            $completed = $true
            break
        }
        if ($stressLog -match 'CAMERA_STRESS_UNCAUGHT') {
            $processExited = $true
            break
        }
    }
} finally {
    if (-not $logcatProcess.HasExited) {
        Stop-Process -Id $logcatProcess.Id -Force
        $logcatProcess.WaitForExit()
    }
}

Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'exit-info', $packageName) -AllowFailure |
    Set-Content -LiteralPath $exitInfoFile -Encoding utf8
Invoke-Adb -Arguments @('shell', 'dumpsys', 'gfxinfo', $packageName, 'framestats') -AllowFailure |
    Set-Content -LiteralPath $gfxInfoFile -Encoding utf8
Invoke-Adb -Arguments @('shell', 'dumpsys', 'thermalservice') -AllowFailure |
    Set-Content -LiteralPath $thermalFile -Encoding utf8
Invoke-Adb -Arguments @('shell', 'dumpsys', 'meminfo', $packageName) -AllowFailure |
    Set-Content -LiteralPath $memInfoFile -Encoding utf8

$remoteBase = "/sdcard/Android/data/$packageName/files/camera-stress"
$remoteRuns = @(Invoke-Adb -Arguments @('shell', 'ls', '-1', $remoteBase) -AllowFailure |
    Where-Object { $_ -match '^run-\d{8}-\d{6}$' } |
    Sort-Object)
if ($remoteRuns.Count -gt 0) {
    $latestRun = $remoteRuns[-1]
    $deviceReportDirectory = Join-Path $outputDirectory 'device-report'
    New-Item -ItemType Directory -Path $deviceReportDirectory -Force | Out-Null
    Invoke-Adb -Arguments @('pull', "$remoteBase/$latestRun", $deviceReportDirectory) -AllowFailure | Out-Null
}

$summaryPath = Get-ChildItem -LiteralPath $outputDirectory -Filter 'summary.json' -Recurse -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime |
    Select-Object -Last 1
$failed = -not $completed -or $processExited
if ($summaryPath) {
    $summary = Get-Content -LiteralPath $summaryPath.FullName -Raw | ConvertFrom-Json
    $failed = $failed -or
        $summary.completion -ne 'complete' -or
        [int]$summary.responsiveness.previewTimeouts -gt 0 -or
        [int]$summary.responsiveness.previewLagSpikes -gt 0 -or
        [int]$summary.responsiveness.cameraRuntimeFailures -gt 0 -or
        $summary.failures.Count -gt 0
    Write-Host "Stress test report: $($summaryPath.FullName)"
    Write-Host "Modes: $($summary.coverage.modesVisited)/$($summary.coverage.modesExpected)"
    Write-Host "Settings: $($summary.coverage.settingsVisited)"
    Write-Host "Preview timeouts: $($summary.responsiveness.previewTimeouts)"
    Write-Host "Preview lag spikes (>$MaxResponsivePreviewMs ms): $($summary.responsiveness.previewLagSpikes)"
    Write-Host "Max preview latency: $($summary.responsiveness.maxPreviewLatencyMs) ms"
    Write-Host "Max camera bind: $($summary.responsiveness.maxCameraBindMs) ms"
    Write-Host "Battery temperature at finish: $($summary.resourcesAtFinish.batteryTemperatureC) C"
} else {
    Write-Warning 'No on-device summary was recovered. Inspect logcat.txt and process-exit-info.txt.'
}

Write-Host "Complete artifact directory: $outputDirectory"
if ($failed) { exit 2 }
exit 0
