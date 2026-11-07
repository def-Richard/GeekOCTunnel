Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'Use the Linux build instructions on Linux.' }
if (-not $env:QT_ROOT -or -not (Test-Path -LiteralPath (Join-Path $env:QT_ROOT 'bin/Qt6Core.dll'))) {
    throw 'Set QT_ROOT to an MSVC x64 Qt 6.4+ SDK and run in a Visual Studio developer shell.'
}
foreach ($tool in 'cmake', 'ninja', 'cargo', 'npm') { Get-Command $tool -ErrorAction Stop | Out-Null }
$repo = Split-Path $PSScriptRoot -Parent
$nativeBuild = Join-Path $repo 'build/desktop-native'
$headers = if ($env:OPENCONNECT_HEADERS) { $env:OPENCONNECT_HEADERS } else { Join-Path $repo 'native/src/openconnect' }
cmake -S $PSScriptRoot -B $nativeBuild -G Ninja '-DCMAKE_BUILD_TYPE=Release' "-DCMAKE_PREFIX_PATH=$env:QT_ROOT" "-DOPENCONNECT_INCLUDE_DIR=$headers"
if ($LASTEXITCODE -ne 0) { throw 'Native configure failed' }
cmake --build $nativeBuild --parallel 4
if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }
$env:Path = (Join-Path $env:QT_ROOT 'bin') + ';' + $env:Path
ctest --test-dir $nativeBuild --output-on-failure
if ($LASTEXITCODE -ne 0) { throw 'Native tests failed' }
Push-Location (Join-Path $PSScriptRoot 'frontend')
try {
    npm ci
    if ($LASTEXITCODE -ne 0) { throw 'Frontend dependencies failed' }
    npm test
    if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed' }
    if (-not $env:OPENCONNECT_RUNTIME -or -not $env:WINTUN_DLL) { throw 'Set OPENCONNECT_RUNTIME to the patched DLL directory and WINTUN_DLL to the verified driver DLL.' }
    npm run prepare:native -- $nativeBuild (Join-Path $env:QT_ROOT 'bin') $env:OPENCONNECT_RUNTIME $env:WINTUN_DLL
    if ($LASTEXITCODE -ne 0) { throw 'Native resource preparation failed' }
    npm run tauri -- build --no-bundle -- --locked
    if ($LASTEXITCODE -ne 0) { throw 'Tauri build failed' }
    Push-Location 'src-tauri'
    try {
        cargo test --release --locked --features tauri/custom-protocol
        if ($LASTEXITCODE -ne 0) { throw 'Native transport tests failed' }
    } finally { Pop-Location }
} finally { Pop-Location }
