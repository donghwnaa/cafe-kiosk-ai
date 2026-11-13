param(
    [string]$Serial,
    [string]$AdbPath,
    [string]$Text
)

$ErrorActionPreference = 'Stop'
if (-not $AdbPath) {
    $adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
    $AdbPath = if ($adbCommand) { $adbCommand.Source } else {
        $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else {
            Join-Path $env:LOCALAPPDATA 'Android\Sdk'
        }
        Join-Path $sdk 'platform-tools\adb.exe'
    }
}
if (-not (Test-Path -LiteralPath $AdbPath)) {
    throw 'adb.exe not found. Set ANDROID_HOME or pass -AdbPath.'
}

$deviceArgs = if ($Serial) { @('-s', $Serial) } else { @() }
$interactive = -not $PSBoundParameters.ContainsKey('Text')
do {
    if ($interactive) { $Text = Read-Host 'Type a sentence (blank to quit)' }
    if ([string]::IsNullOrWhiteSpace($Text)) { break }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Text))
    $result = & $AdbPath @deviceArgs shell am broadcast -a com.example.cafe_kiosk_ai.DEBUG_TEXT_INPUT -p com.example.cafe_kiosk_ai --es text_b64 $encoded
    if ($LASTEXITCODE -ne 0 -or -not ($result -match 'Broadcast completed: result=-1\s*$')) {
        throw "Input was not accepted. Keep the debug app's order screen in the foreground and wait for any pending response. $result"
    }
    Write-Host 'Text was submitted to the order session.'
} while ($interactive)
