[CmdletBinding()]
param(
    [string]$WslDistribution = 'Debian',
    [string]$ProxyUrl = $env:HTTPS_PROXY,
    [string]$OpenConnectRepository = 'https://gitlab.com/openconnect/openconnect.git',
    [string]$OpenConnectCommit = 'f17fe20d337b400b476a73326de642a9f63b59c8',
    [string]$NdkRelease = 'r28c',
    [string]$NdkRevision = '28.2.13676358',
    [string]$CacheRoot = (Join-Path ([System.IO.Path]::GetTempPath()) 'TunnelKeeper-OpenConnect-Native')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$nativeRoot = Join-Path $repositoryRoot 'native'
$sourceRoot = Join-Path $nativeRoot 'src'
$openConnectRoot = Join-Path $sourceRoot 'openconnect'
$sourceLockPath = Join-Path $nativeRoot 'source-lock.json'
$repositoryMetadataPath = Join-Path $CacheRoot 'repository2-3.xml'

function Invoke-Wsl {
    param(
        [Parameter(Mandatory)]
        [string[]]$ArgumentList,
        [switch]$CaptureOutput
    )

    if ($CaptureOutput) {
        $output = & wsl.exe --distribution $WslDistribution --exec @ArgumentList
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            throw "WSL command failed with exit code ${exitCode}: $($ArgumentList -join ' ')"
        }
        return ($output | Out-String).Trim()
    }

    & wsl.exe --distribution $WslDistribution --exec @ArgumentList
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "WSL command failed with exit code ${exitCode}: $($ArgumentList -join ' ')"
    }
}

function ConvertTo-WslPath {
    param(
        [Parameter(Mandatory)]
        [string]$LiteralPath
    )

    $resolvedPath = [System.IO.Path]::GetFullPath($LiteralPath)
    return Invoke-Wsl -CaptureOutput -ArgumentList @('wslpath', '-a', $resolvedPath)
}

function Get-HashAlgorithm {
    param(
        [Parameter(Mandatory)]
        [string]$ExpectedHash,
        [string]$DeclaredType
    )

    if ($DeclaredType -match 'sha-?256' -or $ExpectedHash.Length -eq 64) {
        return 'SHA256'
    }
    if ($DeclaredType -match 'sha-?1' -or $ExpectedHash.Length -eq 40) {
        return 'SHA1'
    }
    throw "Unsupported repository checksum type '$DeclaredType' with length $($ExpectedHash.Length)."
}

function Set-WslProxyEnvironment {
    $script:previousProxyEnvironment = @{}
    foreach ($name in 'HTTPS_PROXY', 'HTTP_PROXY', 'ALL_PROXY', 'WSLENV') {
        $script:previousProxyEnvironment[$name] = [System.Environment]::GetEnvironmentVariable($name, 'Process')
    }

    $env:HTTPS_PROXY = $ProxyUrl
    $env:HTTP_PROXY = $ProxyUrl
    $env:ALL_PROXY = $ProxyUrl
    $env:WSLENV = 'HTTPS_PROXY/u:HTTP_PROXY/u:ALL_PROXY/u'
}

