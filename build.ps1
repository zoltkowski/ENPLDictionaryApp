param(
    [ValidateSet('Debug','Release')]
    [string]$Variant = 'Debug',
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME) {
    $androidStudioJdk = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path $androidStudioJdk) {
        $env:JAVA_HOME = $androidStudioJdk
    }
}

function Fail([string]$Message) {
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

Write-Host "EN-PL Dictionary Android build ($Variant)" -ForegroundColor Cyan

if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $javaCmd = Join-Path $env:JAVA_HOME 'bin\java.exe'
} elseif (Get-Command java -ErrorAction SilentlyContinue) {
    $javaCmd = 'java'
} else {
    Fail "Java not found. Install JDK 17 and make sure java.exe is on PATH (or set JAVA_HOME)."
}

$javaVersion = (& $javaCmd -version 2>&1 | Select-Object -First 1)
Write-Host "Java: $javaVersion"
if ($javaVersion -notmatch '17[\.\"]') {
    Write-Warning "This project targets Java 17 bytecode; current runtime: $javaVersion"
}

if (-not $env:ANDROID_SDK_ROOT -and $env:ANDROID_HOME) {
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
}
if (-not $env:ANDROID_SDK_ROOT) {
    $defaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $defaultSdk) { $env:ANDROID_SDK_ROOT = $defaultSdk }
}
if (-not $env:ANDROID_SDK_ROOT -or -not (Test-Path $env:ANDROID_SDK_ROOT)) {
    Fail "Android SDK not found. Install Android Studio / SDK 35 or set ANDROID_SDK_ROOT."
}
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
Write-Host "Android SDK: $env:ANDROID_SDK_ROOT"

$gradleCmd = $null
if (Test-Path '.\gradlew.bat') {
    $gradleCmd = '.\gradlew.bat'
} elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
    $gradleCmd = 'gradle'
} else {
    $version = '9.7.1'
    $toolDir = Join-Path $PSScriptRoot '.tools'
    $gradleHome = Join-Path $toolDir "gradle-$version"
    $gradleBat = Join-Path $gradleHome 'bin\gradle.bat'
    if (-not (Test-Path $gradleBat)) {
        New-Item -ItemType Directory -Force -Path $toolDir | Out-Null
        $zip = Join-Path $toolDir "gradle-$version-bin.zip"
        if (-not (Test-Path $zip)) {
            Write-Host "Downloading Gradle $version..."
            Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$version-bin.zip" -OutFile $zip
        }
        Write-Host "Extracting Gradle..."
        Expand-Archive -Path $zip -DestinationPath $toolDir -Force
    }
    $gradleCmd = $gradleBat
}

$task = if ($Variant -eq 'Release') { ':app:assembleRelease' } else { ':app:assembleDebug' }
if ($Clean) {
    & $gradleCmd --no-daemon clean
    if ($LASTEXITCODE -ne 0) { Fail "Gradle clean failed." }
}

Write-Host "Running $task..." -ForegroundColor Cyan
& $gradleCmd --no-daemon $task
if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed." }

$variantLower = $Variant.ToLowerInvariant()
$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\$variantLower\app-$variantLower.apk"
if (-not (Test-Path $apk)) { Fail "Build finished but APK was not found at $apk" }

$out = Join-Path $PSScriptRoot "ENPLDictionary-$variantLower.apk"
Copy-Item $apk $out -Force
Write-Host "\nAPK: $out" -ForegroundColor Green
