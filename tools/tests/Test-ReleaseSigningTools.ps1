<#
.SYNOPSIS
Exercises disposable signing identities in a unique external temporary folder.
.DESCRIPTION
Never opens the default release signing directory, builds an APK or uses a phone.
Passwords remain in this isolated test process; only closed checks are printed.
#>
[CmdletBinding()]
param([string] $JdkHome = 'D:\Android\Jdks\jdk-17.0.20.1+1')
$ErrorActionPreference = 'Stop'
$tools = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $tools 'ReleaseSigning.Common.ps1')
Assert-SigningPlatform
$temporaryParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
$temporary = Join-Path $temporaryParent ('MobileHarness-signing-test-' + [Guid]::NewGuid().ToString('N'))
if (Test-Path -LiteralPath $temporary) { throw 'Disposable signing directory unexpectedly exists.' }
$source = Join-Path $temporary 'source'
$restored = Join-Path $temporary 'restored'
$backup = Join-Path $temporary 'recovery\identity.recovery.json'
$checks = [ordered]@{}
$failure = $null
$original = $null
$recovered = $null
$certificate = $null
$passphraseBytes = [byte[]]::new(32)
$variables = @('MOBILEHARNESS_INITIALIZE_SIGNING_PASSWORD', 'MOBILEHARNESS_CERT_CHECK_PASSWORD')
$previous = @{}
foreach ($variable in $variables) { $previous[$variable] = [Environment]::GetEnvironmentVariable($variable, 'Process') }

function Assert-Test([bool] $Condition, [string] $Code) {
    if (-not $Condition) { throw $Code }
    $script:checks[$Code] = $true
}

function Assert-Rejected([scriptblock] $Action, [string] $Code) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-Test $rejected $Code
}

# This replaces only the interactive prompt in the disposable test process.
# The production export/restore scripts still exercise their actual crypto/IO.
function global:Read-Host {
    param([string] $Prompt, [switch] $AsSecureString)
    if (-not $AsSecureString) { throw 'Signing test requires a secure prompt.' }
    ConvertTo-SecureString $global:MobileHarnessTestRecoveryPassphrase -AsPlainText -Force
}

