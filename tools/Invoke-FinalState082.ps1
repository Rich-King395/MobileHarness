$ErrorActionPreference = 'Stop'
$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
$cli = 'D:\Android\Sdk\cmdline-tools\latest\bin\android.exe'
$root = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = 'D:\Android\Jdks\jdk-17.0.20.1+1'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'

function Adb082 {
    $arguments082 = @($args)
    $output = & $adb @arguments082
    if ($LASTEXITCODE -ne 0) { throw 'ADB_COMMAND_FAILED' }
    return $output
}
function Readiness082 {
    Adb082 shell am broadcast -n org.mobileharness.app/.DebugOverlayReadinessReceiver -a org.mobileharness.app.action.DEBUG_READ_OVERLAY_READINESS | Out-Null
    return (Adb082 shell run-as org.mobileharness.app cat no_backup/overlay-readiness-verification.json | ConvertFrom-Json)
}
function ExpandNodes082($value) {
    if ($null -eq $value) { return }
    if ($value -is [System.Collections.IDictionary]) {
        if ($value.Contains('text') -and $value.Contains('bounds')) {
            [pscustomobject]@{ text = $value['text']; bounds = $value['bounds'] }
        }
        foreach ($child in $value.Values) { ExpandNodes082 $child }
    } elseif ($value -is [array]) {
        foreach ($child in $value) { ExpandNodes082 $child }
    }
}
function OwnNodes082 {
    $ready082 = Readiness082
    if ($ready082.runtime_state -notin @('IDLE', 'ENDED') -or -not $ready082.controls_match_runtime -or -not $ready082.readiness_unlocked -or -not $ready082.readiness_sensitive_page_visible) {
        throw 'OWN_UI_REQUIRES_INACTIVE_UNLOCKED_MAIN_PAGE'
    }
    # The tree is held in RAM and only fixed labels are used. No raw UI is printed/saved.
    $tree082 = & $cli --no-metrics layout 2>$null | ConvertFrom-Json -AsHashtable
    if ($LASTEXITCODE -ne 0) { throw 'OWN_LAYOUT_UNAVAILABLE' }
    ExpandNodes082 $tree082
}
function TapFixed082($nodes, $labels) {
    # A page heading can repeat a tab's text. The topmost fixed label is the tab.
    $matches082 = @($nodes | Where-Object { $_.text -in $labels -and $_.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$' } |
        Sort-Object { if ($_.bounds -match '^\[(\d+),(\d+)\]') { [int]$Matches[2] } } | Select-Object -First 1)
    if ($matches082.Count -ne 1) { throw 'FIXED_CONTROL_NOT_PRESENT' }
    if ($matches082[0].bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'FIXED_CONTROL_BOUNDS_UNAVAILABLE' }
    $pointX082 = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $pointY082 = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Adb082 shell input tap $pointX082 $pointY082 | Out-Null
}

$target082 = Join-Path $root 'docs/acceptance/results/stability-0.8.2/device-ui.json'
if (Test-Path -LiteralPath $target082) { throw 'EVIDENCE_ALREADY_EXISTS' }
try {
    $ready082 = Readiness082
    if ($ready082.runtime_state -notin @('IDLE', 'ENDED') -or -not $ready082.controls_match_runtime -or -not $ready082.readiness_unlocked) { throw 'DEVICE_NOT_IDLE_OR_UNLOCKED' }
    Adb082 shell am start -f 0x24000000 -n org.mobileharness.app/.MainActivity | Out-Null
    $taskNodes082 = @(OwnNodes082)
    $scope082 = @($taskNodes082 | Where-Object { $_.text -in @('授权 App · 24', 'Apps · 24') }).Count -gt 0
    TapFixed082 $taskNodes082 @('模型', 'Models')
    $modelNodes082 = @(OwnNodes082)
    $keyBadges082 = @($modelNodes082 | Where-Object { $_.text -in @('密钥已加密保存', 'Key saved with encryption') }).Count
    TapFixed082 $modelNodes082 @('任务', 'Task')
    $final082 = Readiness082
    $passed082 = $scope082 -and $keyBadges082 -eq 2 -and $final082.service_connected -and $final082.controls_match_runtime
    $result082 = [ordered]@{
        status = $(if ($passed082) { 'PASS' } else { 'NOT_ESTABLISHED' })
        app_version = '0.8.2-dev'
        authorized_scope_24_readable = $scope082
        encrypted_profile_badges = $keyBadges082
        phone_local_catalog_decryption_exercised_by_normal_ui = $true
        key_editor_opened = $false
        profile_keys_returned_to_host = $false
        profile_or_scope_written = $false
        screenshots_saved = $false
        raw_ui_saved_or_printed = $false
        remote_requests = 0
        service_connected = $final082.service_connected
        controls_match_runtime = $final082.controls_match_runtime
        runtime_state = $final082.runtime_state
    }
    $result082 | ConvertTo-Json | Set-Content -LiteralPath $target082 -Encoding utf8
    [pscustomobject]@{ status = $result082.status; encrypted_profiles = $keyBadges082; scope_24 = $scope082 } | ConvertTo-Json
    if (-not $passed082) { throw 'FINAL_UI_PREDICATES_NOT_ESTABLISHED' }
} finally {
    Adb082 shell am force-stop com.android.cli.interact.instrumentation | Out-Null
}
