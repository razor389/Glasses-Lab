param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]+$')]
    [string]$Serial,
    [string]$Adb = 'C:\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
$apk = Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk'
if (!(Test-Path -LiteralPath $apk)) { throw 'Build the companion APK first.' }
$online = & $Adb -s $Serial get-state
if ($LASTEXITCODE -ne 0 -or $online.Trim() -ne 'device') { throw 'The selected phone is not authorized and online in ADB.' }
$product = & $Adb -s $Serial shell getprop ro.product.device
$model = & $Adb -s $Serial shell getprop ro.product.model
$api = & $Adb -s $Serial shell getprop ro.build.version.sdk
if ($LASTEXITCODE -ne 0) { throw 'Could not identify the selected phone.' }
if ($product -match 'MentraLive' -or $model -match 'Mentra.?Live') {
    throw 'This is the glasses. Select the Android PHONE serial from adb devices -l.'
}
if ([int]$api.Trim() -lt 28) { throw 'Glasses Lab requires Android 9 / API 28 or newer.' }
Write-Output "Installing Glasses Lab on $($model.Trim()) ($Serial)."
& $Adb -s $Serial install -r -g $apk
if ($LASTEXITCODE -ne 0) { throw 'Companion installation failed.' }
& $Adb -s $Serial shell am start -n dev.ross.glasses/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'Installed, but the launch request failed. Open Glasses Lab on the phone.' }
