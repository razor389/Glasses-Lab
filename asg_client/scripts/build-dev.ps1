param(
    [string]$JavaHome,
    [string]$AndroidSdkRoot,
    [switch]$RunTests,
    [string[]]$TestFilter = @('*DevelopmentSafetyTest', '*OtaCommandHandlerTest', '*FactoryResetEventSubscriberTest', '*MentraLiveSystemControllerTest')
)

$ErrorActionPreference = 'Stop'
$asgRoot = Split-Path $PSScriptRoot -Parent
$repoRoot = Split-Path $asgRoot -Parent
$localRoot = Join-Path $repoRoot 'local/asg-dev'
$configFile = Join-Path $localRoot 'toolchain.json'
if (Test-Path -LiteralPath $configFile) {
    $config = Get-Content -Raw -LiteralPath $configFile | ConvertFrom-Json
    if (!$JavaHome) { $JavaHome = $config.javaHome }
    if (!$AndroidSdkRoot) { $AndroidSdkRoot = $config.androidSdkRoot }
}
if (!$JavaHome) { $JavaHome = $env:JAVA_HOME }
if (!$AndroidSdkRoot) { $AndroidSdkRoot = $env:ANDROID_HOME }
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'Supply -JavaHome pointing to a Java 17 JDK.'
}
if (!$AndroidSdkRoot -or !(Test-Path -LiteralPath (Join-Path $AndroidSdkRoot 'platforms/android-34/android.jar'))) {
    throw 'Supply -AndroidSdkRoot containing Android platform 34 and build-tools 34.0.0.'
}

$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_USER_HOME', 'GRADLE_USER_HOME', 'JAVA_TOOL_OPTIONS')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
New-Item -ItemType Directory -Path $localRoot -Force | Out-Null
Push-Location $asgRoot
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidSdkRoot
    $env:ANDROID_USER_HOME = Join-Path $localRoot 'android-user'
    $env:GRADLE_USER_HOME = Join-Path $localRoot 'gradle'
    $javaUserHome = Join-Path $localRoot 'java-home'
    New-Item -ItemType Directory -Path $javaUserHome -Force | Out-Null
    $env:JAVA_TOOL_OPTIONS = "-Duser.home=`"$javaUserHome`""
    $keyFile = Join-Path $localRoot 'debug.keystore'
    if (!(Test-Path -LiteralPath $keyFile)) {
        & (Join-Path $JavaHome 'bin/keytool.exe') -genkeypair -keystore $keyFile `
            -storepass android -keypass android -alias androiddebugkey -keyalg RSA `
            -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the development signing key.' }
    }
    $gradleArguments = @(':app:assembleDebug', '--no-daemon', '-PASG_THIRD_PARTY=true', "-PASG_DEBUG_KEYSTORE=$keyFile")
    if ($RunTests) {
        $gradleArguments += ':app:testDebugUnitTest'
        # Gradle runs the full suite without --tests. Do not forward a bare '*':
        # the Windows Java launcher can expand it into filenames before Gradle sees it.
        if ($TestFilter -notcontains '*') {
            foreach ($filter in $TestFilter) { $gradleArguments += @('--tests', $filter) }
        }
    }
    & ./gradlew.bat @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw 'Development build/checks failed; no device commands were run.' }

    $apk = Join-Path $asgRoot 'app/build/outputs/apk/debug/app-debug.apk'
    & (Join-Path $AndroidSdkRoot 'build-tools/34.0.0/apksigner.bat') verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    Write-Output "Development APK: $apk"
    Get-FileHash -LiteralPath $apk -Algorithm SHA256
} finally {
    Pop-Location
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
