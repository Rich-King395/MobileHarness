<#
.SYNOPSIS
Checks local source/build/log files for the current signing password in memory.
.DESCRIPTION
Run after the release build has finished. Only boolean results are written.
Does not read private key bytes, change the signing identity, or access a phone.
The scan checks literal UTF-8/UTF-16 and common JSON-escaped password forms;
it is not a general proof that arbitrary encrypted or transformed data is absent.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string[]] $BuildLog,
    [string] $SigningConfig = 'D:\Android\Signing\MobileHarness\signing.dpapi.json',
    [string] $GradleUserHome = 'D:\Android\GradleUserHome\MobileHarness',
    [int] $RecentHours = 24
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
Assert-SigningPlatform
if ($RecentHours -lt 1 -or $RecentHours -gt 168) { throw 'RecentHours must be between 1 and 168.' }
if (-not ('MobileHarness.SigningLeakProbe' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
namespace MobileHarness {
    public static class SigningLeakProbe {
        public static bool Contains(Stream source, byte[][] patterns, int chunkSize) {
            int overlap = 0;
            foreach (byte[] pattern in patterns) {
                if (pattern.Length == 0) throw new ArgumentException();
                overlap = Math.Max(overlap, pattern.Length - 1);
            }
            byte[] buffer = new byte[chunkSize + overlap];
            try {
                int carry = 0;
                while (true) {
                    int count = source.Read(buffer, carry, chunkSize);
                    if (count == 0) return false;
                    int available = carry + count;
                    foreach (byte[] pattern in patterns) {
                        if (buffer.AsSpan(0, available).IndexOf(pattern.AsSpan()) >= 0) return true;
                    }
                    carry = Math.Min(overlap, available);
                    Buffer.BlockCopy(buffer, available - carry, buffer, 0, carry);
                }
            } finally { Array.Clear(buffer, 0, buffer.Length); }
        }
        public static bool ContainsFile(string path, byte[][] patterns) {
            using (FileStream source = new FileStream(path, FileMode.Open, FileAccess.Read,
                    FileShare.ReadWrite | FileShare.Delete)) {
                return Contains(source, patterns, 131072);
            }
        }
    }
}
'@
}
$result = [ordered]@{
    scanner_boundary_self_test_passed = $false
    source_scan_complete = $false
    source_plaintext_absent = $false
    project_gradle_scan_complete = $false
    project_gradle_plaintext_absent = $false
    app_build_scan_complete = $false
    app_build_plaintext_absent = $false
    build_log_scan_complete = $false
    build_log_plaintext_absent = $false
    recent_daemon_log_scan_complete = $false
    recent_daemon_log_plaintext_absent = $false
    apk_files_excluded = $true
    private_keystore_bytes_read = $false
    production_identity_changed = $false
    phone_accessed = $false
}
$credential = $null
$patterns = $null
$variants = $null
$failed = $false

function Test-FileGroup([string] $Name, [string[]] $Paths, [bool] $EnumerationComplete) {
    $complete = $EnumerationComplete
    $absent = $true
    foreach ($path in $Paths) {
        try {
            $item = Get-Item -LiteralPath $path -Force
            if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                $complete = $false
                continue
            }
            if ($item.Extension -ieq '.apk') { continue }
            if ([MobileHarness.SigningLeakProbe]::ContainsFile($item.FullName, $script:patterns)) { $absent = $false }
        } catch { $complete = $false }
    }
    $script:result[$Name + '_scan_complete'] = $complete
    $script:result[$Name + '_plaintext_absent'] = $complete -and $absent
}

function Get-ScanFiles([string] $Directory) {
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) { return @() }
    return @(Get-ChildItem -LiteralPath $Directory -File -Recurse -Force | ForEach-Object FullName)
}

