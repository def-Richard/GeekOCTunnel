[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ToolchainRoot,

    [Parameter(Mandatory = $false)]
    [string]$AndroidSdk = (Join-Path $ToolchainRoot 'android-sdk'),

    [Parameter(Mandatory = $false)]
    [ValidateRange(1, 100)]
    [int]$KeepVersions = 10,

    [ValidateSet('arm64-v8a', 'x86_64')]
    [string]$Abi = 'arm64-v8a',

    [switch]$BuildDeviceTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-VersionedApkRecords {
    param([Parameter(Mandatory = $true)][string]$DirectoryPath)

    if (-not (Test-Path -LiteralPath $DirectoryPath -PathType Container)) {
        return @()
    }

    return @(
        Get-ChildItem -LiteralPath $DirectoryPath -File |
            ForEach-Object {
                $nameMatch = [regex]::Match(
                    $_.Name,
                    '^GeekOCTunnel-v(?<VersionName>\d+\.\d+\.\d+)-(?<VersionCode>\d+)-(?:x86_64-)?debug\.apk$'
                )
                if ($nameMatch.Success) {
                    [pscustomobject]@{
                        File = $_
                        VersionCode = [int]$nameMatch.Groups['VersionCode'].Value
                        VersionName = $nameMatch.Groups['VersionName'].Value
                    }
                }
            }
    )
}

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$versionFile = Join-Path $repositoryRoot 'version.properties'
$releaseDirectory = Join-Path $repositoryRoot 'releases\apk'
$outputDirectory = Join-Path $repositoryRoot 'app\build\outputs\apk\debug'
$metadataPath = Join-Path $outputDirectory 'output-metadata.json'
$lockPath = Join-Path $repositoryRoot '.publish-apk.lock'
$javaHome = Join-Path $ToolchainRoot 'jdk-17'
$java = Join-Path $javaHome 'bin\java.exe'
$gradleLauncher = Join-Path $ToolchainRoot 'gradle-8.9\lib\gradle-launcher-8.9.jar'
$nativeVerificationScript = Join-Path $PSScriptRoot 'Verify-ApkNativeLibraries.ps1'

foreach ($requiredPath in @($versionFile, $java, $gradleLauncher, $AndroidSdk, $nativeVerificationScript)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Missing publish requirement: $requiredPath"
    }
}

New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null

