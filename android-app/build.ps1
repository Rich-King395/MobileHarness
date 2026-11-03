param(
    [string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest'),
    [string]$BuildJdk = 'D:\Android\Jdks\jdk-17.0.20.1+1',
    [string]$GradleCache = 'D:\Android\GradleUserHome\MobileHarness'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath (Join-Path $BuildJdk 'bin\java.exe'))) { throw 'Build JDK 17 was not found' }
$previousTaskJava = $env:JAVA_HOME
$previousTaskCache = $env:GRADLE_USER_HOME
Push-Location -LiteralPath $PSScriptRoot
try {
    $env:JAVA_HOME = $BuildJdk
    $env:GRADLE_USER_HOME = $GradleCache
    & (Join-Path $PSScriptRoot 'gradlew.bat') --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
} finally {
    $env:JAVA_HOME = $previousTaskJava
    $env:GRADLE_USER_HOME = $previousTaskCache
    Pop-Location
}
