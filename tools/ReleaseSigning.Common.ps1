# Shared helpers for local Windows release signing. Never enable a transcript or
# print returned credential objects. These tools do not access Android app data.
Set-StrictMode -Version Latest

function Assert-SigningPlatform {
    if ($PSVersionTable.PSVersion -lt [Version]'7.4' -or -not [OperatingSystem]::IsWindows()) {
        throw 'Release signing requires PowerShell 7.4 or newer on Windows.'
    }
    Add-Type -AssemblyName System.Security.Cryptography.ProtectedData
}

function Get-ExternalSigningPath([string] $Path) {
    if (-not [IO.Path]::IsPathFullyQualified($Path)) { throw 'Signing paths must be absolute.' }
    $fullPath = [IO.Path]::GetFullPath($Path)
    if ($fullPath.TrimEnd([char[]]'\/') -eq [IO.Path]::GetPathRoot($fullPath).TrimEnd([char[]]'\/')) {
        throw 'A drive root cannot be used as a signing directory.'
    }
    $resolved = $fullPath.TrimEnd([IO.Path]::DirectorySeparatorChar)
    $repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if ($resolved.Equals($repository, [StringComparison]::OrdinalIgnoreCase) -or
        $resolved.StartsWith($repository + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Signing material and recovery backups must stay outside this repository.'
    }
    # Reject junctions/symlinks in existing ancestors so a nominally external
    # directory cannot resolve back into the checkout or another location.
    $ancestor = $resolved
    while ($ancestor) {
        if (Test-Path -LiteralPath $ancestor) {
            $item = Get-Item -LiteralPath $ancestor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Signing paths must not contain a junction or symbolic link.'
            }
        }
        $parent = [IO.Path]::GetDirectoryName($ancestor)
        if ($parent -eq $ancestor) { break }
        $ancestor = $parent
    }
    return $resolved
}

function Set-PrivateSigningAcl([string] $Path, [switch] $Directory) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $system = [Security.Principal.SecurityIdentifier]::new([Security.Principal.WellKnownSidType]::LocalSystemSid, $null)
    $acl = if ($Directory) { [Security.AccessControl.DirectorySecurity]::new() } else { [Security.AccessControl.FileSecurity]::new() }
    $acl.SetOwner($identity)
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @($identity, $system)) {
        $rule = if ($Directory) {
            [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        } else {
            [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow')
        }
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function New-PrivateSigningDirectory([string] $Path) {
    $directory = Get-ExternalSigningPath $Path
    $created = -not (Test-Path -LiteralPath $directory)
    if ($created) {
        [void][IO.Directory]::CreateDirectory($directory)
    }
    if (-not (Get-Item -LiteralPath $directory).PSIsContainer) { throw 'Expected a signing directory.' }
    if (-not $created -and @(Get-ChildItem -LiteralPath $directory -Force).Count -gt 0) {
        # Never replace the permissions of a shared/pre-existing data directory.
        # Existing signing folders may be reused only when already private.
        $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $permittedSids = @($currentSid, 'S-1-5-18')
        $currentAcl = Get-Acl -LiteralPath $directory
        $rules = $currentAcl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])
        foreach ($rule in $rules) {
            if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -notin $permittedSids) {
                throw 'Use an empty dedicated signing/recovery folder; the selected nonempty folder has other ACL access.'
            }
        }
        return $directory
    }
    Set-PrivateSigningAcl -Path $directory -Directory
    return $directory
}

function Write-NewSigningJson([string] $Path, [object] $Value) {
    # CreateNew refuses replacement; the parent directory is restricted first.
    $jsonBytes = [Text.Encoding]::UTF8.GetBytes(($Value | ConvertTo-Json -Depth 8))
    $stream = $null
    try {
        $stream = [IO.FileStream]::new($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $stream.Write($jsonBytes, 0, $jsonBytes.Length)
        $stream.Flush($true)
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
        [Array]::Clear($jsonBytes, 0, $jsonBytes.Length)
    }
    Set-PrivateSigningAcl -Path $Path
}

function Protect-SigningPassword([string] $Password) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Password)
    $entropy = [Text.Encoding]::UTF8.GetBytes('MobileHarness.ReleaseSigning.v1')
    try {
        return [Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect(
            $bytes, $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser))
    } finally { [Array]::Clear($bytes, 0, $bytes.Length) }
}

function Read-ReleaseSigningCredential([string] $ConfigPath) {
    $configFile = Get-ExternalSigningPath $ConfigPath
    $config = Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json
    if ($config.schema_version -ne 1 -or $config.protection -ne 'WINDOWS_DPAPI_CURRENT_USER') {
        throw 'Unsupported signing configuration.'
    }
    $storeFile = Get-ExternalSigningPath ([string]$config.store_file)
    if ([IO.Path]::GetDirectoryName($storeFile) -ne [IO.Path]::GetDirectoryName($configFile)) {
        throw 'The signing keystore must be beside its protected configuration.'
    }
    if (-not (Test-Path -LiteralPath $storeFile -PathType Leaf)) { throw 'Release keystore is missing; do not generate a replacement.' }
    if ([string]$config.key_alias -notmatch '^[a-zA-Z0-9._-]+$') { throw 'Invalid signing alias.' }
    $encrypted = [Convert]::FromBase64String([string]$config.password_dpapi)
    $entropy = [Text.Encoding]::UTF8.GetBytes('MobileHarness.ReleaseSigning.v1')
    $bytes = $null
    try {
        $bytes = [Security.Cryptography.ProtectedData]::Unprotect($encrypted, $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        return [pscustomobject]@{
            StoreFile = $storeFile
            Alias = [string]$config.key_alias
            Password = [Text.Encoding]::UTF8.GetString($bytes)
        }
    } catch { throw 'Cannot unlock signing credentials for this Windows account. Use a portable recovery backup.' }
    finally { if ($null -ne $bytes) { [Array]::Clear($bytes, 0, $bytes.Length) } }
}

function Get-ReleaseCertificateSha256([string] $Keytool, [string] $StoreFile, [string] $Alias, [string] $Password) {
    $variable = 'MOBILEHARNESS_CERT_CHECK_PASSWORD'
    $previous = [Environment]::GetEnvironmentVariable($variable, 'Process')
    try {
        [Environment]::SetEnvironmentVariable($variable, $Password, 'Process')
        $output = & $Keytool '-J-Duser.language=en' '-J-Duser.country=US' '-list' '-v' '-storetype' 'PKCS12' '-keystore' $StoreFile '-alias' $Alias '-storepass:env' $variable 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'Certificate verification failed.' }
        $match = [regex]::Match(($output -join "`n"), 'SHA256:\s*([A-Fa-f0-9:]{95})')
        if (-not $match.Success) { throw 'Certificate SHA-256 fingerprint was not available.' }
        return $match.Groups[1].Value.Replace(':', '').ToLowerInvariant()
    } finally { [Environment]::SetEnvironmentVariable($variable, $previous, 'Process') }
}
