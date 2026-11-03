<#
.SYNOPSIS
Unlocks the local release identity for one Gradle invocation, then restores env.
.DESCRIPTION
Does not install, upload, change a debug key, or access experiment records. Do
not run under Start-Transcript, Gradle --debug, --info or --scan. Signing secrets
are process environment values, never command-line arguments or project files.
#>
[CmdletBinding()]
param(
    [string] $SigningConfig = 'D:\Android\Signing\MobileHarness\signing.dpapi.json',
    [string] $JdkHome = 'D:\Android\Jdks\jdk-17.0.20.1+1',
    [string] $GradleUserHome = 'D:\Android\GradleUserHome\MobileHarness',
    [switch] $Offline
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
Assert-SigningPlatform
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\android-app'))
$wrapper = Join-Path $project 'gradlew.bat'
if (-not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\java.exe') -PathType Leaf)) { throw 'Configured JDK was not found.' }
if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) { throw 'Gradle wrapper was not found.' }
$credential = Read-ReleaseSigningCredential $SigningConfig
$variables = @('JAVA_HOME', 'JAVA_TOOL_OPTIONS', 'GRADLE_USER_HOME', 'MOBILEHARNESS_RELEASE_STORE_FILE', 'MOBILEHARNESS_RELEASE_STORE_PASSWORD', 'MOBILEHARNESS_RELEASE_KEY_ALIAS', 'MOBILEHARNESS_RELEASE_KEY_PASSWORD')
$previous = @{}
foreach ($variable in $variables) { $previous[$variable] = [Environment]::GetEnvironmentVariable($variable, 'Process') }
$locationChanged = $false
try {
    $env:JAVA_HOME = $JdkHome
    $env:GRADLE_USER_HOME = $GradleUserHome
    # A short location avoids Windows Unix-domain socket path limits in Gradle.
    $socketDirectory = Join-Path $GradleUserHome 'socket-tmp'
    [void][IO.Directory]::CreateDirectory($socketDirectory)
    $socketOption = '"-Djdk.net.unixdomain.tmpdir=' + $socketDirectory + '"'
    $env:JAVA_TOOL_OPTIONS = (@($previous['JAVA_TOOL_OPTIONS'], $socketOption) | Where-Object { $_ }) -join ' '
    $env:MOBILEHARNESS_RELEASE_STORE_FILE = $credential.StoreFile
    $env:MOBILEHARNESS_RELEASE_STORE_PASSWORD = $credential.Password
    $env:MOBILEHARNESS_RELEASE_KEY_ALIAS = $credential.Alias
    $env:MOBILEHARNESS_RELEASE_KEY_PASSWORD = $credential.Password
    Push-Location -LiteralPath $project
    $locationChanged = $true
    $arguments = @('--no-configuration-cache', '--no-daemon', ':app:assembleRelease')
    if ($Offline) { $arguments += '--offline' }
    & $wrapper @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed. Signing environment has been cleared; the release identity was retained.' }
} finally {
    if ($locationChanged) { Pop-Location }
    foreach ($variable in $variables) { [Environment]::SetEnvironmentVariable($variable, $previous[$variable], 'Process') }
    $credential = $null
}
