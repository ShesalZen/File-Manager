param(
    [string]$ApkPath = "app/build/outputs/apk/release/app-arm64-v8a-release.apk",
    [string]$PackageName = "com.zenlabs.filemanager",
    [string]$Serial = "",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb is not available in PATH"
}
if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK not found: $ApkPath"
}

$adbArgs = @()
if ($Serial) { $adbArgs += @("-s", $Serial) }
function Invoke-Adb([string[]]$Arguments, [switch]$AllowFailure) {
    $output = & adb @adbArgs @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) {
        throw "adb $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)"
    }
    return $output
}

$devices = @(Invoke-Adb @("devices") | Where-Object { $_ -match "^[^\\s]+\s+device$" })
if ($devices.Count -eq 0) {
    throw "No authorized Android device is connected. Run 'adb devices -l' and authorize the device first."
}
if ($devices.Count -gt 1 -and -not $Serial) {
    throw "Multiple devices are connected; pass -Serial to select one."
}

$apk = (Resolve-Path -LiteralPath $ApkPath).Path
if (-not $SkipInstall) {
    Write-Host "Installing $apk"
    Invoke-Adb @("install", "-r", "-t", $apk) | Write-Host
} else {
    $installed = @(Invoke-Adb @("shell", "pm", "path", $PackageName) -AllowFailure)
    if (($installed -join " ") -notmatch "package:") {
        throw "$PackageName is not installed on the selected device"
    }
}

Invoke-Adb @("logcat", "-c")
Invoke-Adb @("shell", "am", "force-stop", $PackageName) | Out-Null
$launch = @(Invoke-Adb @("shell", "am", "start", "-W", "-n", "$PackageName/.screen.main.MainActivity"))
$launch | Write-Host
if (($launch -join " ") -notmatch "Status: ok") {
    throw "Main activity did not report a successful launch"
}

Start-Sleep -Seconds 3
$pid = (Invoke-Adb @("shell", "pidof", $PackageName) | Select-Object -First 1).Trim()
if (-not $pid) { throw "Application process is not running after launch" }
Write-Host "Launch OK (pid $pid)"

Invoke-Adb @("shell", "am", "force-stop", $PackageName) | Out-Null
Start-Sleep -Milliseconds 500
$restart = @(Invoke-Adb @("shell", "am", "start", "-W", "-n", "$PackageName/.screen.main.MainActivity"))
if (($restart -join " ") -notmatch "Status: ok") { throw "Restart did not report success" }
Start-Sleep -Seconds 3
$restartPid = (Invoke-Adb @("shell", "pidof", $PackageName) | Select-Object -First 1).Trim()
if (-not $restartPid) { throw "Application process is not running after restart" }
Write-Host "Restart OK (pid $restartPid)"

$packageInfo = Invoke-Adb @("shell", "dumpsys", "package", $PackageName)
$nativeDir = (($packageInfo | Select-String "nativeLibraryDir=" | Select-Object -First 1).Line -replace ".*nativeLibraryDir=", "").Trim()
Write-Host "Native library directory: $nativeDir"
if ($nativeDir) {
    Invoke-Adb @("shell", "ls", "-l", "$nativeDir") | Write-Host
}

$crashes = @(Invoke-Adb @("logcat", "-d", "-v", "brief") | Select-String "FATAL EXCEPTION|AndroidRuntime.*$PackageName")
if ($crashes.Count -gt 0) {
    $crashes | Write-Host
    throw "Crash signatures were found in logcat"
}

Write-Host "ADB smoke test passed: launch, restart, process liveness, native directory, and crash scan."
Write-Host "Interactive feature coverage remains in docs/PHYSICAL_DEVICE_SMOKE_TEST.md."
