[CmdletBinding()]
param(
    [string]$WslDistribution = 'Debian',
    [string]$ProxyUrl = $env:HTTPS_PROXY,
    [string]$CacheRoot = (Join-Path ([System.IO.Path]::GetTempPath()) 'TunnelKeeper-OpenConnect-Native'),
    [ValidateSet('arm64', 'x86_64')]
    [string[]]$Architectures = @('arm64'),
    [ValidateRange(1, 64)]
    [int]$Jobs = [Math]::Min([Environment]::ProcessorCount, 12),
    [ValidateRange(23, 35)]
    [int]$ApiLevel = 26,
    [string]$ReuseWslBuildRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$nativeRoot = Join-Path $repositoryRoot 'native'
$sourceLockPath = Join-Path $nativeRoot 'source-lock.json'
$artifactRoot = Join-Path $nativeRoot 'artifacts'
$logRoot = Join-Path $artifactRoot 'logs'
$licenseRoot = Join-Path $artifactRoot 'licenses'
$jniLibsRoot = Join-Path $repositoryRoot 'app\src\main\jniLibs'
$manifestPath = Join-Path $artifactRoot 'build-manifest.json'
$componentPath = Join-Path $artifactRoot 'third-party-components.json'

$architectureMap = @{
    arm64 = [ordered]@{
        abi = 'arm64-v8a'
        triplet = 'aarch64-linux-android'
        expectedMachine = 'AArch64'
    }
    x86_64 = [ordered]@{
        abi = 'x86_64'
        triplet = 'x86_64-linux-android'
        expectedMachine = 'Advanced Micro Devices X86-64'
    }
}

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

function Invoke-WslLogged {
    param(
        [Parameter(Mandatory)]
        [string[]]$ArgumentList,
        [Parameter(Mandatory)]
        [string]$LogPath
    )

    & wsl.exe --distribution $WslDistribution --exec @ArgumentList 2>&1 |
        Tee-Object -FilePath $LogPath |
        Out-Null
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "WSL command failed with exit code ${exitCode}: $($ArgumentList -join ' '). Log: $LogPath"
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

function Copy-WslFile {
    param(
        [Parameter(Mandatory)]
        [string]$WslSource,
        [Parameter(Mandatory)]
        [string]$WindowsDestination
    )

    $destinationDirectory = Split-Path -Parent $WindowsDestination
    New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
    $destinationWslPath = ConvertTo-WslPath -LiteralPath $WindowsDestination
    Invoke-Wsl -ArgumentList @('cp', '--force', $WslSource, $destinationWslPath)
}

function Get-ElfMetadata {
    param(
        [Parameter(Mandatory)]
        [string]$WindowsPath,
        [Parameter(Mandatory)]
        [string]$ExpectedMachine
    )

    $wslPath = ConvertTo-WslPath -LiteralPath $WindowsPath
    $headerOutput = Invoke-Wsl -CaptureOutput -ArgumentList @('readelf', '--file-header', '--wide', $wslPath)
    $machineMatch = [regex]::Match($headerOutput, '(?m)^\s*Machine:\s*(.+?)\s*$')
    if (-not $machineMatch.Success) {
        throw "Unable to read ELF machine for $WindowsPath."
    }
    $machine = $machineMatch.Groups[1].Value.Trim()
    if ($machine -ne $ExpectedMachine) {
        throw "Unexpected ELF machine for ${WindowsPath}: '$machine', expected '$ExpectedMachine'."
    }

    $programHeaders = Invoke-Wsl -CaptureOutput -ArgumentList @('readelf', '--program-headers', '--wide', $wslPath)
    $loadAlignments = @()
    foreach ($line in $programHeaders -split "`n") {
        $loadMatch = [regex]::Match($line, '^\s*LOAD\s+.*\s+(0x[0-9a-fA-F]+)\s*$')
        if ($loadMatch.Success) {
            $loadAlignments += [Convert]::ToInt64($loadMatch.Groups[1].Value.Substring(2), 16)
        }
    }
    if ($loadAlignments.Count -eq 0) {
        throw "No ELF LOAD program headers found in $WindowsPath."
    }
    $minimumAlignment = ($loadAlignments | Measure-Object -Minimum).Minimum
    if ($minimumAlignment -lt 16384) {
        throw "ELF LOAD alignment is below 16 KB for ${WindowsPath}: $minimumAlignment bytes."
    }

    $dynamicOutput = Invoke-Wsl -CaptureOutput -ArgumentList @('readelf', '--dynamic', '--wide', $wslPath)
    $sonameMatch = [regex]::Match($dynamicOutput, '\(SONAME\).*\[(.+?)\]')
    $needed = @()
    foreach ($match in [regex]::Matches($dynamicOutput, '\(NEEDED\).*\[(.+?)\]')) {
        $needed += $match.Groups[1].Value
    }

    return [ordered]@{
        machine = $machine
        minimumLoadAlignmentBytes = [long]$minimumAlignment
        loadAlignmentsBytes = @($loadAlignments | Sort-Object -Unique)
        soname = if ($sonameMatch.Success) { $sonameMatch.Groups[1].Value } else { $null }
        neededLibraries = $needed
    }
}

function Copy-LicenseFiles {
    param(
        [Parameter(Mandatory)]
        [string]$WslPackageRoot,
        [Parameter(Mandatory)]
        [string]$PackageName,
        [ValidateRange(1, 8)]
        [int]$MaxDepth = 3
    )

    $fileList = Invoke-Wsl -CaptureOutput -ArgumentList @(
        'find', $WslPackageRoot, '-maxdepth', [string]$MaxDepth, '-type', 'f', '-print'
    )
    if (-not $fileList) {
        return
    }

    foreach ($sourceFileLine in $fileList -split "`n") {
        $sourceFile = $sourceFileLine.TrimEnd("`r")
        $leafName = [System.IO.Path]::GetFileName($sourceFile)
        if ($leafName -notmatch '^(COPYING|LICENSE|COPYRIGHT|NOTICE)(\..*)?$') {
            continue
        }
        $relativePath = $sourceFile.Substring($WslPackageRoot.Length).TrimStart('/')
        $relativeWindowsPath = $relativePath.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
        $destinationPath = Join-Path (Join-Path $licenseRoot $PackageName) $relativeWindowsPath
        Copy-WslFile -WslSource $sourceFile -WindowsDestination $destinationPath
    }
}

if (-not (Test-Path -LiteralPath $sourceLockPath)) {
    throw "Missing source lock. Run scripts\Sync-OpenConnectNativeSources.ps1 first."
}

$sourceLock = Get-Content -LiteralPath $sourceLockPath -Raw | ConvertFrom-Json
$openConnectRoot = Join-Path $repositoryRoot 'native/src/openconnect'
$expectedCommit = [string]$sourceLock.openConnect.commit
$ndkArchivePath = Join-Path $CacheRoot ([System.IO.Path]::GetFileName(([uri]$sourceLock.androidNdk.archiveUrl).AbsolutePath))
$ndkRelease = [string]$sourceLock.androidNdk.release
$ndkRevision = [string]$sourceLock.androidNdk.revision
$ndkHashAlgorithm = [string]$sourceLock.androidNdk.checksumAlgorithm
$expectedNdkHash = [string]$sourceLock.androidNdk.expectedChecksum

if (-not (Test-Path -LiteralPath $openConnectRoot)) {
    throw "OpenConnect source directory does not exist: $openConnectRoot"
}
if (-not (Test-Path -LiteralPath $ndkArchivePath)) {
    throw "Android NDK archive does not exist: $ndkArchivePath"
}

$actualNdkHash = (Get-FileHash -LiteralPath $ndkArchivePath -Algorithm $ndkHashAlgorithm).Hash.ToLowerInvariant()
if ($actualNdkHash -ne $expectedNdkHash) {
    throw "Android NDK archive checksum mismatch: expected $expectedNdkHash, got $actualNdkHash."
}

New-Item -ItemType Directory -Path $artifactRoot, $logRoot, $licenseRoot, $jniLibsRoot -Force | Out-Null

Set-WslProxyEnvironment
$buildStartedUtc = [DateTime]::UtcNow
if ($ReuseWslBuildRoot) {
    $wslBuildRoot = $ReuseWslBuildRoot.TrimEnd('/')
    if (-not $wslBuildRoot.StartsWith('/tmp/tunnelkeeper-openconnect-', [StringComparison]::Ordinal)) {
        throw "ReuseWslBuildRoot must be an explicit TunnelKeeper build directory under /tmp."
    }
} else {
    $buildId = 'tunnelkeeper-openconnect-{0}-{1}-{2}' -f $expectedCommit.Substring(0, 12), $ndkRelease, $buildStartedUtc.ToString('yyyyMMddTHHmmssZ')
    $wslBuildRoot = "/tmp/$buildId"
}
$wslOpenConnectRoot = "$wslBuildRoot/openconnect"
$wslNdkRoot = "$wslBuildRoot/android-ndk-$ndkRelease"
$artifactRecords = @()
$buildCommands = @()

try {
    $openConnectWslSource = ConvertTo-WslPath -LiteralPath $openConnectRoot
    $ndkArchiveWslPath = ConvertTo-WslPath -LiteralPath $ndkArchivePath

    $actualCommit = Invoke-Wsl -CaptureOutput -ArgumentList @('git', '-C', $openConnectWslSource, 'rev-parse', 'HEAD')
    if ($actualCommit -ne $expectedCommit) {
        throw "OpenConnect source commit mismatch: expected $expectedCommit, got $actualCommit."
    }
    $sourceStatus = Invoke-Wsl -CaptureOutput -ArgumentList @('git', '-C', $openConnectWslSource, 'status', '--porcelain')
    if ($sourceStatus) {
        throw "OpenConnect source checkout is not clean:`n$sourceStatus"
    }

    if (-not $ReuseWslBuildRoot) {
        Invoke-Wsl -ArgumentList @('mkdir', '--parents', $wslBuildRoot)
        Invoke-Wsl -ArgumentList @('unzip', '-q', $ndkArchiveWslPath, '-d', $wslBuildRoot)
        Invoke-Wsl -ArgumentList @('cp', '--archive', $openConnectWslSource, $wslOpenConnectRoot)
        Invoke-Wsl -ArgumentList @(
            'chmod', '+x',
            "$wslOpenConnectRoot/autogen.sh",
            "$wslOpenConnectRoot/android/fetch.sh",
            "$wslOpenConnectRoot/android/install_symlink.sh"
        )
    }

    Invoke-Wsl -ArgumentList @('test', '-f', "$wslNdkRoot/source.properties")
    Invoke-Wsl -ArgumentList @('test', '-f', "$wslOpenConnectRoot/android/Makefile")
    $ndkProperties = Invoke-Wsl -CaptureOutput -ArgumentList @('cat', "$wslNdkRoot/source.properties")
    if ($ndkProperties -notmatch [regex]::Escape("Pkg.Revision = $ndkRevision")) {
        throw "Extracted NDK revision is not $ndkRevision.`n$ndkProperties"
    }

    foreach ($architecture in $Architectures) {
        $architectureInfo = $architectureMap[$architecture]
        $abi = [string]$architectureInfo.abi
        $triplet = [string]$architectureInfo.triplet
        $logSuffix = if ($ReuseWslBuildRoot) { '-resume' } else { '' }
        $logPath = Join-Path $logRoot "build-$abi$logSuffix.log"
        $makeArguments = @(
            'make', '-C', "$wslOpenConnectRoot/android",
            "ARCH=$architecture",
            "NDK=$wslNdkRoot",
            "API_LEVEL=$ApiLevel",
            'EXTRA_LDFLAGS=-Wl,-z,max-page-size=16384',
            "-j$Jobs"
        )
        $buildCommands += $makeArguments -join ' '
        Invoke-WslLogged -ArgumentList $makeArguments -LogPath $logPath

        $installedLibraryDirectory = "$wslOpenConnectRoot/android/$triplet/out/lib"
        $installedFiles = Invoke-Wsl -CaptureOutput -ArgumentList @(
            'find', $installedLibraryDirectory, '-maxdepth', '1', '-type', 'f',
            '-name', 'libopenconnect.so*', '-print'
        )
        $installedCandidates = @($installedFiles -split "`n" | Where-Object { $_ })
        if ($installedCandidates.Count -ne 1) {
            throw "Expected one installed libopenconnect shared object for $abi, found $($installedCandidates.Count): $installedFiles"
        }

        $unstrippedDirectory = "$wslOpenConnectRoot/android/$triplet/openconnect/.libs"
        $unstrippedFiles = Invoke-Wsl -CaptureOutput -ArgumentList @(
            'find', $unstrippedDirectory, '-maxdepth', '1', '-type', 'f',
            '-name', 'libopenconnect.so*', '-print'
        )
        $unstrippedCandidates = @($unstrippedFiles -split "`n" | Where-Object { $_ })
        if ($unstrippedCandidates.Count -ne 1) {
            throw "Expected one unstripped libopenconnect shared object for $abi, found $($unstrippedCandidates.Count): $unstrippedFiles"
        }

        $jniDestination = Join-Path (Join-Path $jniLibsRoot $abi) 'libopenconnect.so'
        $symbolDestination = Join-Path (Join-Path (Join-Path $artifactRoot $abi) 'symbols') 'libopenconnect.so'
        Copy-WslFile -WslSource $installedCandidates[0] -WindowsDestination $jniDestination
        Copy-WslFile -WslSource $unstrippedCandidates[0] -WindowsDestination $symbolDestination

        foreach ($artifact in @(
            [ordered]@{ kind = 'jniLib'; path = $jniDestination },
            [ordered]@{ kind = 'unstrippedSymbols'; path = $symbolDestination }
        )) {
            $elfMetadata = Get-ElfMetadata -WindowsPath $artifact.path -ExpectedMachine $architectureInfo.expectedMachine
            $artifactRecords += [ordered]@{
                abi = $abi
                architecture = $architecture
                kind = $artifact.kind
                path = [System.IO.Path]::GetRelativePath($repositoryRoot, $artifact.path).Replace('\', '/')
                basename = [System.IO.Path]::GetFileName($artifact.path)
                sizeBytes = (Get-Item -LiteralPath $artifact.path).Length
                sha256 = (Get-FileHash -LiteralPath $artifact.path -Algorithm SHA256).Hash.ToLowerInvariant()
                elf = $elfMetadata
            }
        }
    }

    Copy-WslFile -WslSource "$wslOpenConnectRoot/COPYING.LGPL" -WindowsDestination (Join-Path $licenseRoot 'openconnect\COPYING.LGPL')
    Copy-LicenseFiles -WslPackageRoot "$wslOpenConnectRoot/android/sources/libxml2-2.13.4" -PackageName 'libxml2'
    Copy-LicenseFiles -WslPackageRoot "$wslOpenConnectRoot/android/sources/gmp-6.3.0" -PackageName 'gmp'
    Copy-LicenseFiles -WslPackageRoot "$wslOpenConnectRoot/android/sources/nettle-3.10" -PackageName 'nettle'
    Copy-LicenseFiles -WslPackageRoot "$wslOpenConnectRoot/android/sources/gnutls-3.7.11" -PackageName 'gnutls' -MaxDepth 5
    Copy-LicenseFiles -WslPackageRoot "$wslOpenConnectRoot/android/sources/stoken-0.93" -PackageName 'stoken'
    $firstTriplet = [string]$architectureMap[$Architectures[0]].triplet
    Copy-LicenseFiles -WslPackageRoot "$wslOpenConnectRoot/android/$firstTriplet/lz4-1.10.0" -PackageName 'lz4'

    $thirdPartyComponents = @(
        [ordered]@{ name = 'OpenConnect'; version = '9.12+f17fe20'; license = 'LGPL-2.1-only'; linked = 'shared JNI library' },
        [ordered]@{ name = 'libxml2'; version = '2.13.4'; license = 'MIT'; linked = 'static into libopenconnect.so' },
        [ordered]@{ name = 'GMP'; version = '6.3.0'; license = 'LGPL-3.0-or-later OR GPL-2.0-or-later'; linked = 'static into libopenconnect.so' },
        [ordered]@{ name = 'Nettle'; version = '3.10'; license = 'LGPL-3.0-or-later OR GPL-2.0-or-later'; linked = 'static into libopenconnect.so' },
        [ordered]@{ name = 'GnuTLS'; version = '3.7.11'; license = 'LGPL-2.1-or-later'; linked = 'static into libopenconnect.so' },
        [ordered]@{ name = 'stoken'; version = '0.93'; license = 'LGPL-2.1-or-later'; linked = 'static into libopenconnect.so' },
        [ordered]@{ name = 'LZ4'; version = '1.10.0'; license = 'BSD-2-Clause'; linked = 'static into libopenconnect.so' }
    )
    $thirdPartyComponents | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $componentPath -Encoding utf8NoBOM

    $buildManifest = [ordered]@{
        schemaVersion = 1
        buildStartedUtc = $buildStartedUtc.ToString('o')
        buildCompletedUtc = [DateTime]::UtcNow.ToString('o')
        wslDistribution = $WslDistribution
        wslBuildRoot = $wslBuildRoot
        openConnect = [ordered]@{
            repository = [string]$sourceLock.openConnect.repository
            commit = $actualCommit
            version = '9.12'
            apiVersion = '5.9'
            officialAndroidMakefileSha256 = (Get-FileHash -LiteralPath (Join-Path $openConnectRoot 'android\Makefile') -Algorithm SHA256).Hash.ToLowerInvariant()
            officialJniSha256 = (Get-FileHash -LiteralPath (Join-Path $openConnectRoot 'jni.c') -Algorithm SHA256).Hash.ToLowerInvariant()
            officialJavaBindingSha256 = (Get-FileHash -LiteralPath (Join-Path $openConnectRoot 'java\src\org\infradead\libopenconnect\LibOpenConnect.java') -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        androidNdk = [ordered]@{
            release = $ndkRelease
            revision = $ndkRevision
            archiveSha1 = $actualNdkHash
        }
        apiLevel = $ApiLevel
        architectures = $Architectures
        jobs = $Jobs
        linkerPageSizeFlag = '-Wl,-z,max-page-size=16384'
        commands = $buildCommands
        artifacts = $artifactRecords
        licensesDirectory = 'native/artifacts/licenses'
        thirdPartyComponents = 'native/artifacts/third-party-components.json'
    }
    $buildManifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM

    Write-Output "Build manifest: $manifestPath"
    Write-Output "WSL build root: $wslBuildRoot"
    foreach ($artifact in $artifactRecords) {
        Write-Output ("{0} {1} {2} bytes SHA256={3} LOAD_ALIGN={4}" -f
            $artifact.abi,
            $artifact.kind,
            $artifact.sizeBytes,
            $artifact.sha256,
            $artifact.elf.minimumLoadAlignmentBytes)
    }
}
finally {
    Restore-WslProxyEnvironment
}
