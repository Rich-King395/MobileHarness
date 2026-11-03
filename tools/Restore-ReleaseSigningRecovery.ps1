<#
.SYNOPSIS
Restores a portable recovery backup into a new local Windows signing directory.
.DESCRIPTION
Never use this to replace an existing identity. The recovered certificate must
match its authenticated backup fingerprint. This does not install or build APKs.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $InputFile,
    [string] $SigningDirectory = 'D:\Android\Signing\MobileHarness',
    [string] $JdkHome = 'D:\Android\Jdks\jdk-17.0.20.1+1'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
Assert-SigningPlatform
$source = Get-ExternalSigningPath $InputFile
$target = Get-ExternalSigningPath $SigningDirectory
$configFile = Join-Path $target 'signing.dpapi.json'
$storeFile = Join-Path $target 'mobileharness-release.p12'
$certificateFile = Join-Path $target 'certificate.public.json'
foreach ($file in @($storeFile, $configFile, $certificateFile)) {
    if (Test-Path -LiteralPath $file) { throw 'Signing artifacts already exist. Refusing to replace a release identity.' }
}
$keytool = Join-Path $JdkHome 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) { throw 'JDK keytool was not found.' }
$backup = Get-Content -LiteralPath $source -Raw | ConvertFrom-Json
if ($backup.format -ne 'MobileHarness.SigningRecovery.v1' -or $backup.cipher -ne 'AES-256-GCM' -or
    $backup.kdf -ne 'PBKDF2-SHA256' -or $backup.iterations -ne 600000) {
    throw 'Unsupported signing recovery format.'
}
$salt = [Convert]::FromBase64String($backup.salt)
$nonce = [Convert]::FromBase64String($backup.nonce)
$tag = [Convert]::FromBase64String($backup.tag)
$ciphertext = [Convert]::FromBase64String($backup.ciphertext)
if ($salt.Length -ne 32 -or $nonce.Length -ne 12 -or $tag.Length -ne 16) { throw 'Invalid signing recovery parameters.' }
$secure = $null
$pointer = [IntPtr]::Zero
$passphrase = $null
$passwordBytes = $null
$derived = $null
$plain = [byte[]]::new($ciphertext.Length)
$keyBytes = $null
$payload = $null
$aes = $null
try {
    $secure = Read-Host 'Recovery passphrase' -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $passphrase = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $passwordBytes = [Text.Encoding]::UTF8.GetBytes($passphrase)
    $derived = [Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2($passwordBytes, $salt, 600000, [Security.Cryptography.HashAlgorithmName]::SHA256, 32)
    $associatedData = [Text.Encoding]::UTF8.GetBytes('MobileHarness.SigningRecovery.v1')
    $aes = [Security.Cryptography.AesGcm]::new($derived, 16)
    try { $aes.Decrypt($nonce, $ciphertext, $tag, $plain, $associatedData) }
    catch { throw 'Recovery authentication failed. The passphrase is incorrect or the backup is damaged.' }
    $payload = [Text.Encoding]::UTF8.GetString($plain) | ConvertFrom-Json
    if ($payload.schema_version -ne 1 -or $payload.store_type -ne 'PKCS12' -or
        [string]$payload.key_alias -notmatch '^[a-zA-Z0-9._-]+$' -or
        [string]$payload.certificate_sha256 -notmatch '^[a-f0-9]{64}$' -or
        [string]$payload.store_password -cne [string]$payload.key_password) {
        throw 'Unsupported recovered signing identity.'
    }
    $keyBytes = [Convert]::FromBase64String($payload.keystore_base64)
    [void](New-PrivateSigningDirectory $target)
    Write-NewSigningJson -Path $configFile -Value ([ordered]@{
        schema_version = 1
        protection = 'WINDOWS_DPAPI_CURRENT_USER'
        store_file = $storeFile
        key_alias = [string]$payload.key_alias
        password_dpapi = Protect-SigningPassword ([string]$payload.store_password)
    })
    $stream = $null
    try {
        $stream = [IO.FileStream]::new($storeFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $stream.Write($keyBytes, 0, $keyBytes.Length)
        $stream.Flush($true)
    } finally { if ($null -ne $stream) { $stream.Dispose() } }
    Set-PrivateSigningAcl -Path $storeFile
    $fingerprint = Get-ReleaseCertificateSha256 -Keytool $keytool -StoreFile $storeFile -Alias $payload.key_alias -Password $payload.store_password
    if ($fingerprint -cne $payload.certificate_sha256) { throw 'Recovered certificate does not match the backup. Artifacts were retained for inspection.' }
    Write-NewSigningJson -Path $certificateFile -Value ([ordered]@{
        schema_version = 1
        alias = [string]$payload.key_alias
        certificate_sha256 = $fingerprint
        key_algorithm = 'RSA-4096'
        signature_algorithm = 'SHA256withRSA'
        restored_utc = [DateTime]::UtcNow.ToString('o')
    })
    Write-Output "Signing configuration restored: $configFile"
    Write-Output "Release certificate SHA-256: $fingerprint"
} finally {
    if ($null -ne $aes) { $aes.Dispose() }
    foreach ($buffer in @($plain, $derived, $passwordBytes, $keyBytes)) {
        if ($null -ne $buffer) { [Array]::Clear($buffer, 0, $buffer.Length) }
    }
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    if ($null -ne $secure) { $secure.Dispose() }
    $passphrase = $null
    $payload = $null
}
