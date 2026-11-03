param(
    [Parameter(Mandatory=$true)][ValidateSet('Workflow','Local')][string]$Kind,
    [Parameter(Mandatory=$true)][ValidateRange(1,10)][int]$Round
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adbTool = 'D:\Android\Sdk\platform-tools\adb.exe'
$resultRoot = Join-Path $projectRoot 'docs/acceptance/results/multiround-0.3.2-20261031'
New-Item -ItemType Directory -Path $resultRoot -Force | Out-Null
$prefix = if ($Kind -eq 'Workflow') { 'workflow' } else { 'local-launch' }
$destination = Join-Path $resultRoot ('{0}-{1:00}.json' -f $prefix,$Round)
if (Test-Path -LiteralPath $destination) { throw 'ROUND_ALREADY_ARCHIVED' }
function Invoke-Adb([string[]]$Arguments) {
    $lines = & $adbTool -d @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'ADB_COMMAND_FAILED' }
    return $lines
}
function Read-Record([string]$Name) {
    try {
        $raw = Invoke-Adb -Arguments @('shell','run-as','org.mobileharness.app','cat',('no_backup/'+$Name))
        return (($raw -join '') | ConvertFrom-Json)
    } catch { return $null }
}
$packagePath = Invoke-Adb -Arguments @('shell','pm','path','org.mobileharness.app')
$installedPath = ($packagePath | Select-Object -First 1).ToString().Replace('package:','').Trim()
if ($installedPath -notmatch '^/data/app/[A-Za-z0-9_~=/.-]+/base[.]apk$') { throw 'APK_PATH_UNEXPECTED' }
$hashLine = Invoke-Adb -Arguments @('shell','sha256sum',$installedPath)
$installedHash = (($hashLine -join '') -split '\s+')[0]
if ($installedHash -ne '3c77f81e6918a232688dc484cbcec5b6c9de3ae52bd1e2a4442b431d3d61f288') { throw 'APK_BASELINE_CHANGED' }
# Only closed diagnostic metadata is read. No layout, image, key, request or reply is requested.
$recordName = if ($Kind -eq 'Workflow') { 'full-workflow-verification.json' } else { 'auto-start-verification.json' }
$previous = Read-Record $recordName
if ($previous -and $previous.status -eq 'RUNNING') { throw 'PREVIOUS_DIAGNOSTIC_RUNNING' }
if ($previous -and $previous.status -eq 'UNCERTAIN') { throw 'PREVIOUS_NATIVE_TERMINAL_UNCONFIRMED' }
$previousId = if ($previous) { $previous.session_id } else { '' }
$oldRecordBytes = if ($previous) { $previous | ConvertTo-Json -Depth 10 -Compress } else { '' }

# Main CLEAR_TOP destroys only a fully finished old debug activity. No force-stop/reinstall.
Invoke-Adb -Arguments @('shell','am','start','-f','0x24000000','-n','org.mobileharness.app/.MainActivity') | Out-Null
Start-Sleep -Milliseconds 500
$component = if ($Kind -eq 'Workflow') { 'org.mobileharness.app/.DebugWorkflowVerificationActivity' } else { 'org.mobileharness.app/.DebugAutoStartVerificationActivity' }
Invoke-Adb -Arguments @('shell','am','start','-n',$component) | Out-Null
$timer = [Diagnostics.Stopwatch]::StartNew()
$limit = if ($Kind -eq 'Workflow') { 215 } else { 35 }
$fresh = $false
$record = $null
while ($timer.Elapsed.TotalSeconds -lt $limit) {
    Start-Sleep -Milliseconds 1000
    $candidate = Read-Record $recordName
    if (-not $candidate) { continue }
    if ($Kind -eq 'Workflow') {
        if ($candidate.session_id -and $candidate.session_id -ne $previousId) { $fresh = $true }
        if (-not $fresh) { continue }
        if ($candidate.status -eq 'RUNNING') { continue }
        if ($candidate.flags.controls_restored -eq $true -and $candidate.flags.service_flags_restored -eq $true -and
            $candidate.flags.native_terminal_confirmed -eq $true -and $candidate.state -eq 'ENDED') {
            $record = $candidate; break
        }
        if ($candidate.status -in @('UNCERTAIN','CANCELLED','FAIL','BLOCKED')) {
            $candidate | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $destination -Encoding utf8
            throw 'DIAGNOSTIC_STOPPED_REVIEW_REQUIRED'
        }
    } else {
        $candidateBytes = $candidate | ConvertTo-Json -Depth 10 -Compress
        if ($candidateBytes -ne $oldRecordBytes) { $fresh = $true }
        if (-not $fresh -or $candidate.status -eq 'RUNNING') { continue }
        $record = $candidate; break
    }
}
if (-not $record) { throw 'DIAGNOSTIC_TIMEOUT_REVIEW_REQUIRED' }
$record | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $destination -Encoding utf8
if ($Kind -eq 'Workflow') {
    if ($record.session_id -notmatch '^[0-9a-f-]{36}$') { throw 'SESSION_ID_INVALID' }
    $eventLines = Invoke-Adb -Arguments @('shell','run-as','org.mobileharness.app','cat',('no_backup/events/'+$record.session_id+'.jsonl'))
    $events = @($eventLines | ForEach-Object { $_ | ConvertFrom-Json })
    if (@($events | Where-Object event -eq 'session_ended').Count -ne 1) { throw 'END_EVENT_MISSING' }
    $eventDestination = Join-Path $resultRoot ('workflow-{0:00}.events.jsonl' -f $Round)
    $eventLines | Set-Content -LiteralPath $eventDestination -Encoding utf8
    [pscustomobject]@{round=$Round;status=$record.status;code=$record.code;gui=$record.actual_gui_calls;
        planner=$record.actual_planner_calls;render=$record.flags.fixture_static_background_rendered;
        mechanics=$record.flags.execution_contract_invariants;task_count_one=$record.flags.final_click_count_is_one;
        protocol_reason=$record.protocol_reason;elapsed_ms=$record.elapsed_ms;events=$events.Count} | ConvertTo-Json -Compress
    if ($record.flags.no_takeover_or_return -ne $true) { throw 'USER_INTERVENTION_REVIEW_REQUIRED' }
    if ($record.error_category -in @('DEVICE','COORDINATOR') -or
        $record.flags.execution_contract_invariants -ne $true -or
        $record.flags.gui_attempt_accounting_matches -ne $true) { throw 'EXECUTION_EVIDENCE_REVIEW_REQUIRED' }
} else {
    [pscustomobject]@{round=$Round;kind='LOCAL';status=$record.status;code=$record.code;
        render=$record.flags.fixture_static_background_rendered_before_tap;elapsed_ms=$record.elapsed_ms} | ConvertTo-Json -Compress
    if ($record.status -ne 'PASS' -or $record.flags.controls_restored -ne $true) { throw 'LOCAL_EVIDENCE_REVIEW_REQUIRED' }
}
Start-Sleep -Milliseconds 600 # Let the completed debug Activity job return before another entry.

