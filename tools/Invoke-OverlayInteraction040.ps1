param([ValidateSet('ON','OFF')][string]$Variant='ON',[ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Attempt='attempt1',[string]$ArchiveDirectory='')
$ErrorActionPreference='Stop'
$adb='D:\Android\Sdk\platform-tools\adb.exe'
$root=Split-Path $PSScriptRoot -Parent
$archive=if($ArchiveDirectory){[IO.Path]::GetFullPath($ArchiveDirectory)}else{Join-Path $root 'docs/acceptance/results/ui-overlay-0.4.0'}
$resultsRoot=[IO.Path]::GetFullPath((Join-Path $root 'docs/acceptance/results'))+[IO.Path]::DirectorySeparatorChar
if(-not ([IO.Path]::GetFullPath($archive)+[IO.Path]::DirectorySeparatorChar).StartsWith($resultsRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'ARCHIVE_OUTSIDE_ACCEPTANCE_RESULTS'}
if((Test-Path -LiteralPath (Join-Path $archive ('physical-overlay-'+$Variant.ToLower()+'-'+$Attempt+'.json'))) -or
   (Test-Path -LiteralPath (Join-Path $archive ('physical-overlay-'+$Variant.ToLower()+'-'+$Attempt+'-failure.json')))) {throw 'ATTEMPT_ALREADY_RECORDED_USE_A_NEW_NAME'}
function Invoke-Adb040 {
    $commandArguments=@($args)
    $output=& $adb @commandArguments
    if($LASTEXITCODE -ne 0){throw 'ADB failed'}
    return $output
}
function Probe([string]$Command='probe') {
    $raw=(Invoke-Adb040 shell am broadcast -n org.mobileharness.app/.DebugOverlayInteractionReceiver -a org.mobileharness.app.action.DEBUG_OVERLAY_INTERACTION --es command $Command)-join "`n"
    $match=[regex]::Match($raw,'data="(.*)"')
    if(-not $match.Success){throw 'Own probe result unavailable'}
    return ($match.Groups[1].Value|ConvertFrom-Json)
}
function WaitStage([string]$Stage) {
    $until=[DateTime]::UtcNow.AddSeconds(45)
    do {
        $probe=Probe
        if($probe.stage -eq $Stage){return $probe}
        if($probe.status -in @('FAIL','BLOCKED','CANCELLED','UNCERTAIN')){throw ('Verification rejected: '+$probe.code)}
        Start-Sleep -Milliseconds 100
    } while([DateTime]::UtcNow -lt $until)
    throw ('Own verification stage timeout: '+$Stage)
}
function TapOwned([string]$Control) {
    # Geometry exists only in memory and comes from our own overlay Views, including in MANUAL.
    $probe=Probe
    $point=$probe.geometry.$Control
    if($null -eq $point){throw ('Own overlay control unavailable: '+$Control)}
    # An expansion changes WindowManager bounds asynchronously. Read our own View
    # center twice after layout; never click stale pre-layout command geometry.
    Start-Sleep -Milliseconds 180
    $fresh=Probe
    $confirmed=$fresh.geometry.$Control
    if($null -eq $confirmed){throw ('Own overlay control disappeared: '+$Control)}
    if($point.center_x -ne $confirmed.center_x -or $point.center_y -ne $confirmed.center_y){
        Start-Sleep -Milliseconds 180
        $stable=(Probe).geometry.$Control
        if($null -eq $stable -or $stable.center_x -ne $confirmed.center_x -or $stable.center_y -ne $confirmed.center_y){throw 'Own layout did not settle'}
        $confirmed=$stable
    }
    $point=$confirmed
    $x=[int]$point.center_x; $y=[int]$point.center_y
    if($x -lt 0 -or $y -lt 0 -or $x -gt 1080 -or $y -gt 2280){throw 'Own control outside verified portrait display'}
    Invoke-Adb040 shell input tap ([string]$x) ([string]$y)|Out-Null
}
try {
    Invoke-Adb040 shell am force-stop com.android.cli.interact.instrumentation|Out-Null
    $existing=Probe
    if($existing.status -eq 'RUNNING'){throw 'Existing debug scenario is active; abort and finish cleanup before relaunch'}
    Invoke-Adb040 shell am start -n org.mobileharness.app/.DebugOverlayInteractionActivity --ez harness $(if($Variant -eq 'ON'){'true'}else{'false'})|Out-Null
    $ready=WaitStage 'WAIT_TAKEOVER'
    if($ready.state -ne 'AUTO'){throw 'Expected AUTO before takeover'}
    TapOwned 'ball'
    TapOwned 'TAKEOVER'
    $pausing=WaitStage 'WAIT_TERMINAL_RELEASE'
    if($pausing.state -ne 'PAUSING' -or $pausing.capture_enabled -or $pausing.geometry.RETURN){throw 'Pausing contract mismatch'}
    $release=Probe 'release_terminal'
    if($release.status -ne 'RELEASED'){throw 'Real terminal release rejected'}
    $manual=WaitStage 'WAIT_RETURN'
    if($manual.state -ne 'MANUAL' -or $manual.capture_enabled){throw 'Manual contract mismatch'}
    # No target layout, screenshot, package or node inspection during MANUAL.
    TapOwned 'ball'
    TapOwned 'RETURN'
    $resumed=WaitStage 'WAIT_STOP'
    if($resumed.state -ne 'AUTO'){throw 'Return did not resume AUTO'}
    TapOwned 'ball'
    $afterExpand=Probe
    if($afterExpand.geometry.TAKEOVER -or $afterExpand.geometry.RETURN){throw 'Unexpected second handoff command'}
    TapOwned 'STOP'
    $done=WaitStage 'COMPLETED'
    $record=((Invoke-Adb040 shell run-as org.mobileharness.app cat no_backup/overlay-interaction-verification.json)-join '')|ConvertFrom-Json
    $record|ConvertTo-Json -Depth 15|Set-Content -LiteralPath (Join-Path $archive ('physical-overlay-'+$Variant.ToLower()+'-'+$Attempt+'.json')) -Encoding UTF8
    if($record.status -ne 'PASS' -or @($record.flags.PSObject.Properties|Where-Object {-not $_.Value}).Count){throw 'Verification flags failed'}
    [pscustomobject]@{variant=$Variant;status=$record.status;gui_synthetic=$record.gui_used;planner_synthetic=$record.planner_used;gui_network=$record.gui_network_requests;planner_network=$record.planner_network_requests;elapsed_ms=$record.elapsed_ms}|ConvertTo-Json -Compress
} catch {
    try { Probe 'abort'|Out-Null; Start-Sleep -Milliseconds 400
        $record=((Invoke-Adb040 shell run-as org.mobileharness.app cat no_backup/overlay-interaction-verification.json)-join '')|ConvertFrom-Json
        $record|ConvertTo-Json -Depth 15|Set-Content -LiteralPath (Join-Path $archive ('physical-overlay-'+$Variant.ToLower()+'-'+$Attempt+'-failure.json')) -Encoding UTF8
    } catch { }
    throw
}
