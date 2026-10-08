# Focused tests with synthetic APK/tool output; no SDK, keys, Gradle or publishing.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$module = Import-Module (Join-Path $PSScriptRoot 'ApkUpgradeGuard.psm1') -Force -PassThru
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("apk upgrade guard {0}" -f [guid]::NewGuid())
$null = New-Item -ItemType Directory -Path $temporaryRoot
$previousApk = Join-Path $temporaryRoot 'previous debug.apk'
$candidateApk = Join-Path $temporaryRoot 'candidate debug.apk'
[System.IO.File]::WriteAllText($previousApk, 'synthetic previous APK')
[System.IO.File]::WriteAllText($candidateApk, 'synthetic candidate APK')
$digest = 'a1' * 32
$script:passed = 0

# Install tool doubles only in this test's imported module session.
& $module {
    function script:Test-Java {
        if (($args[0..4] -join ' ') -ne '-jar test-apksigner.jar verify --verbose --print-certs' -or $args.Count -ne 6) {
            throw 'Unexpected apksigner command; tests permit verification only.'
        }
        $fixture = $script:Fixtures[[string]$args[-1]]
        $global:LASTEXITCODE = $fixture.SigningExitCode
        $fixture.Signing
    }
    function script:Test-Aapt {
        if (($args[0..1] -join ' ') -ne 'dump badging' -or $args.Count -ne 3) {
            throw 'Unexpected aapt2 command.'
        }
        $fixture = $script:Fixtures[[string]$args[-1]]
        $global:LASTEXITCODE = $fixture.BadgingExitCode
        $fixture.Badging
    }
}

function New-Fixtures {
    $fixtures = @{}
    foreach ($entry in @(@($previousApk, 23), @($candidateApk, 24))) {
        $fixtures[$entry[0]] = @{
            SigningExitCode = 0
            BadgingExitCode = 0
            Signing = @('Verifies', 'Number of signers: 1', "Signer #1 certificate SHA-256 digest: $digest")
            Badging = @("package: name='com.richard.tunnelkeeper' versionCode='$($entry[1])' versionName='0.1.22'")
        }
    }
    return $fixtures
}

