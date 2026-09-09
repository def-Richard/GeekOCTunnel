Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$lock = Get-Content -LiteralPath (Join-Path $root 'native/source-lock.json') -Raw | ConvertFrom-Json
$manifest = Get-Content -LiteralPath (Join-Path $root 'native/artifacts/build-manifest.json') -Raw | ConvertFrom-Json
if ($lock.openConnect.sourcePath -ne 'native/src/openconnect') { throw 'Source path must be repository-relative.' }
if ($lock.androidNdk.PSObject.Properties.Name -contains 'archivePath') { throw 'Do not publish machine-specific cache paths.' }
if ($lock.googleRepositoryMetadata.PSObject.Properties.Name -contains 'downloadedPath') { throw 'Do not publish machine-specific download paths.' }
$archiveName = [IO.Path]::GetFileName(([uri]$lock.androidNdk.archiveUrl).AbsolutePath)
if ($archiveName -ne "android-ndk-$($lock.androidNdk.release)-linux.zip") { throw 'NDK cache filename does not match release.' }
foreach ($artifact in $manifest.artifacts) {
    if ([IO.Path]::IsPathRooted($artifact.path) -or $artifact.path.Contains('..')) { throw 'Artifact path must stay repository-relative.' }
    if ($artifact.kind -ne 'jniLib') { continue }
    $path = Join-Path $root $artifact.path
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $artifact.sha256) { throw "Native library checksum mismatch: $($artifact.abi)" }
}
foreach ($script in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1') {
    $parseTokens = $null
    $parseErrors = $null
    $null = [Management.Automation.Language.Parser]::ParseFile($script.FullName, [ref]$parseTokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw "PowerShell syntax error in $($script.Name): $parseErrors" }
}
Write-Output 'PASS: portable native metadata, binary checksums and PowerShell syntax.'
