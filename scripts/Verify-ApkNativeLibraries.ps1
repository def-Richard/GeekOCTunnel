[CmdletBinding()]
param(
    [string]$ApkPath,
    [string]$AndroidSdkRoot = $env:ANDROID_SDK_ROOT,
    [string[]]$ExpectedAbis = @('arm64-v8a')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $ApkPath) {
    $outputDirectory = Join-Path $repositoryRoot 'app\build\outputs\apk\debug'
    $metadataPath = Join-Path $outputDirectory 'output-metadata.json'
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
        throw "APK output metadata does not exist: $metadataPath"
    }
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    $outputElement = @($metadata.elements) | Select-Object -First 1
    if ($null -eq $outputElement -or -not $outputElement.outputFile) {
        throw "APK output metadata contains no output file: $metadataPath"
    }
    $ApkPath = Join-Path $outputDirectory ([string]$outputElement.outputFile)
}
$ApkPath = [System.IO.Path]::GetFullPath($ApkPath)
$manifestPath = Join-Path $repositoryRoot 'native\artifacts\build-manifest.json'

if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "APK does not exist: $ApkPath"
}
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Native build manifest does not exist: $manifestPath"
}
if (-not $AndroidSdkRoot) {
    throw 'AndroidSdkRoot is required when ANDROID_SDK_ROOT is not set.'
}

$zipalignCandidates = Get-ChildItem -LiteralPath (Join-Path $AndroidSdkRoot 'build-tools') -Directory |
    ForEach-Object {
        $candidate = Join-Path $_.FullName 'zipalign.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            [pscustomobject]@{
                Version = [version]$_.Name
                Path = $candidate
            }
        }
    } |
    Sort-Object Version -Descending
$zipalign = $zipalignCandidates | Select-Object -First 1
if ($null -eq $zipalign) {
    throw "zipalign.exe was not found under $AndroidSdkRoot."
}

$zipalignOutput = & $zipalign.Path -c -P 16 -v 4 $ApkPath
if ($LASTEXITCODE -ne 0) {
    throw "APK zip alignment check failed with exit code $LASTEXITCODE.`n$($zipalignOutput -join "`n")"
}
Write-Output "zipalign=$($zipalignOutput[-1])"

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$expectedAbis = @($ExpectedAbis | Sort-Object -Unique)
if ($expectedAbis.Count -eq 0) {
    throw 'At least one expected APK ABI is required.'
}
$expectedLibraries = @(
    $manifest.artifacts |
        Where-Object { $_.kind -eq 'jniLib' -and [string]$_.abi -in $expectedAbis }
)
if ($expectedLibraries.Count -ne $expectedAbis.Count) {
    throw "Native build manifest does not contain exactly one jniLib for each expected ABI: $($expectedAbis -join ', ')"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
try {
    $actualAbis = @(
        $archive.Entries |
            Where-Object { $_.FullName -match '^lib/([^/]+)/[^/]+\.so$' } |
            ForEach-Object { ([regex]::Match($_.FullName, '^lib/([^/]+)/')).Groups[1].Value } |
            Sort-Object -Unique
    )
    $abiDifferences = @(Compare-Object -ReferenceObject $expectedAbis -DifferenceObject $actualAbis)
    if ($abiDifferences.Count -ne 0) {
        throw "APK ABI set mismatch. Expected: $($expectedAbis -join ', '); actual: $($actualAbis -join ', ')"
    }

    $records = foreach ($artifact in $expectedLibraries) {
        $entryName = "lib/$($artifact.abi)/libopenconnect.so"
        $entry = $archive.GetEntry($entryName)
        if ($null -eq $entry) {
            throw "APK is missing native library entry: $entryName"
        }
        if ($entry.Length -ne [long]$artifact.sizeBytes) {
            throw "APK native library size mismatch for ${entryName}: expected $($artifact.sizeBytes), got $($entry.Length)."
        }

        $stream = $entry.Open()
        $sha256 = [System.Security.Cryptography.SHA256]::Create()
        try {
            $actualHash = [Convert]::ToHexString($sha256.ComputeHash($stream)).ToLowerInvariant()
        }
        finally {
            $sha256.Dispose()
            $stream.Dispose()
        }
        if ($actualHash -ne [string]$artifact.sha256) {
            throw "APK native library hash mismatch for ${entryName}: expected $($artifact.sha256), got $actualHash."
        }
        if ([long]$artifact.elf.minimumLoadAlignmentBytes -lt 16384) {
            throw "APK native library is not 16 KB page aligned: $entryName"
        }

        [pscustomobject]@{
            Entry = $entryName
            SizeBytes = $entry.Length
            CompressedBytes = $entry.CompressedLength
            Sha256 = $actualHash
            ElfLoadAlignmentBytes = $artifact.elf.minimumLoadAlignmentBytes
        }
    }

    $records | Format-List
}
finally {
    $archive.Dispose()
}

Write-Output "APK SHA256=$((Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant())"
