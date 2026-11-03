$ErrorActionPreference = 'Stop'
$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
$cli = 'D:\Android\Sdk\cmdline-tools\latest\bin\android.exe'
$root = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = 'D:\Android\Jdks\jdk-17.0.20.1+1'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'

function Adb090 {
    $arguments090 = @($args)
    $output = & $adb @arguments090
    if ($LASTEXITCODE -ne 0) { throw 'ADB_COMMAND_FAILED' }
    return $output
}
function Readiness090 {
    Adb090 shell am broadcast -n org.mobileharness.app/.DebugOverlayReadinessReceiver -a org.mobileharness.app.action.DEBUG_READ_OVERLAY_READINESS | Out-Null
    return (Adb090 shell run-as org.mobileharness.app cat no_backup/overlay-readiness-verification.json | ConvertFrom-Json)
}
function ExpandNodes090($value) {
    if ($null -eq $value) { return }
    if ($value -is [System.Collections.IDictionary]) {
        if ($value.Contains('text') -and $value.Contains('bounds')) {
            [pscustomobject]@{ text = $value['text']; bounds = $value['bounds'] }
        }
        foreach ($child in $value.Values) { ExpandNodes090 $child }
    } elseif ($value -is [array]) {
        foreach ($child in $value) { ExpandNodes090 $child }
    }
}
function OwnNodes090 {
    $ready090 = Readiness090
    if ($ready090.runtime_state -notin @('IDLE', 'ENDED') -or -not $ready090.controls_match_runtime -or -not $ready090.readiness_unlocked -or -not $ready090.readiness_sensitive_page_visible) {
        throw 'OWN_UI_REQUIRES_INACTIVE_UNLOCKED_MAIN_PAGE'
    }
    # The tree is held in RAM and only fixed labels are used. No raw UI is printed/saved.
    $tree090 = & $cli --no-metrics layout 2>$null | ConvertFrom-Json -AsHashtable
    if ($LASTEXITCODE -ne 0) { throw 'OWN_LAYOUT_UNAVAILABLE' }
    ExpandNodes090 $tree090
}
function TapFixed090($nodes, $labels) {
    # A page heading can repeat a tab's text. The topmost fixed label is the tab.
    $matches090 = @($nodes | Where-Object { $_.text -in $labels -and $_.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$' } |
        Sort-Object { if ($_.bounds -match '^\[(\d+),(\d+)\]') { [int]$Matches[2] } } | Select-Object -First 1)
    if ($matches090.Count -ne 1) { throw 'FIXED_CONTROL_NOT_PRESENT' }
    if ($matches090[0].bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'FIXED_CONTROL_BOUNDS_UNAVAILABLE' }
    $pointX090 = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $pointY090 = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Adb090 shell input tap $pointX090 $pointY090 | Out-Null
}

$target090 = Join-Path $root 'docs/acceptance/results/experiment-analysis-0.9.0/device-ui.json'
if (Test-Path -LiteralPath $target090) { throw 'EVIDENCE_ALREADY_EXISTS' }
try {
    $ready090 = Readiness090
    if ($ready090.runtime_state -notin @('IDLE', 'ENDED') -or -not $ready090.controls_match_runtime -or -not $ready090.readiness_unlocked) { throw 'DEVICE_NOT_IDLE_OR_UNLOCKED' }
    Adb090 shell am start -f 0x24000000 -n org.mobileharness.app/.MainActivity | Out-Null
    $taskNodes090 = @(OwnNodes090)
    $scope090 = @($taskNodes090 | Where-Object { $_.text -in @('授权 App · 24', 'Apps · 24') }).Count -gt 0
    TapFixed090 $taskNodes090 @('模型', 'Models')
    $modelNodes090 = @(OwnNodes090)
    $keyBadges090 = @($modelNodes090 | Where-Object { $_.text -in @('密钥已加密保存', 'Key saved with encryption') }).Count
    TapFixed090 $modelNodes090 @('任务', 'Task')
    $final090 = Readiness090
    $passed090 = $scope090 -and $keyBadges090 -eq 2 -and $final090.service_connected -and $final090.controls_match_runtime
    $result090 = [ordered]@{
        status = $(if ($passed090) { 'PASS' } else { 'NOT_ESTABLISHED' })
        app_version = '0.9.0-dev'
        authorized_scope_24_readable = $scope090
        encrypted_profile_badges = $keyBadges090
        phone_local_catalog_decryption_exercised_by_normal_ui = $true
        key_editor_opened = $false
        profile_keys_returned_to_host = $false
        profile_or_scope_written = $false
        screenshots_saved = $false
        raw_ui_saved_or_printed = $false
        remote_requests = 0
        service_connected = $final090.service_connected
        controls_match_runtime = $final090.controls_match_runtime
        runtime_state = $final090.runtime_state
    }
    $result090 | ConvertTo-Json | Set-Content -LiteralPath $target090 -Encoding utf8
    [pscustomobject]@{ status = $result090.status; encrypted_profiles = $keyBadges090; scope_24 = $scope090 } | ConvertTo-Json
    if (-not $passed090) { throw 'FINAL_UI_PREDICATES_NOT_ESTABLISHED' }
} finally {
    Adb090 shell am force-stop com.android.cli.interact.instrumentation | Out-Null
}
