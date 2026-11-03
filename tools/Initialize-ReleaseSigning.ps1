<#
.SYNOPSIS
Creates the release identity once, outside the checkout, without showing secrets.
.DESCRIPTION
The PKCS12 password is random and protected with Windows DPAPI CurrentUser. Keep
the original Windows account and export a password-protected portable recovery
backup separately. A self-signed CN is an app update identity, not proof of a
verified publisher. Never replace this key after publishing an APK.
#>
[CmdletBinding()]
param(
    [string] $SigningDirectory = 'D:\Android\Signing\MobileHarness',
    [string] $JdkHome = 'D:\Android\Jdks\jdk-17.0.20.1+1'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
Assert-SigningPlatform
$target = Get-ExternalSigningPath $SigningDirectory
$storeFile = Join-Path $target 'mobileharness-release.p12'
$configFile = Join-Path $target 'signing.dpapi.json'
$certificateFile = Join-Path $target 'certificate.public.json'
$keytool = Join-Path $JdkHome 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) { throw 'JDK keytool was not found.' }
foreach ($file in @($storeFile, $configFile, $certificateFile)) {
    if (Test-Path -LiteralPath $file) { throw 'Signing artifacts already exist. Refusing to replace a release identity.' }
}
[void](New-PrivateSigningDirectory $target)
$random = [byte[]]::new(48)
$password = $null
$variable = 'MOBILEHARNESS_INITIALIZE_SIGNING_PASSWORD'
$previous = [Environment]::GetEnvironmentVariable($variable, 'Process')
try {
    [Security.Cryptography.RandomNumberGenerator]::Fill($random)
    $password = [Convert]::ToBase64String($random)
    # Persist protected credentials before key generation. If interrupted, retain
    # all artifacts for recovery; never silently regenerate an existing identity.
    Write-NewSigningJson -Path $configFile -Value ([ordered]@{
        schema_version = 1
        protection = 'WINDOWS_DPAPI_CURRENT_USER'
        store_file = $storeFile
        key_alias = 'mobileharness-release'
        password_dpapi = Protect-SigningPassword $password
    })
    [Environment]::SetEnvironmentVariable($variable, $password, 'Process')
    $keytoolOutput = & $keytool '-genkeypair' '-storetype' 'PKCS12' '-keystore' $storeFile '-alias' 'mobileharness-release' '-keyalg' 'RSA' '-keysize' '4096' '-sigalg' 'SHA256withRSA' '-validity' '36500' '-dname' 'CN=MobileHarness Release' '-storepass:env' $variable '-keypass:env' $variable 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed. Protected credentials and any partial key were preserved; do not rerun blindly.' }
    Set-PrivateSigningAcl -Path $storeFile
    $fingerprint = Get-ReleaseCertificateSha256 -Keytool $keytool -StoreFile $storeFile -Alias 'mobileharness-release' -Password $password
    Write-NewSigningJson -Path $certificateFile -Value ([ordered]@{
        schema_version = 1
        alias = 'mobileharness-release'
        certificate_sha256 = $fingerprint
        key_algorithm = 'RSA-4096'
        signature_algorithm = 'SHA256withRSA'
        created_utc = [DateTime]::UtcNow.ToString('o')
        backup_status = 'PORTABLE_RECOVERY_NOT_YET_EXPORTED'
    })
    Write-Output "Signing configuration: $configFile"
    Write-Output "Release certificate SHA-256: $fingerprint"
    Write-Output 'Portable recovery backup is still required. Run Export-ReleaseSigningRecovery.ps1 interactively.'
} finally {
    [Environment]::SetEnvironmentVariable($variable, $previous, 'Process')
    [Array]::Clear($random, 0, $random.Length)
    $password = $null
    $keytoolOutput = $null
}
