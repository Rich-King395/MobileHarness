<#
.SYNOPSIS
Interactively exports a portable encrypted backup of the release identity.
.DESCRIPTION
Run in a local PowerShell 7 terminal with no transcript. Choose a unique recovery
passphrase of at least 16 characters and keep it independently from the backup.
The backup includes the PKCS12 and its password, encrypted with AES-256-GCM using
PBKDF2-SHA256 (600,000 rounds). No Windows account is needed to decrypt a backup.
Never put the backup in this repository. Copy it to an independent offline store;
merely creating a second file on the development disk is not a durable backup.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $OutputFile,
    [string] $SigningConfig = 'D:\Android\Signing\MobileHarness\signing.dpapi.json',
    [string] $JdkHome = 'D:\Android\Jdks\jdk-17.0.20.1+1'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
Assert-SigningPlatform
$destination = Get-ExternalSigningPath $OutputFile
if (Test-Path -LiteralPath $destination) { throw 'Recovery destination exists. Refusing to overwrite.' }
# Require a dedicated existing/private folder and restrict it before any write.
$directory = [IO.Path]::GetDirectoryName($destination)
[void](New-PrivateSigningDirectory $directory)
$credential = Read-ReleaseSigningCredential $SigningConfig
$keytool = Join-Path $JdkHome 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) { throw 'JDK keytool was not found.' }
$fingerprint = Get-ReleaseCertificateSha256 -Keytool $keytool -StoreFile $credential.StoreFile -Alias $credential.Alias -Password $credential.Password
$first = $null
$second = $null
$firstPointer = [IntPtr]::Zero
$secondPointer = [IntPtr]::Zero
$passphrase = $null
$confirmation = $null
$plain = $null
$derived = $null
$passwordBytes = $null
$keyBytes = $null
$aes = $null
try {
    $first = Read-Host 'Recovery passphrase (at least 16 characters)' -AsSecureString
    $second = Read-Host 'Repeat recovery passphrase' -AsSecureString
    $firstPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($first)
    $secondPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($second)
    $passphrase = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($firstPointer)
    $confirmation = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secondPointer)
    if ($passphrase.Length -lt 16 -or $passphrase -cne $confirmation) { throw 'Recovery passphrases do not match or are shorter than 16 characters.' }
    $keyBytes = [IO.File]::ReadAllBytes($credential.StoreFile)
    $payload = [ordered]@{
        schema_version = 1
        key_alias = $credential.Alias
        certificate_sha256 = $fingerprint
        store_type = 'PKCS12'
        store_password = $credential.Password
        key_password = $credential.Password
        keystore_base64 = [Convert]::ToBase64String($keyBytes)
        created_utc = [DateTime]::UtcNow.ToString('o')
    }
    $plain = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Depth 4 -Compress))
    $salt = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $nonce = [Security.Cryptography.RandomNumberGenerator]::GetBytes(12)
    $tag = [byte[]]::new(16)
    $ciphertext = [byte[]]::new($plain.Length)
    $passwordBytes = [Text.Encoding]::UTF8.GetBytes($passphrase)
    $derived = [Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2($passwordBytes, $salt, 600000, [Security.Cryptography.HashAlgorithmName]::SHA256, 32)
    $associatedData = [Text.Encoding]::UTF8.GetBytes('MobileHarness.SigningRecovery.v1')
    $aes = [Security.Cryptography.AesGcm]::new($derived, 16)
    $aes.Encrypt($nonce, $plain, $ciphertext, $tag, $associatedData)
    Write-NewSigningJson -Path $destination -Value ([ordered]@{
        format = 'MobileHarness.SigningRecovery.v1'
        cipher = 'AES-256-GCM'
        kdf = 'PBKDF2-SHA256'
        iterations = 600000
        salt = [Convert]::ToBase64String($salt)
        nonce = [Convert]::ToBase64String($nonce)
        tag = [Convert]::ToBase64String($tag)
        ciphertext = [Convert]::ToBase64String($ciphertext)
    })
    Write-Output "Encrypted portable recovery backup: $destination"
    Write-Output 'Keep the recovery passphrase separately and copy the backup to an independent offline location.'
} finally {
    if ($null -ne $aes) { $aes.Dispose() }
    foreach ($buffer in @($plain, $derived, $passwordBytes, $keyBytes)) {
        if ($null -ne $buffer) { [Array]::Clear($buffer, 0, $buffer.Length) }
    }
    if ($firstPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($firstPointer) }
    if ($secondPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secondPointer) }
    if ($null -ne $first) { $first.Dispose() }
    if ($null -ne $second) { $second.Dispose() }
    $credential = $null
    $payload = $null
    $passphrase = $null
    $confirmation = $null
}
