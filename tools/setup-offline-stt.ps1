$ErrorActionPreference = 'Stop'

$project = Split-Path -Parent $PSScriptRoot
& (Join-Path $project 'gradlew.bat') -p $project -PmalloStt=sensevoice :app:prepareOfflineStt
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
