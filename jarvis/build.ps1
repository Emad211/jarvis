# Build the Jarvis console APK with the Android SDK tools only (no Gradle).
# Same six steps as build.sh: aapt2 -> javac --release 8 -> d8 -> zipalign -> apksigner.
#   pwsh -File jarvis\build.ps1
#   pwsh -File jarvis\build.ps1 -Install        also install on the phone over adb
param(
    [switch]$Install
)
$ErrorActionPreference = 'Stop'

$SDK = 'C:\Users\Public\Android\sdk'
$BT = Join-Path $SDK 'build-tools\35.0.0'
$ANDROID_JAR = Join-Path $SDK 'platforms\android-35\android.jar'
$PROJ = $PSScriptRoot
$ROOT = Split-Path $PROJ -Parent
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.16.8-hotspot' }
$JAVAC = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$KEYTOOL = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
$OUT = Join-Path $PROJ 'build'
$DIST = Join-Path $PROJ 'dist'
$KEYSTORE = Join-Path $ROOT 'keystore.jks'

Remove-Item -Recurse -Force $OUT -ErrorAction SilentlyContinue
foreach ($d in 'res', 'classes', 'dex', 'gen') { New-Item -ItemType Directory -Force -Path (Join-Path $OUT $d) | Out-Null }
New-Item -ItemType Directory -Force -Path $DIST | Out-Null

function Run($exe, $arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe failed with exit code $LASTEXITCODE" }
}

Write-Host '==> 1/6 aapt2 compile'
Run (Join-Path $BT 'aapt2.exe') @('compile', '--dir', (Join-Path $PROJ 'res'), '-o', (Join-Path $OUT 'res\resources.zip'))

Write-Host '==> 2/6 aapt2 link'
Run (Join-Path $BT 'aapt2.exe') @(
    'link',
    '-o', (Join-Path $OUT 'base.apk'),
    '-I', $ANDROID_JAR,
    '--manifest', (Join-Path $PROJ 'AndroidManifest.xml'),
    '--min-sdk-version', '26',
    '--target-sdk-version', '35',
    '-A', (Join-Path $PROJ 'assets'),
    '--java', (Join-Path $OUT 'gen'),
    (Join-Path $OUT 'res\resources.zip'))

Write-Host '==> 3/6 javac'
$sources = @()
$sources += Get-ChildItem -Recurse -Filter *.java (Join-Path $OUT 'gen') -ErrorAction SilentlyContinue | ForEach-Object FullName
$sources += Get-ChildItem -Recurse -Filter *.java (Join-Path $PROJ 'java') | ForEach-Object FullName
Run $JAVAC (@('-encoding', 'UTF-8', '--release', '8', '-Xlint:-options',
    '-classpath', $ANDROID_JAR, '-d', (Join-Path $OUT 'classes')) + $sources)

Write-Host '==> 4/6 d8 (dex)'
$classes = Get-ChildItem -Recurse -Filter *.class (Join-Path $OUT 'classes') | ForEach-Object FullName
Run (Join-Path $BT 'd8.bat') (@('--release', '--min-api', '26', '--lib', $ANDROID_JAR,
    '--output', (Join-Path $OUT 'dex')) + $classes)

Write-Host '==> 5/6 package dex + zipalign'
Copy-Item (Join-Path $OUT 'base.apk') (Join-Path $OUT 'unaligned.apk') -Force
$packer = @'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    if "classes.dex" in z.namelist():
        z.remove("classes.dex")
    z.write(dex, "classes.dex")
print("    dex packaged")
'@
$packerPath = Join-Path $env:TEMP 'jarvis_pack_dex.py'
Set-Content -Path $packerPath -Value $packer -Encoding ASCII
python $packerPath (Join-Path $OUT 'unaligned.apk') (Join-Path $OUT 'dex\classes.dex')
if ($LASTEXITCODE -ne 0) { throw 'packaging dex failed' }
Run (Join-Path $BT 'zipalign.exe') @('-f', '-p', '4', (Join-Path $OUT 'unaligned.apk'), (Join-Path $OUT 'aligned.apk'))

Write-Host '==> 6/6 sign'
if (-not (Test-Path $KEYSTORE)) {
    Run $KEYTOOL @('-genkeypair', '-keystore', $KEYSTORE, '-alias', 'hermesctl', '-keyalg', 'RSA',
        '-keysize', '2048', '-validity', '10950', '-storepass', 'hermesctl', '-keypass', 'hermesctl',
        '-dname', 'CN=Emad Jarvis, OU=Emad, O=Emad, L=Tehran, C=IR')
}
Run (Join-Path $BT 'apksigner.bat') @('sign', '--ks', $KEYSTORE, '--ks-key-alias', 'hermesctl',
    '--ks-pass', 'pass:hermesctl', '--key-pass', 'pass:hermesctl',
    '--out', (Join-Path $DIST 'jarvis.apk'), (Join-Path $OUT 'aligned.apk'))
Run (Join-Path $BT 'apksigner.bat') @('verify', (Join-Path $DIST 'jarvis.apk'))

$apk = Get-Item (Join-Path $DIST 'jarvis.apk')
Write-Host ''
Write-Host "BUILT: $($apk.FullName)  ($($apk.Length) bytes)"

if ($Install) {
    $env:ANDROID_ADB_SERVER_PORT = '5038'
    & adb connect 10.205.157.105:5555 | Out-Null
    & adb -s 10.205.157.105:5555 install -r $apk.FullName
    if ($LASTEXITCODE -ne 0) { throw 'install failed' }
    Write-Host 'INSTALLED on the phone'
}
