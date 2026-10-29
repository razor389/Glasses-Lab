param([switch]$RunTests, [switch]$BuildDeviceTests, [switch]$RebuildApp)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$localRoot = Join-Path $repoRoot 'local/asg-dev'
$config = Get-Content -Raw -LiteralPath (Join-Path $localRoot 'toolchain.json') | ConvertFrom-Json
$saved = @{}
foreach ($name in @('JAVA_HOME','ANDROID_HOME','ANDROID_USER_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Push-Location $PSScriptRoot
try {
    # The Java downloader received HTTP 406 from GitHub on this Windows toolchain.
    # Windows curl follows the official release redirect; Gradle verifies this hash again.
    $sherpaDir = Join-Path $localRoot 'phone-maven/com/k2fsa/sherpa/onnx/sherpa-onnx/1.13.2'
    $sherpaFile = Join-Path $sherpaDir 'sherpa-onnx-1.13.2.aar'
    if (!(Test-Path -LiteralPath $sherpaFile)) {
        New-Item -ItemType Directory -Path $sherpaDir -Force | Out-Null
        $partialFile = Join-Path $sherpaDir 'download.part'
        & curl.exe --fail --location --retry 2 --output $partialFile 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.aar'
        if ($LASTEXITCODE -ne 0) { throw 'Sherpa dependency download failed.' }
        $hash = (Get-FileHash -LiteralPath $partialFile -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -ne 'aa5505c0ec4f8bdaee5f214a64ba3012be64f2aecc022e82a64f33392b8dd245') {
            throw 'Sherpa dependency checksum mismatch.'
        }
        Move-Item -LiteralPath $partialFile -Destination $sherpaFile
    }
    $env:JAVA_HOME = $config.javaHome
    $env:ANDROID_HOME = $config.androidSdkRoot
    $env:ANDROID_USER_HOME = Join-Path $localRoot 'android-user'
    $env:GRADLE_USER_HOME = Join-Path $localRoot 'gradle'
    $javaUserHome = Join-Path $localRoot 'java-home'
    $env:JAVA_TOOL_OPTIONS = "-Duser.home=`"$javaUserHome`""
    $arguments = @(':app:assembleDebug', '--no-daemon')
    if ($RebuildApp) { $arguments = @(':app:clean') + $arguments }
    if ($RunTests) { $arguments += ':app:testDebugUnitTest' }
    if ($BuildDeviceTests) { $arguments += ':app:assembleDebugAndroidTest' }
    & ./gradlew.bat @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Companion build failed.' }
    $apk = Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk'
    & (Join-Path $config.androidSdkRoot 'build-tools/35.0.0/apksigner.bat') verify --verbose $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    Get-FileHash -LiteralPath $apk -Algorithm SHA256
} finally {
    Pop-Location
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
