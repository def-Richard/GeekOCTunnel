param(
    [Parameter(Mandatory = $true)]
    [string]$ToolchainRoot,

    [Parameter(Mandatory = $false)]
    [string]$AndroidSdk = (Join-Path $ToolchainRoot 'android-sdk')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$publishScript = Join-Path $PSScriptRoot 'Publish-Apk.ps1'
if (-not (Test-Path -LiteralPath $publishScript -PathType Leaf)) {
    throw "Missing APK publish script: $publishScript"
}

& $publishScript -ToolchainRoot $ToolchainRoot -AndroidSdk $AndroidSdk
if ($LASTEXITCODE -ne 0) {
    throw "Android verification failed with exit code $LASTEXITCODE"
}
