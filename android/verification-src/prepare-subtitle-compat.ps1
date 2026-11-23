param([string]$Serial = '', [string]$Adb = '')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$destination = Join-Path $projectRoot 'verification/subtitle-cache'
New-Item -ItemType Directory -Force $destination | Out-Null
$base = 'https://raw.githubusercontent.com/androidx/media/1.8.0/libraries/test_data/src/test/assets/media/mkv/'
$assets = @(
    @{Name='sample_with_srt.mkv'; Hash='895BC216D45DECF55E8889ED8EB58F8D342A23ED8FC0FC9A2C348D2F607F45B7'},
    @{Name='sample_with_ass_subtitles.mkv'; Hash='C031E8BAB37CDA0E759B2AA7A8113A423432CE9E86C6465890926008E73D3B65'},
    @{Name='sample_with_overlapping_srt.mkv'; Hash='8EAD062D0B211BCEA10C6A9BBE654FDA446E202C56CA4CB14692A44C0BE55E1A'}
)
foreach ($asset in $assets) {
    $target = Join-Path $destination $asset.Name
    Invoke-WebRequest ($base + $asset.Name) -OutFile $target
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $asset.Hash) { throw "Fixture hash mismatch: $($asset.Name)" }
}
if ($Serial) {
    if (-not $Adb) { $Adb = (Get-Command adb -ErrorAction Stop).Source }
    $devicePath = '/sdcard/Android/data/cn.jiuwusan.jellyfin.player/files/subtitle-compat/'
    & $Adb -s $Serial shell mkdir -p $devicePath
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create device fixture directory' }
    foreach ($asset in $assets) {
        & $Adb -s $Serial push (Join-Path $destination $asset.Name) $devicePath
        if ($LASTEXITCODE -ne 0) { throw "Cannot push fixture: $($asset.Name)" }
    }
}
Write-Output 'Verified Media3 1.8.0 fixtures; binaries stay under ignored verification/.'
