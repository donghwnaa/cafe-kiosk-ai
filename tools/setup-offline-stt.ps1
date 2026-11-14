$ErrorActionPreference = 'Stop'

$project = Split-Path -Parent $PSScriptRoot
$install = Join-Path $project 'app/offline-stt'
$downloads = Join-Path $install 'downloads'
$assets = Join-Path $install 'assets/stt'
$release = 'https://github.com/k2-fsa/sherpa-onnx/releases/download'
$model = 'sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17'
$aar = 'sherpa-onnx-2.0.1.aar'
$vad = 'silero_vad.onnx'
New-Item -ItemType Directory -Force -Path $downloads, $assets | Out-Null

function Get-ReleaseFile($Tag, $Name, $Sha256) {
    $file = Join-Path $downloads $Name
    if ((Test-Path -LiteralPath $file) -and (Get-FileHash -LiteralPath $file).Hash -eq $Sha256) {
        return $file
    }
    curl.exe --fail --silent --show-error --location --retry 2 --output "$file.part" "$release/$Tag/$Name"
    if ($LASTEXITCODE -ne 0) { throw "Download failed for $Name (curl exit $LASTEXITCODE)." }
    if ((Get-FileHash -LiteralPath "$file.part").Hash -ne $Sha256) {
        throw "SHA-256 mismatch for $Name."
    }
    Move-Item -LiteralPath "$file.part" -Destination $file -Force
    return $file
}

$aarFile = Get-ReleaseFile 'v2.0.1' $aar 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'
$vadFile = Get-ReleaseFile 'asr-models' $vad '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6'
$archive = Get-ReleaseFile 'asr-models' "$model.tar.bz2" '7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e'
tar.exe -xf $archive -C $downloads "$model/model.int8.onnx" "$model/tokens.txt" "$model/LICENSE"
if ($LASTEXITCODE -ne 0) { throw "Could not extract $archive." }

Copy-Item -LiteralPath $aarFile -Destination (Join-Path $install $aar) -Force
Copy-Item -LiteralPath $vadFile -Destination (Join-Path $assets $vad) -Force
Copy-Item -LiteralPath (Join-Path $downloads "$model/model.int8.onnx") -Destination $assets -Force
Copy-Item -LiteralPath (Join-Path $downloads "$model/tokens.txt") -Destination $assets -Force
Copy-Item -LiteralPath (Join-Path $downloads "$model/LICENSE") -Destination (Join-Path $assets 'SenseVoice-LICENSE.txt') -Force
Write-Host 'SenseVoice assets are ready. Sync Gradle and click Run in Android Studio.'
