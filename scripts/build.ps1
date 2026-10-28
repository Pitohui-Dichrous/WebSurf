[CmdletBinding()]
param([Parameter(Position=0,ValueFromRemainingArguments=$true)][string[]]$Tasks=@('assembleDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolCandidates = @((Join-Path $projectRoot '.tools'), 'E:\MediaMaster\.tools')
foreach ($candidate in $toolCandidates) {
    $jdk = Get-ChildItem -LiteralPath $candidate -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk -and -not $env:JAVA_HOME) { $env:JAVA_HOME = $jdk.FullName }
    $sdk = Join-Path $candidate 'android-sdk'
    if ((Test-Path (Join-Path $sdk 'platforms\android-35\android.jar')) -and -not $env:ANDROID_HOME) { $env:ANDROID_HOME = $sdk }
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a Java 17 JDK.' }
if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME to an Android SDK with platform 35.' }
if (-not $env:GRADLE_USER_HOME) {
    $sharedCache = 'E:\MediaMaster\.tools\gradle-user-home'
    $env:GRADLE_USER_HOME = if(Test-Path $sharedCache){$sharedCache}else{Join-Path $projectRoot '.tools\gradle-user-home'}
}
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
Push-Location $projectRoot
try { & .\gradlew.bat @Tasks --console=plain --no-parallel; $buildResult=$LASTEXITCODE }
finally { Pop-Location }
exit $buildResult
