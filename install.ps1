[CmdletBinding()]
param(
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$workspaceRoot = Split-Path $projectRoot -Parent
$adb = Join-Path $workspaceRoot 'work\android-installer-toolchain\android-sdk\platform-tools\adb.exe'
if (!(Test-Path -LiteralPath $adb)) {
    $adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' } else { 'adb' }
}

& (Join-Path $projectRoot 'build.ps1') -Variant Debug | Out-Host
$apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'

if ([string]::IsNullOrWhiteSpace($Serial)) {
    $devices = @(& $adb devices | Select-String '\sdevice$' | ForEach-Object { ($_ -split '\s+')[0] })
    if ($devices.Count -eq 0) {
        throw 'No authorized Android phone is connected.'
    }
    if ($devices.Count -gt 1) { throw 'Several devices are connected. Supply -Serial to choose one.' }
    $Serial = $devices[0]
}

& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "ADB install failed with exit code $LASTEXITCODE"
}

& $adb -s $Serial shell am start -n 'com.afahm.blefinder/.MainActivity'