try {
    $sample = [Text.Encoding]::UTF8.GetBytes('prefix--cross-boundary-token--suffix')
    $testPatterns = [byte[][]]::new(1)
    $testPatterns[0] = [Text.Encoding]::UTF8.GetBytes('cross-boundary-token')
    $testStream = [IO.MemoryStream]::new($sample, $false)
    try { $positive = [MobileHarness.SigningLeakProbe]::Contains($testStream, $testPatterns, 5) }
    finally { $testStream.Dispose() }
    $testStream = [IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes('no matching data'), $false)
    try { $negative = [MobileHarness.SigningLeakProbe]::Contains($testStream, $testPatterns, 5) }
    finally { $testStream.Dispose() }
    $result.scanner_boundary_self_test_passed = $positive -and -not $negative
    if (-not $result.scanner_boundary_self_test_passed) { throw 'Scanner self-test failed.' }
    $project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    $sourceNames = @(& git -C $project -c core.quotepath=false ls-files --cached --others --exclude-standard 2>$null)
    $sourceComplete = $LASTEXITCODE -eq 0 -and $sourceNames.Count -gt 0
    $sourceFiles = @($sourceNames | ForEach-Object { [IO.Path]::GetFullPath((Join-Path $project $_)) })
    $cache = Join-Path $project 'android-app\.gradle'
    $build = Join-Path $project 'android-app\app\build'
    $cacheFiles = @(Get-ScanFiles $cache)
    $buildFiles = @(Get-ScanFiles $build)
    $daemon = Join-Path $GradleUserHome 'daemon'
    $cutoff = [DateTime]::UtcNow.AddHours(-$RecentHours)
    $daemonFiles = @(Get-ChildItem -LiteralPath $daemon -File -Recurse -Filter 'daemon-*.out.log' |
        Where-Object { $_.LastWriteTimeUtc -ge $cutoff } | ForEach-Object FullName)
    $credential = Read-ReleaseSigningCredential $SigningConfig
    $variants = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    [void]$variants.Add($credential.Password)
    foreach ($replacement in @(@('/', '\/'), @('+', '\u002b'), @('=', '\u003d'))) {
        foreach ($value in @($variants)) { [void]$variants.Add($value.Replace($replacement[0], $replacement[1])) }
    }
    $list = [Collections.Generic.List[byte[]]]::new()
    foreach ($variant in $variants) {
        foreach ($encoding in @([Text.Encoding]::UTF8, [Text.Encoding]::Unicode, [Text.Encoding]::BigEndianUnicode)) {
            $list.Add($encoding.GetBytes($variant))
        }
    }
    $patterns = $list.ToArray()
    Test-FileGroup 'source' $sourceFiles $sourceComplete
    Test-FileGroup 'project_gradle' $cacheFiles ((Test-Path -LiteralPath $cache -PathType Container) -and $cacheFiles.Count -gt 0)
    Test-FileGroup 'app_build' $buildFiles ((Test-Path -LiteralPath $build -PathType Container) -and $buildFiles.Count -gt 0)
    Test-FileGroup 'build_log' $BuildLog ($BuildLog.Count -gt 0)
    Test-FileGroup 'recent_daemon_log' $daemonFiles ($daemonFiles.Count -gt 0)
} catch { $failed = $true }
finally {
    $credential = $null
    $variants = $null
    if ($null -ne $patterns) {
        foreach ($pattern in $patterns) { [Array]::Clear($pattern, 0, $pattern.Length) }
    }
    $patterns = $null
}
$result['all_checks_passed'] = -not $failed -and $result.scanner_boundary_self_test_passed -and
    $result.source_scan_complete -and $result.source_plaintext_absent -and
    $result.project_gradle_scan_complete -and $result.project_gradle_plaintext_absent -and
    $result.app_build_scan_complete -and $result.app_build_plaintext_absent -and
    $result.build_log_scan_complete -and $result.build_log_plaintext_absent -and
    $result.recent_daemon_log_scan_complete -and $result.recent_daemon_log_plaintext_absent
$result | ConvertTo-Json
if (-not $result.all_checks_passed) { exit 1 }
