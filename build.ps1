[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Variant = 'Debug'
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$toolchainRoot = Join-Path (Split-Path $projectRoot -Parent) 'work\android-installer-toolchain'
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and (Test-Path -LiteralPath (Join-Path $toolchainRoot 'jdk'))) {
    $env:JAVA_HOME = (Get-ChildItem -LiteralPath (Join-Path $toolchainRoot 'jdk') -Directory | Select-Object -First 1).FullName
}
if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME) -and (Test-Path -LiteralPath (Join-Path $toolchainRoot 'android-sdk'))) {
    $env:ANDROID_HOME = Join-Path $toolchainRoot 'android-sdk'
}
$gradle = Join-Path $projectRoot 'gradlew.bat'
$task = if ($Variant -eq 'Release') { 'assembleRelease' } else { 'assembleDebug' }

& $gradle -p $projectRoot --no-daemon testDebugUnitTest lintDebug $task
if ($LASTEXITCODE -ne 0) {
    throw "Gradle failed with exit code $LASTEXITCODE"
}

$apkName = if ($Variant -eq 'Release') { 'app-release.apk' } else { 'app-debug.apk' }
$apk = Join-Path $projectRoot "app\build\outputs\apk\$($Variant.ToLowerInvariant())\$apkName"
Get-Item -LiteralPath $apk
