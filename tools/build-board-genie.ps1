param(
    [Parameter(Mandatory)][string]$NdkPath,
    [string]$Serial,
    [string]$AdbPath = 'adb.exe'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$clang = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
$output = Join-Path $root 'build/genie/mallo-genie-worker-v3'
$source = Join-Path $PSScriptRoot 'genie/mallo-genie-worker.c'
New-Item -ItemType Directory -Path (Split-Path -Parent $output) -Force | Out-Null
& $clang --target=aarch64-linux-android24 -O2 -Wall -Wextra -Werror '-Wl,--strip-all' $source -o $output -ldl -llog
if ($LASTEXITCODE -ne 0) { throw 'Could not build the Genie worker.' }

if ($Serial) {
    $destination = '/data/local/tmp/bundle/mallo-genie-worker-v3'
    $staging = "$destination.$PID"
    & $AdbPath -s $Serial push $output $staging
    if ($LASTEXITCODE -ne 0) { throw 'Could not transfer the Genie worker.' }
    & $AdbPath -s $Serial shell "chmod 755 $staging && mv $staging $destination"
    if ($LASTEXITCODE -ne 0) { throw 'Could not install the Genie worker.' }
}

Write-Output $output
