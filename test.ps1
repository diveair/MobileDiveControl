param(
    [string]$Serial,
    [switch]$NoLaunch,
    [switch]$SkipBuild,
    [switch]$Fresh
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$applicationId = "com.mobiledivecontrol"
$launchActivity = "$applicationId/.MainActivity"
$apkPath = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"

function Resolve-AdbPath {
    $candidates = @()

    if ($env:ADB_PATH) {
        $candidates += $env:ADB_PATH
    }

    $localPropertiesPath = Join-Path $repoRoot "local.properties"
    if (Test-Path $localPropertiesPath) {
        $sdkDirLine = Get-Content $localPropertiesPath |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($sdkDirLine) {
            $sdkDir = ($sdkDirLine -replace '^sdk\.dir=', '').Replace('/', '\')
            $candidates += (Join-Path $sdkDir "platform-tools\adb.exe")
        }
    }

    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
    }

    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
    }

    $candidates += @(
        "C:\Users\PC\AppData\Local\Android\Sdk\platform-tools\adb.exe",
        "C:\Android\platform-tools\adb.exe"
    )

    foreach ($candidate in $candidates | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) {
        if (Test-Path $candidate) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw "Unable to find adb.exe. Set ADB_PATH or install Android platform-tools."
}

function Get-AdbDeviceLines {
    param([string]$AdbPath)

    $output = & $AdbPath devices
    if ($LASTEXITCODE -ne 0) {
        throw "adb devices failed."
    }

    return $output | Select-Object -Skip 1 | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
}

function Resolve-TargetSerial {
    param(
        [string]$AdbPath,
        [string]$RequestedSerial
    )

    $deviceLines = Get-AdbDeviceLines -AdbPath $AdbPath
    $parsedDevices = @()
    $nonReadyDevices = @()

    foreach ($line in $deviceLines) {
        $parts = $line -split '\s+'
        if ($parts.Length -lt 2) {
            continue
        }

        $device = [pscustomobject]@{
            Serial = $parts[0]
            State = $parts[1]
        }

        if ($device.State -eq "device") {
            $parsedDevices += $device
        } else {
            $nonReadyDevices += $device
        }
    }

    if ($RequestedSerial) {
        $matched = $parsedDevices | Where-Object { $_.Serial -eq $RequestedSerial } | Select-Object -First 1
        if (-not $matched) {
            $known = @($parsedDevices + $nonReadyDevices) | ForEach-Object { "$($_.Serial) [$($_.State)]" }
            $knownText = if ($known.Count -gt 0) { $known -join ", " } else { "none" }
            throw "Requested device '$RequestedSerial' is not connected and ready. Visible devices: $knownText"
        }
        return $matched.Serial
    }

    if ($parsedDevices.Count -eq 0) {
        $known = $nonReadyDevices | ForEach-Object { "$($_.Serial) [$($_.State)]" }
        $knownText = if ($known.Count -gt 0) { $known -join ", " } else { "none" }
        throw "No ready Android device detected over adb. Visible devices: $knownText"
    }

    if ($parsedDevices.Count -gt 1) {
        $known = $parsedDevices | ForEach-Object { $_.Serial }
        throw "Multiple ready devices detected: $($known -join ', '). Re-run with -Serial <device-id>."
    }

    return $parsedDevices[0].Serial
}

function Invoke-Checked {
    param(
        [string]$FilePath,
        [string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        $joined = $Arguments -join " "
        throw "Command failed: $FilePath $joined"
    }
}

$adbPath = Resolve-AdbPath
$targetSerial = Resolve-TargetSerial -AdbPath $adbPath -RequestedSerial $Serial

Write-Host "Using adb: $adbPath"
Write-Host "Target device: $targetSerial"

if (-not $SkipBuild) {
    Write-Host "Building debug APK..."
    Invoke-Checked -FilePath (Join-Path $repoRoot "run-gradle.ps1") -Arguments @(":app:assembleDebug")
}

if (-not (Test-Path $apkPath)) {
    throw "Debug APK not found at $apkPath"
}

if ($Fresh) {
    Write-Host "Uninstalling existing app..."
    & $adbPath -s $targetSerial uninstall $applicationId | Out-Host
}

Write-Host "Installing APK..."
Invoke-Checked -FilePath $adbPath -Arguments @("-s", $targetSerial, "install", "-r", $apkPath)

if (-not $NoLaunch) {
    Write-Host "Launching app..."
    Invoke-Checked -FilePath $adbPath -Arguments @("-s", $targetSerial, "shell", "am", "start", "-n", $launchActivity)
}

Write-Host "Done. App is installed on $targetSerial."