$publishLockStream = $null
try {
    try {
        $publishLockStream = [System.IO.File]::Open(
            $lockPath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
    }
    catch {
        throw 'Another APK publish is already running for this workspace.'
    }

    $versionData = Get-Content -LiteralPath $versionFile -Raw | ConvertFrom-StringData
    if (-not $versionData.ContainsKey('VERSION_CODE') -or -not $versionData.ContainsKey('VERSION_NAME')) {
        throw 'version.properties must contain VERSION_CODE and VERSION_NAME.'
    }

    $storedVersionCode = [int]$versionData['VERSION_CODE']
    $storedVersionName = [string]$versionData['VERSION_NAME']
    $baselineVersionCode = $storedVersionCode
    $baselineVersionName = $storedVersionName
    $highestArchive = Get-VersionedApkRecords -DirectoryPath $releaseDirectory |
        Sort-Object VersionCode -Descending |
        Select-Object -First 1
    if ($null -ne $highestArchive -and $highestArchive.VersionCode -gt $baselineVersionCode) {
        $baselineVersionCode = $highestArchive.VersionCode
        $baselineVersionName = $highestArchive.VersionName
    }

    $semanticVersion = [regex]::Match(
        $baselineVersionName,
        '^(?<Major>0|[1-9]\d*)\.(?<Minor>0|[1-9]\d*)\.(?<Patch>0|[1-9]\d*)$'
    )
    if (-not $semanticVersion.Success) {
        throw "VERSION_NAME must use major.minor.patch format: $baselineVersionName"
    }

    $nextVersionCode = $baselineVersionCode + 1
    $nextVersionName = '{0}.{1}.{2}' -f @(
        $semanticVersion.Groups['Major'].Value,
        $semanticVersion.Groups['Minor'].Value,
        ([int]$semanticVersion.Groups['Patch'].Value + 1)
    )
    $abiSuffix = if ($Abi -eq 'x86_64') { '-x86_64' } else { '' }
    $expectedFileName = "GeekOCTunnel-v$nextVersionName-$nextVersionCode$abiSuffix-debug.apk"

    $env:JAVA_HOME = $javaHome
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk

    $gradleArguments = @(
        '-classpath'
        $gradleLauncher
        'org.gradle.launcher.GradleMain'
        "-PappVersionCode=$nextVersionCode"
        "-PappVersionName=$nextVersionName"
        "-PtestAbi=$Abi"
        'testDebugUnitTest'
        'assembleDebug'
    )
    if ($BuildDeviceTests) { $gradleArguments += 'assembleDebugAndroidTest' }
    & $java @gradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Android publish build failed with exit code $LASTEXITCODE"
    }

    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
        throw "APK output metadata does not exist: $metadataPath"
    }
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    $outputElement = @($metadata.elements) |
        Where-Object {
            [int]$_.versionCode -eq $nextVersionCode -and
            [string]$_.versionName -eq $nextVersionName
        } |
        Select-Object -First 1
    if ($null -eq $outputElement) {
        throw "APK metadata does not contain version $nextVersionName ($nextVersionCode)."
    }
    if ([string]$outputElement.outputFile -ne $expectedFileName) {
        throw "Unexpected APK file name: $($outputElement.outputFile); expected $expectedFileName"
    }

    $builtApk = Join-Path $outputDirectory ([string]$outputElement.outputFile)
    if (-not (Test-Path -LiteralPath $builtApk -PathType Leaf)) {
        throw "Built APK does not exist: $builtApk"
    }

    $buildToolsDirectory = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1
    if ($null -eq $buildToolsDirectory) {
        throw "No Android build-tools installation was found under $AndroidSdk."
    }
    $aapt2 = Join-Path $buildToolsDirectory.FullName 'aapt2.exe'
    if (-not (Test-Path -LiteralPath $aapt2 -PathType Leaf)) {
        throw "aapt2.exe was not found: $aapt2"
    }
    $badging = & $aapt2 'dump' 'badging' $builtApk
    if ($LASTEXITCODE -ne 0) {
        throw "APK metadata inspection failed with exit code $LASTEXITCODE"
    }
    $packageLine = [string]($badging | Where-Object { $_ -like 'package:*' } | Select-Object -First 1)
    if ($packageLine -notmatch "name='com\.richard\.tunnelkeeper'") {
        throw "APK applicationId check failed: $packageLine"
    }
    if ($packageLine -notmatch "versionCode='$nextVersionCode'") {
        throw "APK versionCode check failed: $packageLine"
    }
    if ($packageLine -notmatch "versionName='$([regex]::Escape($nextVersionName))'") {
        throw "APK versionName check failed: $packageLine"
    }

    & $nativeVerificationScript -ApkPath $builtApk -AndroidSdkRoot $AndroidSdk -ExpectedAbis @($Abi)

    $archivePath = Join-Path $releaseDirectory $expectedFileName
    if (Test-Path -LiteralPath $archivePath) {
        throw "Refusing to overwrite an existing release APK: $archivePath"
    }
    Copy-Item -LiteralPath $builtApk -Destination $archivePath
    $builtHash = (Get-FileHash -LiteralPath $builtApk -Algorithm SHA256).Hash
    $archiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
    if ($builtHash -ne $archiveHash) {
        throw "Archived APK checksum mismatch: $archivePath"
    }

    $temporaryVersionFile = Join-Path $repositoryRoot ("version.properties.{0}.tmp" -f [guid]::NewGuid())
    $backupVersionFile = Join-Path $repositoryRoot ("version.properties.{0}.bak" -f [guid]::NewGuid())
    try {
        $versionContent = "VERSION_CODE=$nextVersionCode`r`nVERSION_NAME=$nextVersionName`r`n"
        [System.IO.File]::WriteAllText(
            $temporaryVersionFile,
            $versionContent,
            [System.Text.UTF8Encoding]::new($false)
        )
        [System.IO.File]::Replace($temporaryVersionFile, $versionFile, $backupVersionFile, $true)
    }
    finally {
        if (Test-Path -LiteralPath $temporaryVersionFile) {
            Remove-Item -LiteralPath $temporaryVersionFile -Force
        }
        if (Test-Path -LiteralPath $backupVersionFile) {
            Remove-Item -LiteralPath $backupVersionFile -Force
        }
    }

    $versionedArchives = Get-VersionedApkRecords -DirectoryPath $releaseDirectory |
        Sort-Object VersionCode -Descending
    @($versionedArchives | Select-Object -Skip $KeepVersions) | ForEach-Object {
        Remove-Item -LiteralPath $_.File.FullName -Force
    }
    $retainedVersions = @(Get-VersionedApkRecords -DirectoryPath $releaseDirectory).Count

    [pscustomobject]@{
        VersionName = $nextVersionName
        VersionCode = $nextVersionCode
        ApkPath = $archivePath
        SizeBytes = (Get-Item -LiteralPath $archivePath).Length
        SHA256 = $archiveHash
        RetainedVersions = $retainedVersions
    }
}
finally {
    if ($null -ne $publishLockStream) {
        $publishLockStream.Dispose()
    }
}
