param(
    [Parameter(Mandatory = $true)]
    [int]$AptProcessId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$wsl = 'C:\Windows\System32\wsl.exe'

& $wsl '--exec' '/usr/bin/kill' '-INT' $AptProcessId
if ($LASTEXITCODE -ne 0) {
    throw "Failed to interrupt apt process $AptProcessId with exit code $LASTEXITCODE"
}

for ($attempt = 0; $attempt -lt 15; $attempt++) {
    Start-Sleep -Seconds 1
    & $wsl '--exec' '/usr/bin/test' '!' '-d' "/proc/$AptProcessId"
    if ($LASTEXITCODE -eq 0) {
        break
    }
}

& $wsl '--exec' '/usr/bin/test' '!' '-d' "/proc/$AptProcessId"
if ($LASTEXITCODE -ne 0) {
    throw "apt process $AptProcessId did not exit after SIGINT"
}

& $wsl '--exec' '/usr/bin/dpkg' '--configure' '-a'
if ($LASTEXITCODE -ne 0) {
    throw "dpkg recovery failed with exit code $LASTEXITCODE"
}