try {
    foreach ($variable in $variables) { [Environment]::SetEnvironmentVariable($variable, 'test-existing-environment', 'Process') }
    [Security.Cryptography.RandomNumberGenerator]::Fill($passphraseBytes)
    $global:MobileHarnessTestRecoveryPassphrase = [Convert]::ToBase64String($passphraseBytes)
    [void](New-PrivateSigningDirectory $temporary)
    & (Join-Path $tools 'Initialize-ReleaseSigning.ps1') -SigningDirectory $source -JdkHome $JdkHome | Out-Null
    $original = Read-ReleaseSigningCredential (Join-Path $source 'signing.dpapi.json')
    $public = Get-Content -LiteralPath (Join-Path $source 'certificate.public.json') -Raw | ConvertFrom-Json
    $fingerprint = Get-ReleaseCertificateSha256 -Keytool (Join-Path $JdkHome 'bin\keytool.exe') `
        -StoreFile $original.StoreFile -Alias $original.Alias -Password $original.Password
    Assert-Test ($fingerprint -ceq $public.certificate_sha256) 'keygen_dpapi_and_public_fingerprint'
    $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new(
        $original.StoreFile, $original.Password, [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
    $rsa = [Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPrivateKey($certificate)
    try {
        Assert-Test ($rsa.KeySize -eq 4096 -and $certificate.HasPrivateKey -and
            ($certificate.NotAfter - $certificate.NotBefore).TotalDays -ge 365 * 25) 'private_rsa4096_and_long_validity'
    } finally { $rsa.Dispose() }
    $identityHash = (Get-FileHash -LiteralPath $original.StoreFile -Algorithm SHA256).Hash
    Assert-Rejected { & (Join-Path $tools 'Initialize-ReleaseSigning.ps1') -SigningDirectory $source -JdkHome $JdkHome } 'repeat_initialization_rejected'
    Assert-Test ((Get-FileHash -LiteralPath $original.StoreFile -Algorithm SHA256).Hash -ceq $identityHash) 'existing_key_unchanged'
    $allowedSids = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18')
    $privateAcl = $true
    foreach ($path in @($source, $original.StoreFile, (Join-Path $source 'signing.dpapi.json'))) {
        $acl = Get-Acl -LiteralPath $path
        foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
            if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -notin $allowedSids) { $privateAcl = $false }
        }
    }
    Assert-Test $privateAcl 'signing_acl_current_user_and_system_only'
    & (Join-Path $tools 'Export-ReleaseSigningRecovery.ps1') -OutputFile $backup `
        -SigningConfig (Join-Path $source 'signing.dpapi.json') -JdkHome $JdkHome | Out-Null
    $backupText = Get-Content -LiteralPath $backup -Raw
    Assert-Test (-not $backupText.Contains($original.Password) -and
        -not $backupText.Contains('store_password') -and -not $backupText.Contains('keystore_base64')) 'portable_backup_contains_no_plaintext_payload'
    Assert-Rejected { & (Join-Path $tools 'Export-ReleaseSigningRecovery.ps1') -OutputFile $backup `
        -SigningConfig (Join-Path $source 'signing.dpapi.json') -JdkHome $JdkHome } 'existing_backup_overwrite_rejected'
    & (Join-Path $tools 'Restore-ReleaseSigningRecovery.ps1') -InputFile $backup `
        -SigningDirectory $restored -JdkHome $JdkHome | Out-Null
    $recovered = Read-ReleaseSigningCredential (Join-Path $restored 'signing.dpapi.json')
    $restoredFingerprint = Get-ReleaseCertificateSha256 -Keytool (Join-Path $JdkHome 'bin\keytool.exe') `
        -StoreFile $recovered.StoreFile -Alias $recovered.Alias -Password $recovered.Password
    Assert-Test ($restoredFingerprint -ceq $fingerprint -and $recovered.Password -ceq $original.Password -and
        (Get-FileHash -LiteralPath $recovered.StoreFile -Algorithm SHA256).Hash -ceq $identityHash) 'portable_restore_preserves_exact_private_identity'
    Assert-Rejected { & (Join-Path $tools 'Restore-ReleaseSigningRecovery.ps1') -InputFile $backup `
        -SigningDirectory $restored -JdkHome $JdkHome } 'restored_identity_overwrite_rejected'
    $global:MobileHarnessTestRecoveryPassphrase += 'wrong'
    $wrongDestination = Join-Path $temporary 'wrong-passphrase'
    Assert-Rejected { & (Join-Path $tools 'Restore-ReleaseSigningRecovery.ps1') -InputFile $backup `
        -SigningDirectory $wrongDestination -JdkHome $JdkHome } 'wrong_passphrase_rejected'
    Assert-Test (-not (Test-Path -LiteralPath $wrongDestination)) 'wrong_passphrase_writes_no_identity'
    $global:MobileHarnessTestRecoveryPassphrase = [Convert]::ToBase64String($passphraseBytes)
    $tampered = $backupText | ConvertFrom-Json
    $ciphertext = [Convert]::FromBase64String($tampered.ciphertext)
    $ciphertext[0] = $ciphertext[0] -bxor 1
    $tampered.ciphertext = [Convert]::ToBase64String($ciphertext)
    $tamperedFile = Join-Path $temporary 'recovery\tampered.recovery.json'
    Write-NewSigningJson -Path $tamperedFile -Value $tampered
    $tamperedDestination = Join-Path $temporary 'tampered'
    Assert-Rejected { & (Join-Path $tools 'Restore-ReleaseSigningRecovery.ps1') -InputFile $tamperedFile `
        -SigningDirectory $tamperedDestination -JdkHome $JdkHome } 'tampered_backup_rejected'
    Assert-Test (-not (Test-Path -LiteralPath $tamperedDestination)) 'tampered_backup_writes_no_identity'
    Assert-Rejected { Get-ExternalSigningPath (Join-Path $tools '..\forbidden-signing-test') } 'repository_signing_path_rejected'
    Assert-Test (@($variables | Where-Object {
        [Environment]::GetEnvironmentVariable($_, 'Process') -cne 'test-existing-environment'
    }).Count -eq 0) 'password_environment_restored'
} catch {
    # Do not serialize exception objects, command output, paths or credentials.
    $failure = 'DISPOSABLE_SIGNING_WORKFLOW_FAILED'
} finally {
    if ($null -ne $certificate) { $certificate.Dispose() }
    $original = $null
    $recovered = $null
    $global:MobileHarnessTestRecoveryPassphrase = $null
    Remove-Item -LiteralPath 'Function:\Read-Host' -ErrorAction SilentlyContinue
    [Array]::Clear($passphraseBytes, 0, $passphraseBytes.Length)
    foreach ($variable in $variables) { [Environment]::SetEnvironmentVariable($variable, $previous[$variable], 'Process') }
    # The only recursively removed tree is this run's verified random temp path.
    $resolvedTemporary = [IO.Path]::GetFullPath($temporary)
    if ($resolvedTemporary.StartsWith($temporaryParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedTemporary) -match '^MobileHarness-signing-test-[a-f0-9]{32}$') {
        if (Test-Path -LiteralPath $resolvedTemporary) { Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force }
    } else { $failure = 'DISPOSABLE_DIRECTORY_CLEANUP_REFUSED' }
}
$checks['disposable_key_material_removed'] = -not (Test-Path -LiteralPath $temporary)
$result = [ordered]@{
    status = if ($null -eq $failure -and $checks['disposable_key_material_removed']) { 'PASS' } else { 'FAIL' }
    checks = $checks
    production_signing_identity_accessed = $false
    phone_accessed = $false
    apk_built_or_installed = $false
}
if ($failure) { $result['reason'] = $failure }
$result | ConvertTo-Json -Depth 4
if ($result.status -ne 'PASS') { exit 1 }
