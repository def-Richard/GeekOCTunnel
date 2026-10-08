Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-ApkInspection {
    param([string]$ToolPath, [string[]]$Arguments)

    $previousErrorPreference = $ErrorActionPreference
    try {
        # PowerShell 7.0/7.1 can treat redirected native stderr as an error record.
        # JVM startup notices on stderr are harmless when the tool exits zero.
        $ErrorActionPreference = 'Continue'
        $output = @(& $ToolPath @Arguments 2>&1 | ForEach-Object { [string]$_ })
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorPreference
    }
    if ($exitCode -ne 0) {
        throw "APK inspection failed with exit code ${exitCode}: $ToolPath`n$($output -join "`n")"
    }
    return $output
}

function Get-ApkUpgradeIdentity {
    param([string]$ApkPath, [string]$JavaPath, [string]$ApkSignerJar, [string]$Aapt2Path)

    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
        throw "APK does not exist: $ApkPath"
    }
    $signing = @(Invoke-ApkInspection -ToolPath $JavaPath -Arguments @(
        '-jar', $ApkSignerJar, 'verify', '--verbose', '--print-certs', $ApkPath
    ))
    $signerCounts = @($signing | Where-Object { $_ -match '^Number of signers:' })
    $certificates = @($signing | Where-Object { $_ -match '^Signer .* certificate SHA-256 digest:' })
    if ($signerCounts.Count -ne 1 -or $signerCounts[0] -notmatch '^Number of signers: 1\s*$' -or
        $certificates.Count -ne 1 -or
        $certificates[0] -notmatch '^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$') {
        throw "Expected exactly one APK signing certificate; missing, multiple or rotated signers are unsupported: $ApkPath"
    }
    $certificateSha256 = $Matches[1].ToLowerInvariant()

    $badging = @(Invoke-ApkInspection -ToolPath $Aapt2Path -Arguments @('dump', 'badging', $ApkPath))
    $packageLines = @($badging | Where-Object { $_ -match '^package:' })
    if ($packageLines.Count -ne 1) {
        throw "Expected exactly one APK package record: $ApkPath"
    }
    $nameMatches = [regex]::Matches($packageLines[0], "(?:^|\s)name='([^']*)'(?=\s|$)")
    if ($nameMatches.Count -ne 1 -or $nameMatches[0].Groups[1].Value -cne 'com.richard.tunnelkeeper') {
        throw "APK applicationId must be com.richard.tunnelkeeper: $ApkPath"
    }
    $versionMatches = [regex]::Matches($packageLines[0], "(?:^|\s)versionCode='([0-9]+)'(?=\s|$)")
    $versionCode = 0L
    if ($versionMatches.Count -ne 1 -or
        -not [long]::TryParse($versionMatches[0].Groups[1].Value, [ref]$versionCode) -or
        $versionCode -lt 1 -or $versionCode -gt [int]::MaxValue) {
        throw "APK has no valid versionCode: $ApkPath"
    }
    [pscustomobject]@{
        VersionCode = $versionCode
        CertificateSha256 = $certificateSha256
    }
}

function Assert-ApkUpgrade {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$ApkPath,
        [Parameter(Mandatory = $true)][string]$PreviousApk,
        [Parameter(Mandatory = $true)][string]$JavaPath,
        [Parameter(Mandatory = $true)][string]$ApkSignerJar,
        [Parameter(Mandatory = $true)][string]$Aapt2Path
    )

    $toolPaths = @{ JavaPath = $JavaPath; ApkSignerJar = $ApkSignerJar; Aapt2Path = $Aapt2Path }
    $previous = Get-ApkUpgradeIdentity -ApkPath $PreviousApk @toolPaths
    $candidate = Get-ApkUpgradeIdentity -ApkPath $ApkPath @toolPaths
    if ($candidate.CertificateSha256 -ne $previous.CertificateSha256) {
        throw 'APK signing certificate differs from PreviousApk. Use the existing signing key; do not uninstall the old app or replace its key.'
    }
    if ($candidate.VersionCode -le $previous.VersionCode) {
        throw "APK versionCode must increase: previous=$($previous.VersionCode), candidate=$($candidate.VersionCode)."
    }
    [pscustomobject]@{
        PreviousVersionCode = $previous.VersionCode
        VersionCode = $candidate.VersionCode
        SigningCertificateSha256 = $candidate.CertificateSha256
    }
}

Export-ModuleMember -Function Assert-ApkUpgrade