function Invoke-Guard {
    param([hashtable]$Fixtures)
    & $module { param($Value) $script:Fixtures = $Value } $Fixtures
    Assert-ApkUpgrade -ApkPath $candidateApk -PreviousApk $previousApk `
        -JavaPath 'Test-Java' -ApkSignerJar 'test-apksigner.jar' -Aapt2Path 'Test-Aapt'
}

function Assert-Rejected {
    param([string]$Name, [hashtable]$Fixtures, [string]$ExpectedError)
    $errorMessage = $null
    try { $null = Invoke-Guard $Fixtures }
    catch { $errorMessage = $_.Exception.Message }
    if (-not $errorMessage -or $errorMessage -notmatch $ExpectedError) {
        throw "FAIL: $Name; expected $ExpectedError; got $errorMessage"
    }
    $script:passed++
    Write-Output "PASS: $Name"
}

try {
    # Exercise actual native stdout/stderr and exit status using this PowerShell
    # executable as a harmless stand-in. No SDK or external downloads are used.
    $nativeStub = Join-Path $temporaryRoot 'native tool stub.ps1'
    $nativePwsh = (Get-Process -Id $PID).Path
    [System.IO.File]::WriteAllText($nativeStub, '[Console]::Error.WriteLine("test-stderr"); [Console]::Out.WriteLine("test-stdout"); exit 0')
    $nativeOutput = @(& $module { param($Tool, $Stub)
        Invoke-ApkInspection -ToolPath $Tool -Arguments @('-NoLogo', '-NoProfile', '-File', $Stub)
    } $nativePwsh $nativeStub)
    if (($nativeOutput -join "`n") -notmatch 'test-stderr' -or ($nativeOutput -join "`n") -notmatch 'test-stdout') {
        throw 'FAIL: successful native stderr/stdout capture'
    }
    $script:passed++
    Write-Output 'PASS: actual native stderr with exit zero is captured without rejection'

    [System.IO.File]::WriteAllText($nativeStub, '[Console]::Out.WriteLine("Verifies"); exit 7')
    $errorMessage = $null
    try {
        $null = & $module { param($Tool, $Stub)
            Invoke-ApkInspection -ToolPath $Tool -Arguments @('-NoLogo', '-NoProfile', '-File', $Stub)
        } $nativePwsh $nativeStub
    }
    catch { $errorMessage = $_.Exception.Message }
    if (-not $errorMessage -or $errorMessage -notmatch 'exit code 7') {
        throw "FAIL: actual native nonzero exit; got $errorMessage"
    }
    $script:passed++
    Write-Output 'PASS: actual native nonzero exit is rejected'

    $global:LASTEXITCODE = 0
    $errorMessage = $null
    try {
        $null = & $module { param($MissingTool)
            Invoke-ApkInspection -ToolPath $MissingTool -Arguments @('verify')
        } (Join-Path $temporaryRoot 'missing-native-tool')
    }
    catch { $errorMessage = $_.Exception.Message }
    if (-not $errorMessage) { throw 'FAIL: missing native tool reused a previous successful exit code' }
    $script:passed++
    Write-Output 'PASS: missing native tool is rejected after a previous zero exit code'

    $before = @((Get-FileHash $previousApk).Hash, (Get-FileHash $candidateApk).Hash)
    $result = Invoke-Guard (New-Fixtures)
    if ($result.PreviousVersionCode -ne 23 -or $result.VersionCode -ne 24 -or $result.SigningCertificateSha256 -ne $digest) {
        throw 'FAIL: valid upgrade result'
    }
    $script:passed++
    Write-Output 'PASS: matching signer, higher version and paths with spaces'

    $fixtures = New-Fixtures
    $fixtures[$candidateApk].Signing[2] = "Signer #1 certificate SHA-256 digest: $($digest.ToUpperInvariant())"
    $fixtures[$candidateApk].Signing += @(
        "Signer #1 public key SHA-256 digest: $('bb' * 32)",
        "Source Stamp Signer certificate SHA-256 digest: $('cc' * 32)",
        'WARNING: harmless test warning'
    )
    $null = Invoke-Guard $fixtures
    $script:passed++
    Write-Output 'PASS: digest case, public-key/source-stamp noise and warnings'

    $fixtures = New-Fixtures
    $fixtures[$candidateApk].Signing[2] = "Signer #1 certificate SHA-256 digest: $('bb' * 32)"
    Assert-Rejected 'different signing certificate' $fixtures 'signing certificate differs'

    foreach ($path in @($previousApk, $candidateApk)) {
        foreach ($field in @('SigningExitCode', 'BadgingExitCode')) {
            $fixtures = New-Fixtures
            $fixtures[$path][$field] = 7
            Assert-Rejected "native failure despite plausible output: $field, $([IO.Path]::GetFileName($path))" $fixtures 'exit code 7'
        }
        $fixtures = New-Fixtures
        $fixtures[$path].Badging = @("package: name='other.package' versionCode='23' versionName='com.richard.tunnelkeeper'")
        Assert-Rejected "wrong package: $([IO.Path]::GetFileName($path))" $fixtures 'applicationId'
    }

    foreach ($version in @('23', '22')) {
        $fixtures = New-Fixtures
        $fixtures[$candidateApk].Badging = @("package: name='com.richard.tunnelkeeper' versionCode='$version' versionName='0.1.23'")
        Assert-Rejected "non-increasing version $version" $fixtures 'versionCode must increase'
    }

    $invalidSigners = @(
        @{ Name = 'missing signer output'; Lines = @() },
        @{ Name = 'missing count'; Lines = @("Signer #1 certificate SHA-256 digest: $digest") },
        @{ Name = 'duplicate count'; Lines = @('Number of signers: 1', 'Number of signers: 1', "Signer #1 certificate SHA-256 digest: $digest") },
        @{ Name = 'multiple signers'; Lines = @('Number of signers: 2', "Signer #1 certificate SHA-256 digest: $digest", "Signer #2 certificate SHA-256 digest: $digest") },
        @{ Name = 'duplicate certificate'; Lines = @('Number of signers: 1', "Signer #1 certificate SHA-256 digest: $digest", "Signer #1 certificate SHA-256 digest: $digest") },
        @{ Name = 'malformed certificate'; Lines = @('Number of signers: 1', 'Signer #1 certificate SHA-256 digest: abc') },
        @{ Name = 'missing certificate'; Lines = @('Number of signers: 1', "Signer #1 public key SHA-256 digest: $digest") },
        @{ Name = 'rotated signer format'; Lines = @('Number of signers: 1', "Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: $digest") }
    )
    foreach ($case in $invalidSigners) {
        $fixtures = New-Fixtures
        $fixtures[$candidateApk].Signing = $case.Lines
        Assert-Rejected $case.Name $fixtures 'exactly one APK signing certificate'
    }

    foreach ($case in @(
        @{ Name = 'missing package'; Lines = @() },
        @{ Name = 'duplicate package'; Lines = @("package: name='com.richard.tunnelkeeper' versionCode='24'", "package: name='com.richard.tunnelkeeper' versionCode='24'") },
        @{ Name = 'duplicate package name'; Lines = @("package: name='com.richard.tunnelkeeper' name='other.package' versionCode='24'") },
        @{ Name = 'missing version'; Lines = @("package: name='com.richard.tunnelkeeper' versionName='24'") },
        @{ Name = 'duplicate version'; Lines = @("package: name='com.richard.tunnelkeeper' versionCode='24' versionCode='25'") },
        @{ Name = 'overflow version'; Lines = @("package: name='com.richard.tunnelkeeper' versionCode='9999999999999999999999999'") },
        @{ Name = 'negative version'; Lines = @("package: name='com.richard.tunnelkeeper' versionCode='-1'") },
        @{ Name = 'zero version'; Lines = @("package: name='com.richard.tunnelkeeper' versionCode='0'") },
        @{ Name = 'out-of-range version'; Lines = @("package: name='com.richard.tunnelkeeper' versionCode='2147483648'") }
    )) {
        $fixtures = New-Fixtures
        $fixtures[$candidateApk].Badging = $case.Lines
        Assert-Rejected $case.Name $fixtures 'package record|applicationId|valid versionCode'
    }

    # Verify the publisher rejects an absent baseline before touching the workspace.
    # It is intentionally invoked only on an isolated copy, never this checkout.
    $isolatedScripts = Join-Path $temporaryRoot 'isolated/scripts'
    $null = New-Item -ItemType Directory -Path $isolatedScripts -Force
    $publisher = Join-Path $isolatedScripts 'Publish-Apk.ps1'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Publish-Apk.ps1') -Destination $publisher
    $errorMessage = $null
    try { & $publisher -ToolchainRoot (Join-Path $temporaryRoot 'no-toolchain') }
    catch { $errorMessage = $_.Exception.Message }
    if (-not $errorMessage -or $errorMessage -notmatch 'PreviousApk must point') {
        throw "FAIL: missing baseline preflight; got $errorMessage"
    }
    if (@(Get-ChildItem (Split-Path $isolatedScripts -Parent) -File -Recurse).Count -ne 1) {
        throw 'FAIL: rejected preflight changed the isolated workspace'
    }
    $script:passed++
    Write-Output 'PASS: missing baseline stops publisher without workspace mutations'

    $isolatedBuild = Join-Path (Split-Path $isolatedScripts -Parent) 'app/build'
    $null = New-Item -ItemType Directory -Path $isolatedBuild -Force
    $unsafeBaseline = Join-Path $isolatedBuild 'old-debug.apk'
    [System.IO.File]::WriteAllText($unsafeBaseline, 'synthetic baseline inside build output')
    $errorMessage = $null
    try { & $publisher -ToolchainRoot (Join-Path $temporaryRoot 'no-toolchain') -PreviousApk $unsafeBaseline }
    catch { $errorMessage = $_.Exception.Message }
    if (-not $errorMessage -or $errorMessage -notmatch 'retained outside app/build') {
        throw "FAIL: baseline inside build output; got $errorMessage"
    }
    $script:passed++
    Write-Output 'PASS: publisher rejects a baseline that the build could overwrite'

    # Static wiring check, not an end-to-end publish: the actual gate must precede
    # the first archive copy, which precedes version replacement and pruning.
    $tokens = $null
    $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($publisher, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw "FAIL: publish syntax: $parseErrors" }
    $gates = @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Assert-ApkUpgrade'
    }, $true))
    $copies = @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Copy-Item'
    }, $true))
    if ($gates.Count -ne 1 -or $copies.Count -ne 1 -or $gates[0].Extent.StartOffset -ge $copies[0].Extent.StartOffset) {
        throw 'FAIL: publisher must invoke the upgrade guard before copying an archive'
    }
    $script:passed++
    Write-Output 'PASS: static publish wiring places upgrade guard before archive copy'

    $wrapper = Join-Path $isolatedScripts 'verify-android.ps1'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'verify-android.ps1') -Destination $wrapper
    # Replace the isolated publisher with a parameter-recording stub. No build runs.
    [System.IO.File]::WriteAllText($publisher, @'
param([string]$ToolchainRoot, [string]$AndroidSdk, [string]$PreviousApk)
$global:LASTEXITCODE = 0
[pscustomobject]@{ ToolchainRoot = $ToolchainRoot; AndroidSdk = $AndroidSdk; PreviousApk = $PreviousApk }
'@)
    $forwarded = & $wrapper -ToolchainRoot 'test toolchain' -AndroidSdk 'test sdk' -PreviousApk $previousApk
    if ($forwarded.PreviousApk -ne $previousApk -or $forwarded.ToolchainRoot -ne 'test toolchain' -or $forwarded.AndroidSdk -ne 'test sdk') {
        throw 'FAIL: verify-android must forward PreviousApk and toolchain paths'
    }
    $script:passed++
    Write-Output 'PASS: verify-android forwards the trusted baseline and toolchain paths'

    $after = @((Get-FileHash $previousApk).Hash, (Get-FileHash $candidateApk).Hash)
    if (@(Compare-Object $before $after).Count -ne 0) { throw 'FAIL: verifier changed its input files' }
    $script:passed++
    Write-Output 'PASS: input APK files unchanged'
    Write-Output "PASS: $script:passed upgrade-guard checks (synthetic tool output, not real APK/device verification)."
}
finally {
    Remove-Module $module
    Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
}