function Restore-WslProxyEnvironment {
    foreach ($entry in $script:previousProxyEnvironment.GetEnumerator()) {
        [System.Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
}

New-Item -ItemType Directory -Path $nativeRoot, $sourceRoot, $CacheRoot -Force | Out-Null

Set-WslProxyEnvironment
try {
    $openConnectWslPath = ConvertTo-WslPath -LiteralPath $openConnectRoot
    $openConnectGitPath = Join-Path $openConnectRoot '.git'

    if (-not (Test-Path -LiteralPath $openConnectGitPath)) {
        if ((Test-Path -LiteralPath $openConnectRoot) -and
            (Get-ChildItem -LiteralPath $openConnectRoot -Force | Select-Object -First 1)) {
            throw "Source directory exists but is not a Git checkout: $openConnectRoot"
        }
        New-Item -ItemType Directory -Path $openConnectRoot -Force | Out-Null
        Invoke-Wsl -ArgumentList @('git', '-C', $openConnectWslPath, 'init')
        Invoke-Wsl -ArgumentList @('git', '-C', $openConnectWslPath, 'remote', 'add', 'origin', $OpenConnectRepository)
    }

    $remoteUrl = Invoke-Wsl -CaptureOutput -ArgumentList @('git', '-C', $openConnectWslPath, 'remote', 'get-url', 'origin')
    if ($remoteUrl -ne $OpenConnectRepository) {
        throw "Unexpected OpenConnect origin '$remoteUrl'; expected '$OpenConnectRepository'."
    }

    Invoke-Wsl -ArgumentList @(
        'git', '-C', $openConnectWslPath, 'fetch', '--depth=1', '--no-tags', 'origin', $OpenConnectCommit
    )
    Invoke-Wsl -ArgumentList @('git', '-C', $openConnectWslPath, 'checkout', '--detach', $OpenConnectCommit)

    $actualCommit = Invoke-Wsl -CaptureOutput -ArgumentList @('git', '-C', $openConnectWslPath, 'rev-parse', 'HEAD')
    if ($actualCommit -ne $OpenConnectCommit) {
        throw "OpenConnect checkout mismatch: expected $OpenConnectCommit, got $actualCommit."
    }
    $sourceStatus = Invoke-Wsl -CaptureOutput -ArgumentList @('git', '-C', $openConnectWslPath, 'status', '--porcelain')
    if ($sourceStatus) {
        throw "OpenConnect source checkout is not clean:`n$sourceStatus"
    }

    $metadataWslPath = ConvertTo-WslPath -LiteralPath $repositoryMetadataPath
    Invoke-Wsl -ArgumentList @(
        'curl', '--fail', '--location', '--retry', '3', '--connect-timeout', '30',
        '--output', $metadataWslPath,
        'https://dl.google.com/android/repository/repository2-3.xml'
    )

    [xml]$repositoryMetadata = Get-Content -LiteralPath $repositoryMetadataPath -Raw
    $packagePath = "ndk;$NdkRevision"
    $ndkPackage = $repositoryMetadata.SelectSingleNode(
        "//*[local-name()='remotePackage' and @path='$packagePath']"
    )
    if ($null -eq $ndkPackage) {
        throw "NDK package '$packagePath' is absent from Google's repository metadata."
    }

    $linuxArchive = $null
    foreach ($archive in $ndkPackage.SelectNodes(".//*[local-name()='archive']")) {
        $hostOsNode = $archive.SelectSingleNode("./*[local-name()='host-os']")
        if ($null -ne $hostOsNode -and $hostOsNode.InnerText -eq 'linux') {
            $linuxArchive = $archive
            break
        }
    }
    if ($null -eq $linuxArchive) {
        throw "Linux archive is absent for NDK '$packagePath'."
    }

    $completeNode = $linuxArchive.SelectSingleNode("./*[local-name()='complete']")
    $urlNode = $completeNode.SelectSingleNode("./*[local-name()='url']")
    $checksumNode = $completeNode.SelectSingleNode("./*[local-name()='checksum']")
    if ($null -eq $urlNode -or $null -eq $checksumNode) {
        throw "NDK metadata is missing the archive URL or checksum."
    }

    $ndkFileName = $urlNode.InnerText.Trim()
    $expectedNdkHash = $checksumNode.InnerText.Trim().ToLowerInvariant()
    $declaredChecksumType = [string]$checksumNode.GetAttribute('type')
    $hashAlgorithm = Get-HashAlgorithm -ExpectedHash $expectedNdkHash -DeclaredType $declaredChecksumType
    $ndkUrl = "https://dl.google.com/android/repository/$ndkFileName"
    $ndkArchivePath = Join-Path $CacheRoot $ndkFileName

    $downloadRequired = $true
    if (Test-Path -LiteralPath $ndkArchivePath) {
        $cachedHash = (Get-FileHash -LiteralPath $ndkArchivePath -Algorithm $hashAlgorithm).Hash.ToLowerInvariant()
        $downloadRequired = $cachedHash -ne $expectedNdkHash
    }
    if ($downloadRequired) {
        $ndkArchiveWslPath = ConvertTo-WslPath -LiteralPath $ndkArchivePath
        Invoke-Wsl -ArgumentList @(
            'curl', '--fail', '--location', '--retry', '3', '--connect-timeout', '30',
            '--output', $ndkArchiveWslPath, $ndkUrl
        )
    }

    $actualNdkHash = (Get-FileHash -LiteralPath $ndkArchivePath -Algorithm $hashAlgorithm).Hash.ToLowerInvariant()
    if ($actualNdkHash -ne $expectedNdkHash) {
        throw "NDK archive checksum mismatch: expected $expectedNdkHash, got $actualNdkHash."
    }

    $sourceLock = [ordered]@{
        schemaVersion = 1
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        openConnect = [ordered]@{
            repository = $OpenConnectRepository
            commit = $actualCommit
            sourcePath = 'native/src/openconnect'
            license = 'LGPL-2.1-only'
            licenseFile = 'native/src/openconnect/COPYING.LGPL'
        }
        androidNdk = [ordered]@{
            release = $NdkRelease
            revision = $NdkRevision
            packagePath = $packagePath
            archiveUrl = $ndkUrl
            checksumAlgorithm = $hashAlgorithm
            expectedChecksum = $expectedNdkHash
            actualChecksum = $actualNdkHash
            sizeBytes = (Get-Item -LiteralPath $ndkArchivePath).Length
        }
        googleRepositoryMetadata = [ordered]@{
            url = 'https://dl.google.com/android/repository/repository2-3.xml'
            sha256 = (Get-FileHash -LiteralPath $repositoryMetadataPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    $sourceLock | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $sourceLockPath -Encoding utf8NoBOM

    Write-Output "OpenConnect source: $openConnectRoot"
    Write-Output "OpenConnect commit: $actualCommit"
    Write-Output "Android NDK archive: $ndkArchivePath"
    Write-Output "Android NDK ${hashAlgorithm}: $actualNdkHash"
    Write-Output "Source lock: $sourceLockPath"
}
finally {
    Restore-WslProxyEnvironment
}
