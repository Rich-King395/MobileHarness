param([ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Attempt='attempt3')
$ErrorActionPreference='Stop'
$adb='D:\Android\Sdk\platform-tools\adb.exe';$cli='D:\Android\Sdk\cmdline-tools\latest\bin\android.exe'
$archive=Join-Path (Split-Path $PSScriptRoot -Parent) 'docs/acceptance/results/ui-overlay-0.4.0'
if(Test-Path -LiteralPath (Join-Path $archive ('layout-scale-rotation-'+$Attempt+'.json'))){throw 'ATTEMPT_ALREADY_RECORDED_USE_A_NEW_NAME'}
$original=Get-Content -LiteralPath (Join-Path $archive 'layout-settings-before.json') -Raw|ConvertFrom-Json
$flags=[ordered]@{};$status='RUNNING';$failure=''
Remove-Item Env:ANDROID_CLI_LAYOUT_V1 -ErrorAction SilentlyContinue
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 "-Djdk.net.unixdomain.tmpdir=D:\Files\CityURAProject\Mobile Agent\MobileHarness\android-app\.gradle\socket-tmp"'
function AdbScale040 {$callArgs=@($args);$r=& $adb @callArgs;if($LASTEXITCODE -ne 0){throw 'ADB_FAILED'};return $r}
function NodesScale040($ns){foreach($n in $ns){$n;NodesScale040 $n.children}}
function RectScale040($n){if($null -eq $n -or $n.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){throw 'OWN_BOUNDS_UNAVAILABLE'};return @{l=[int]$Matches[1];t=[int]$Matches[2];r=[int]$Matches[3];b=[int]$Matches[4]}}
function OwnScale040 {
    $raw=& $cli --no-metrics layout 2>$null;if($LASTEXITCODE -ne 0){throw 'LAYOUT_UNAVAILABLE'}
    foreach($w in (($raw-join [Environment]::NewLine)|ConvertFrom-Json)){
        $n=@(NodesScale040 $w.content)
        if($w.'window-title' -match 'MobileHarness|org\.mobileharness\.app' -and @($n|Where-Object text -eq 'MobileHarness').Count){return @{window=$w;nodes=$n}}
    };throw 'OWN_MAIN_WINDOW_UNAVAILABLE'
}
function RequireScale040($name,[bool]$value){$flags[$name]=$value;if(-not $value){throw $name}}
try {
    AdbScale040 shell am broadcast -n org.mobileharness.app/.DebugOverlayReadinessReceiver -a org.mobileharness.app.action.DEBUG_READ_OVERLAY_READINESS|Out-Null
    $ready=((AdbScale040 shell run-as org.mobileharness.app cat no_backup/overlay-readiness-verification.json)-join '')|ConvertFrom-Json
    if($ready.runtime_state -notin @('IDLE','ENDED') -or -not $ready.controls_match_runtime -or -not $ready.readiness_unlocked){throw 'OWN_RUNTIME_NOT_IDLE_OR_UNLOCKED'}
    foreach($case in @('font_130','landscape')){
        # Diagnostic helper only: true landscape acceptance is not yet established.
        # Disconnect the developer observer and let teardown finish before config changes.
        AdbScale040 shell am force-stop com.android.cli.interact.instrumentation|Out-Null
        Start-Sleep -Milliseconds 600
        if($case -eq 'font_130'){AdbScale040 shell settings put system font_scale 1.3|Out-Null;AdbScale040 shell wm user-rotation lock 0|Out-Null}
        else{AdbScale040 shell settings put system font_scale $original.font_scale|Out-Null;AdbScale040 shell wm user-rotation lock 1|Out-Null}
        Start-Sleep -Milliseconds 650
        AdbScale040 shell am start -f 0x24000000 -n org.mobileharness.app/.MainActivity|Out-Null
        $own=OwnScale040;$win=RectScale040 $own.window
        $start=@($own.nodes|Where-Object {$_.text -eq '开始任务' -and -not $_.'off-screen'})|Select-Object -First 1
        RequireScale040 ($case+'_start_visible') ($null -ne $start)
        $startRect=RectScale040 $start
        RequireScale040 ($case+'_start_inside_window') ($startRect.l -ge $win.l -and $startRect.r -le $win.r -and $startRect.t -ge $win.t -and $startRect.b -le $win.b)
        $tabs=@($own.nodes|Where-Object {$_.text -in @('模型','记录')})
        $reason=@($own.nodes|Where-Object text -eq '请输入任务')|Select-Object -First 1
        # The intro may have scrolled off-screen across recreation. The fixed
        # navigation and footer still define a safe viewport for our form.
        $bodyTop=($tabs|ForEach-Object {(RectScale040 $_).b}|Measure-Object -Maximum).Maximum
        $bodyBottom=(RectScale040 $reason).t-8
        RequireScale040 ($case+'_positive_body_viewport') ($bodyBottom -gt $bodyTop+60)
        $reachable=$false
        for($step=0;$step -lt 10;$step++){
            $consent=@($own.nodes|Where-Object {$_.text -eq '同意将任务与必要截图发送至上述服务' -and -not $_.'off-screen'})|Select-Object -First 1
            if($consent){$r=RectScale040 $consent;if($r.t -ge $bodyTop -and $r.b -le $bodyBottom){$reachable=$true;break}}
            # Scrolling is needed only when contents overflow. Coordinates describe
            # our form viewport, never a model action or target-App correction.
            $x=[int](($win.l+$win.r)/2);$height=$bodyBottom-$bodyTop
            AdbScale040 shell input swipe ([string]$x) ([string][int]($bodyTop+$height*.8)) ([string]$x) ([string][int]($bodyTop+$height*.2)) 650|Out-Null
            $own=OwnScale040
        }
        RequireScale040 ($case+'_consent_reachable') $reachable
        if($case -eq 'font_130'){RequireScale040 'font_scale_130_applied' (((AdbScale040 shell settings get system font_scale)-join '').Trim() -eq '1.3')}
        else{RequireScale040 'landscape_orientation_applied' (($win.r-$win.l) -gt ($win.b-$win.t))}
    }
    $status='PASS'
}catch{$status='FAIL';$failure=$_.Exception.Message}
finally{
    AdbScale040 shell am force-stop com.android.cli.interact.instrumentation|Out-Null
    Start-Sleep -Milliseconds 600
    AdbScale040 shell settings put system font_scale $original.font_scale|Out-Null
    if($original.accelerometer_rotation -eq '1'){AdbScale040 shell wm user-rotation free|Out-Null}
    else{AdbScale040 shell wm user-rotation lock $original.user_rotation|Out-Null}
    AdbScale040 shell settings put system accelerometer_rotation $original.accelerometer_rotation|Out-Null
    AdbScale040 shell settings put system user_rotation $original.user_rotation|Out-Null
    Start-Sleep -Milliseconds 550
    AdbScale040 shell am start -f 0x24000000 -n org.mobileharness.app/.MainActivity|Out-Null
    $flags['font_setting_restored']=(((AdbScale040 shell settings get system font_scale)-join '').Trim() -eq $original.font_scale)
    $flags['rotation_setting_restored']=(((AdbScale040 shell settings get system user_rotation)-join '').Trim() -eq $original.user_rotation) -and (((AdbScale040 shell settings get system accelerometer_rotation)-join '').Trim() -eq $original.accelerometer_rotation)
    [pscustomobject]@{app_version='0.4.0-dev';status=$status;failure=$failure;flags=$flags;gui_network_requests=0;planner_network_requests=0}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $archive ('layout-scale-rotation-'+$Attempt+'.json')) -Encoding UTF8
    [pscustomobject]@{status=$status;checks=$flags.Count;failed_flags=@($flags.Values|Where-Object {-not $_}).Count;failure=$failure}|ConvertTo-Json -Compress
}
if($status -ne 'PASS'){throw 'SCALE_ROTATION_CHECK_FAILED'}
