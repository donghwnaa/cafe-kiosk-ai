[CmdletBinding(DefaultParameterSetName = 'Device')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Device')]
    [ValidatePattern('^[A-Za-z0-9_.:\[\]-]+$')]
    [string]$Device,

    [Parameter(ParameterSetName = 'Device')]
    [string]$OutputPath = 'usb-capture.wav',

    [Parameter(ParameterSetName = 'Device')]
    [string]$Adb = 'adb',

    [Parameter(Mandatory, ParameterSetName = 'Local')]
    [string]$Path,

    [switch]$Play
)

$ErrorActionPreference = 'Stop'

function Read-PcmWave([string]$WavePath) {
    $reader = [IO.BinaryReader]::new([IO.File]::OpenRead($WavePath))
    try {
        $stream = $reader.BaseStream
        $riff = [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
        $riffSize = $reader.ReadUInt32()
        $wave = [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
        if ($riff -ne 'RIFF' -or $wave -ne 'WAVE' -or 8L + $riffSize -ne $stream.Length) {
            throw 'Invalid or incomplete RIFF/WAVE file.'
        }
        $rate = 0
        $offset = 0L
        $dataBytes = 0L
        while ($stream.Position + 8 -le $stream.Length) {
            $tag = [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
            $size = $reader.ReadUInt32()
            $next = $stream.Position + $size + ($size % 2)
            if ($next -gt $stream.Length) { throw 'Incomplete WAV chunk.' }
            if ($tag -eq 'fmt ' -and $size -ge 16) {
                $encoding = $reader.ReadUInt16()
                $channels = $reader.ReadUInt16()
                $rate = $reader.ReadUInt32()
                $null = $reader.ReadUInt32()
                $alignment = $reader.ReadUInt16()
                $bits = $reader.ReadUInt16()
                if ($encoding -ne 1 -or $channels -ne 1 -or $bits -ne 16 -or $alignment -ne 2 -or $rate -eq 0) {
                    throw 'Expected mono 16-bit PCM WAV.'
                }
            }
            if ($tag -eq 'data') {
                $offset = $stream.Position
                $dataBytes = $size
            }
            $stream.Position = $next
        }
        if ($rate -eq 0 -or $dataBytes -eq 0 -or $dataBytes % 2 -ne 0) { throw 'No complete PCM samples found.' }

        $stream.Position = $offset
        $samples = [long]($dataBytes / 2)
        $windows = @(
            for ($start = 0L; $start -lt $samples; $start += $rate) {
                $count = [Math]::Min($rate, $samples - $start)
                $energy = 0.0
                $peak = 0
                $nonZero = 0
                for ($i = 0; $i -lt $count; $i++) {
                    $sample = [int]$reader.ReadInt16()
                    $peak = [Math]::Max($peak, [Math]::Abs($sample))
                    if ($sample -ne 0) { $nonZero++ }
                    $energy += [double]$sample * $sample
                }
                [pscustomobject]@{
                    StartSeconds = $start / $rate
                    Samples = $count
                    Rms = [Math]::Sqrt($energy / $count) / 32768
                    Peak = $peak
                    NonZero = $nonZero
                }
            }
        )
        [pscustomobject]@{ SampleRate = $rate; Samples = $samples; Seconds = $samples / $rate; Windows = $windows }
    } finally {
        $reader.Dispose()
    }
}

if ($PSCmdlet.ParameterSetName -eq 'Device') {
    $target = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)
    if ([IO.File]::Exists($target)) { throw "Output already exists: $target" }
    $partial = "$target.$([Guid]::NewGuid().ToString('N')).part"
    try {
        $info = [Diagnostics.ProcessStartInfo]::new(
            (Get-Command -Name $Adb -CommandType Application -ErrorAction Stop).Source,
            "-s $Device exec-out run-as com.example.usbaudioprobe cat cache/usb-capture.wav"
        )
        $info.UseShellExecute = $false
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $info.CreateNoWindow = $true
        $process = [Diagnostics.Process]::Start($info)
        try {
            $capture = [IO.File]::Open($partial, [IO.FileMode]::CreateNew)
            try {
                $stderr = $process.StandardError.ReadToEndAsync()
                $copy = $process.StandardOutput.BaseStream.CopyToAsync($capture)
                if (!$copy.Wait(15000) -or !$process.WaitForExit(2000)) { throw 'Timed out retrieving WAV through ADB.' }
                if ($process.ExitCode -ne 0) { throw "ADB WAV extraction failed. $($stderr.Result.Trim())" }
            } finally {
                $capture.Dispose()
            }
        } finally {
            if (!$process.HasExited) { $process.Kill() }
            $process.Dispose()
        }
        $stats = Read-PcmWave $partial
        [IO.File]::Move($partial, $target)
    } finally {
        if ([IO.File]::Exists($partial)) { [IO.File]::Delete($partial) }
    }
} else {
    $target = (Resolve-Path -LiteralPath $Path).ProviderPath
    $stats = Read-PcmWave $target
}

Write-Host "WAV $target"
Write-Host ("{0} Hz | {1} samples | {2:F3} s" -f $stats.SampleRate, $stats.Samples, $stats.Seconds)
$stats.Windows | Format-Table -AutoSize
if ($Play) {
    $player = [System.Media.SoundPlayer]::new($target)
    try { $player.PlaySync() } finally { $player.Dispose() }
}
