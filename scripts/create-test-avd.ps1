param(
    [Parameter(Mandatory = $true)]
    [string]$ToolchainRoot,

    [Parameter(Mandatory = $false)]
    [string]$AvdName = 'TunnelKeeperApi35'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$androidSdk = Join-Path $ToolchainRoot 'android-sdk'
$avdManager = Join-Path $androidSdk 'cmdline-tools\latest\bin\avdmanager.bat'
$emulator = Join-Path $androidSdk 'emulator\emulator.exe'
foreach ($requiredPath in @($avdManager, $emulator)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Missing Android tool: $requiredPath"
    }
}

$env:ANDROID_HOME = $androidSdk
$env:ANDROID_SDK_ROOT = $androidSdk
$existingAvds = @(& $emulator '-list-avds')
if ($LASTEXITCODE -ne 0) {
    throw "emulator -list-avds failed with exit code $LASTEXITCODE"
}
if ($existingAvds -contains $AvdName) {
    throw "AVD already exists: $AvdName"
}

'no' | & $avdManager 'create' 'avd' `
    '--name' $AvdName `
    '--package' 'system-images;android-35;google_apis;x86_64' `
    '--device' 'pixel_6'
if ($LASTEXITCODE -ne 0) {
    throw "avdmanager create avd failed with exit code $LASTEXITCODE"
}

$createdAvds = @(& $emulator '-list-avds')
if ($LASTEXITCODE -ne 0 -or $createdAvds -notcontains $AvdName) {
    throw "AVD creation could not be verified: $AvdName"
}

[PSCustomObject]@{
    Name = $AvdName
    SystemImage = 'system-images;android-35;google_apis;x86_64'
    Device = 'pixel_6'
}
