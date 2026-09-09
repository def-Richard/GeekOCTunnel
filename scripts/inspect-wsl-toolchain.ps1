Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$wsl = 'C:\Windows\System32\wsl.exe'
& $wsl '--exec' '/usr/bin/df' '-h' '/tmp'
if ($LASTEXITCODE -ne 0) {
    throw "WSL df failed with exit code $LASTEXITCODE"
}

$tools = @('git', 'make', 'autoconf', 'automake', 'libtool', 'pkg-config', 'patch', 'curl', 'unzip', 'javac')
foreach ($tool in $tools) {
    $resolved = & $wsl '--exec' '/usr/bin/which' $tool
    [PSCustomObject]@{
        Tool = $tool
        Installed = $LASTEXITCODE -eq 0
        Path = if ($LASTEXITCODE -eq 0) { $resolved } else { $null }
    }
}

& $wsl '--exec' '/usr/bin/uname' '-a'
if ($LASTEXITCODE -ne 0) {
    throw "WSL uname failed with exit code $LASTEXITCODE"
}

& $wsl '--exec' '/usr/bin/id' '-u'
if ($LASTEXITCODE -ne 0) {
    throw "WSL id failed with exit code $LASTEXITCODE"
}
