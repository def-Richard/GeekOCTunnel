param(
    [Parameter(Mandatory = $true)]
    [string]$ToolchainRoot,

    [Parameter(Mandatory = $false)]
    [string]$AndroidSdk = (Join-Path $ToolchainRoot 'android-sdk')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$javaHome = Join-Path $ToolchainRoot 'jdk-17'
$gradleLauncher = Join-Path $ToolchainRoot 'gradle-8.9\lib\gradle-launcher-8.9.jar'
$java = Join-Path $javaHome 'bin\java.exe'
$verificationDirectory = Join-Path (Get-Location) 'build\verification'

foreach ($requiredPath in @($java, $AndroidSdk, $gradleLauncher)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Missing build tool: $requiredPath"
    }
}

New-Item -ItemType Directory -Force -Path $verificationDirectory | Out-Null
$standardOutput = Join-Path $verificationDirectory 'gradle-stdout.log'
$standardError = Join-Path $verificationDirectory 'gradle-stderr.log'
$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$arguments = "-classpath `"$gradleLauncher`" -Dorg.gradle.java.home=`"$javaHome`" org.gradle.launcher.GradleMain testDebugUnitTest assembleDebug"

$process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory (Get-Location) -WindowStyle Hidden -PassThru -RedirectStandardOutput $standardOutput -RedirectStandardError $standardError
[PSCustomObject]@{
    ProcessId = $process.Id
    StandardOutput = $standardOutput
    StandardError = $standardError
}
