# Build the Hermes Control APK with the Android SDK tools only (no Gradle).
# PowerShell twin of build.sh for Windows hosts where MSYS/git-bash is unavailable
# (the sandbox denies the MSYS runtime): same steps, same output path.
#   pwsh -File build.ps1
$ErrorActionPreference = 'Stop'

$SDK = 'C:\Users\Public\Android\sdk'
$BT = Join-Path $SDK 'build-tools\35.0.0'
$ANDROID_JAR = Join-Path $SDK 'platforms\android-35\android.jar'
$PROJ = $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.16.8-hotspot' }
$JAVAC = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$KEYTOOL = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
$OUT = Join-Path $PROJ 'build'
$DIST = Join-Path $PROJ 'dist'

Remove-Item -Recurse -Force (Join-Path $PROJ 'build') -ErrorAction SilentlyContinue
foreach ($d in 'res', 'classes', 'dex', 'gen') { New-Item -ItemType Directory -Force -Path (Join-Path $OUT $d) | Out-Null }
New-Item -ItemType Directory -Force -Path $DIST | Out-Null

function Run($exe, $arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe failed with exit code $LASTEXITCODE" }
}

Write-Host '==> 1/6 aapt2 compile'
Run (Join-Path $BT 'aapt2.exe') @('compile', '--dir', (Join-Path $PROJ 'res'), '-o', (Join-Path $OUT 'res\resources.zip'))

Write-Host '==> 2/6 aapt2 link (manifest + resources + assets)'
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
# --release 8 takes java.* from the JDK's Java 8 API: android.jar has no LambdaMetafactory,
# which javac needs to compile lambdas. d8 desugars them for Android afterwards.
Run $JAVAC (@('-encoding', 'UTF-8', '--release', '8', '-Xlint:-options',
    '-classpath', $ANDROID_JAR, '-d', (Join-Path $OUT 'classes')) + $sources)

Write-Host '==> 4/6 d8 (dex)'
$classes = Get-ChildItem -Recurse -Filter *.class (Join-Path $OUT 'classes') | ForEach-Object FullName
Run (Join-Path $BT 'd8.bat') (@('--release', '--min-api', '26', '--lib', $ANDROID_JAR,
    '--output', (Join-Path $OUT 'dex')) + $classes)

Write-Host '==> 5/6 package dex into apk + zipalign'
Copy-Item (Join-Path $OUT 'base.apk') (Join-Path $OUT 'unaligned.apk') -Force
$packer = @'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    names = z.namelist()
    if "classes.dex" in names:
        z.remove("classes.dex")
    z.write(dex, "classes.dex")
print("    dex packaged")
'@
$packerPath = Join-Path $env:TEMP 'hermes_pack_dex.py'
Set-Content -Path $packerPath -Value $packer -Encoding ASCII
python $packerPath (Join-Path $OUT 'unaligned.apk') (Join-Path $OUT 'dex\classes.dex')
if ($LASTEXITCODE -ne 0) { throw 'packaging dex failed' }
Run (Join-Path $BT 'zipalign.exe') @('-f', '-p', '4', (Join-Path $OUT 'unaligned.apk'), (Join-Path $OUT 'aligned.apk'))

Write-Host '==> 6/6 sign'
$KEYSTORE = Join-Path $PROJ 'keystore.jks'
if (-not (Test-Path $KEYSTORE)) {
    Run $KEYTOOL @('-genkeypair', '-keystore', $KEYSTORE, '-alias', 'hermesctl', '-keyalg', 'RSA',
        '-keysize', '2048', '-validity', '10950', '-storepass', 'hermesctl', '-keypass', 'hermesctl',
        '-dname', 'CN=Hermes Control, OU=Emad, O=Emad, L=Tehran, C=IR')
    Write-Host '    created keystore'
}
Run (Join-Path $BT 'apksigner.bat') @('sign', '--ks', $KEYSTORE, '--ks-key-alias', 'hermesctl',
    '--ks-pass', 'pass:hermesctl', '--key-pass', 'pass:hermesctl',
    '--out', (Join-Path $DIST 'hermes-control.apk'), (Join-Path $OUT 'aligned.apk'))
Run (Join-Path $BT 'apksigner.bat') @('verify', '--print-certs', (Join-Path $DIST 'hermes-control.apk'))

$apk = Get-Item (Join-Path $DIST 'hermes-control.apk')
Write-Host ''
Write-Host "BUILT: $($apk.FullName)  ($($apk.Length) bytes)"
