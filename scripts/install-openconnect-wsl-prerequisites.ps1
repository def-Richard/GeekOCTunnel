param(
    [Parameter(Mandatory = $false)]
    [string]$ProxyUrl
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$wsl = 'C:\Windows\System32\wsl.exe'
$aptPrefix = @('--exec')
if ($ProxyUrl) {
    $aptPrefix += @(
        '/usr/bin/env',
        "http_proxy=$ProxyUrl",
        "https_proxy=$ProxyUrl"
    )
}
$aptPrefix += '/usr/bin/apt-get'

& $wsl @aptPrefix 'update'
if ($LASTEXITCODE -ne 0) {
    throw "apt-get update failed with exit code $LASTEXITCODE"
}

$packages = @(
    'build-essential',
    'autoconf',
    'automake',
    'libtool',
    'pkg-config',
    'gettext',
    'autopoint',
    'gperf',
    'unzip',
    'openjdk-21-jdk-headless',
    'xz-utils',
    'ca-certificates'
)
& $wsl @aptPrefix 'install' '-y' @packages
if ($LASTEXITCODE -ne 0) {
    throw "apt-get install failed with exit code $LASTEXITCODE"
}
