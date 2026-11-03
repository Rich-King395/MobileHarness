param([ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Attempt='attempt1')
$ErrorActionPreference='Stop'
$adb='D:\Android\Sdk\platform-tools\adb.exe'
$cli='D:\Android\Sdk\cmdline-tools\latest\bin\android.exe'
$root=Split-Path $PSScriptRoot -Parent
$archive=Join-Path $root 'docs/acceptance/results/ui-overlay-0.4.0'
if(Test-Path -LiteralPath (Join-Path $archive ('task-ui-'+$Attempt+'.json'))){throw 'ATTEMPT_ALREADY_RECORDED_USE_A_NEW_NAME'}
$flags=[ordered]@{}
$status='RUNNING';$failure='';$draftOwned=$false
Remove-Item Env:ANDROID_CLI_LAYOUT_V1 -ErrorAction SilentlyContinue
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 "-Djdk.net.unixdomain.tmpdir=D:\Files\CityURAProject\Mobile Agent\MobileHarness\android-app\.gradle\socket-tmp"'
function Adb040 { $callArgs=@($args);$r=& $adb @callArgs;if($LASTEXITCODE -ne 0){throw 'ADB_FAILED'};return $r }
function Windows040 { $raw=& $cli --no-metrics layout 2>$null;if($LASTEXITCODE -ne 0){throw 'LAYOUT_UNAVAILABLE'};return (($raw -join [Environment]::NewLine)|ConvertFrom-Json) }
function Nodes040($items){foreach($n in $items){$n;Nodes040 $n.children}}
function Own040 {
    foreach($w in (Windows040)){
        $nodes=@(Nodes040 $w.content)
        # Compose sheets have an independent window title, not the Activity title.
        $knownSheet=@($nodes|Where-Object {$_.text -in @('运行设置','授权 App','此次远端发送')}).Count -gt 0
        if($w.'window-title' -match 'MobileHarness|org\.mobileharness\.app' -or $knownSheet){$nodes}
    }
}
function Rect040($n){if($null -eq $n -or $n.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){throw 'OWN_BOUNDS_MISSING'};return @{l=[int]$Matches[1];t=[int]$Matches[2];r=[int]$Matches[3];b=[int]$Matches[4]}}
function Tap040($n){$r=Rect040 $n;Adb040 shell input tap ([string][int](($r.l+$r.r)/2)) ([string][int](($r.t+$r.b)/2))|Out-Null}
function Text040([string]$pattern){@((Own040)|Where-Object {$_.text -match $pattern -and -not $_.'off-screen'})|Select-Object -First 1}
function ScrollText040([string]$pattern){
    for($i=0;$i -lt 5;$i++){
        $all=@(Own040);$n=@($all|Where-Object {$_.text -match $pattern -and -not $_.'off-screen'})|Select-Object -First 1
        if($n){return $n}
        $scroll=@($all|Where-Object {$_.interactions -contains 'SCROLLABLE' -and -not $_.'off-screen'})|Sort-Object { $r=Rect040 $_;$r.b-$r.t } -Descending|Select-Object -First 1
        $r=Rect040 $scroll;$height=$r.b-$r.t
        if($height -lt 100){throw 'OWN_SCROLL_AREA_TOO_SMALL'}
        Adb040 shell input swipe ([string][int](($r.l+$r.r)/2)) ([string][int]($r.t+$height*.8)) ([string][int](($r.l+$r.r)/2)) ([string][int]($r.t+$height*.2)) 650|Out-Null
    };throw 'OWN_LABEL_UNREACHABLE'
}
function Check040([string]$name,[bool]$value){$flags[$name]=$value;if(-not $value){throw $name.ToUpperInvariant()}}
function TaskEdit040([string]$Expected='') {
    $edits=@((Own040)|Where-Object {$_.class -match 'EditText' -and -not $_.'off-screen'})
    # A modal and its underlying activity can both appear in the accessibility tree.
    if($Expected -eq '__EMPTY__'){$edits=@($edits|Where-Object {[string]::IsNullOrEmpty($_.text)})}
    elseif($Expected){$edits=@($edits|Where-Object text -eq $Expected)}
    elseif($edits.Count -gt 1){$edits=@($edits|Where-Object {$_.state -contains 'FOCUSED'})}
    if($edits.Count -ne 1){throw 'EXPECTED_SINGLE_OWN_EDITOR'};return $edits[0]
}
function SetTask040([string]$value){
    $field=TaskEdit040;Tap040 $field
    $focused=@((Own040)|Where-Object {$_.class -match 'EditText' -and $_.state -contains 'FOCUSED'})
    if($focused.Count -ne 1){throw 'TASK_NOT_FOCUSED'}
    Adb040 shell input keycombination 113 29|Out-Null;Adb040 shell input keyevent 67|Out-Null
    if($value){Adb040 shell input text $value|Out-Null}
}
function CloseIme040 {if(@((Windows040)|Where-Object type -eq 'INPUT_METHOD').Count){Adb040 shell input keyevent 4|Out-Null}}
try {
    Adb040 shell am broadcast -n org.mobileharness.app/.DebugOverlayReadinessReceiver -a org.mobileharness.app.action.DEBUG_READ_OVERLAY_READINESS|Out-Null
    $ready=((Adb040 shell run-as org.mobileharness.app cat no_backup/overlay-readiness-verification.json)-join '')|ConvertFrom-Json
    if(-not $ready.readiness_unlocked -or -not $ready.controls_match_runtime -or $ready.runtime_state -notin @('IDLE','ENDED')){throw 'OWN_RUNTIME_NOT_READY'}
    Adb040 shell am start -f 0x24000000 -n org.mobileharness.app/.MainActivity|Out-Null
    # MainActivity may already be on another user-selected tab; enter Task explicitly.
    Tap040 (Text040 '^任务$')
    Check040 'new_task_page' ($null -ne (Text040 '^今天需要做什么？$'))
    Check040 'initial_draft_empty' ([string]::IsNullOrEmpty((TaskEdit040).text))
    Check040 'empty_task_reason' ($null -ne (Text040 '^请输入任务$'))
    Check040 'scope_18_preserved' ($null -ne (Text040 '^授权 App · 18$'))
    SetTask040 'MH_UI_040';$draftOwned=$true
    $windows=@(Windows040);$ime=@($windows|Where-Object type -eq 'INPUT_METHOD')|Select-Object -First 1
    Check040 'real_ime_visible' ($null -ne $ime)
    $imeRect=Rect040 $ime;$edit=Rect040 (TaskEdit040);$start=Rect040 (Text040 '^开始任务$')
    Check040 'task_field_above_ime' ($edit.b -le $imeRect.t)
    Check040 'fixed_start_above_ime' ($start.b -le $imeRect.t)
    CloseIme040
    Tap040 (ScrollText040 '^模型$')
    $models=@(Own040);$badgeCount=@($models|Where-Object text -eq '密钥已加密保存').Count
    Check040 'two_encrypted_configurations_retained' ($badgeCount -eq 2)
    Tap040 (Text040 '^任务$')
    Check040 'draft_survives_tab_change_in_ram' ((TaskEdit040).text -eq 'MH_UI_040')
    Tap040 (Text040 '^30 步$')
    $budget=TaskEdit040 '30';Tap040 $budget
    Check040 'budget_focused' (@((Own040)|Where-Object {$_.class -match 'EditText' -and $_.state -contains 'FOCUSED'}).Count -eq 1)
    $windows=@(Windows040);$ime=@($windows|Where-Object type -eq 'INPUT_METHOD')|Select-Object -First 1
    Check040 'budget_ime_visible' ($null -ne $ime)
    Check040 'budget_field_above_ime' ((Rect040 (TaskEdit040)).b -le (Rect040 $ime).t)
    Check040 'budget_unchanged' ((TaskEdit040).text -eq '30')
    $done=ScrollText040 '^完成$';Check040 'settings_done_reachable' ($null -ne $done)
    CloseIme040;Tap040 (ScrollText040 '^完成$')
    Tap040 (Text040 '^授权 App · 18$')
    $search=TaskEdit040 '__EMPTY__';Tap040 $search
    Check040 'scope_search_focused' (@((Own040)|Where-Object {$_.class -match 'EditText' -and $_.state -contains 'FOCUSED'}).Count -eq 1)
    Adb040 shell input text org.mobileharness.fixture|Out-Null
    $windows=@(Windows040);$ime=@($windows|Where-Object type -eq 'INPUT_METHOD')|Select-Object -First 1
    Check040 'scope_search_above_ime' ((Rect040 (TaskEdit040)).b -le (Rect040 $ime).t)
    Check040 'scope_save_above_ime' ((Rect040 (Text040 '^保存授权范围$')).b -le (Rect040 $ime).t)
    CloseIme040;Adb040 shell input keyevent 4|Out-Null
    Check040 'scope_cancel_keeps_18' ($null -ne (Text040 '^授权 App · 18$'))
    $consent=ScrollText040 '^同意将任务与必要截图发送至上述服务$'
    Check040 'consent_reachable' ($null -ne $consent)
    Tap040 $consent
    Check040 'explicit_consent_can_be_checked' (@((Own040)|Where-Object {$_.interactions -contains 'CHECKABLE' -and $_.state -contains 'CHECKED'}).Count -gt 0)
    Tap040 (Text040 '^同意将任务与必要截图发送至上述服务$')
    Check040 'consent_cleared' (@((Own040)|Where-Object {$_.interactions -contains 'CHECKABLE' -and $_.state -contains 'CHECKED'}).Count -eq 0)
    $status='PASS'
} catch {$status='FAIL';$failure=$_.Exception.Message;throw}
finally {
    if($draftOwned){try {CloseIme040;Adb040 shell input keyevent 4|Out-Null;Adb040 shell am start -f 0x24000000 -n org.mobileharness.app/.MainActivity|Out-Null
        $field=TaskEdit040
        if($field.text -eq 'MH_UI_040'){SetTask040 '';CloseIme040}
        $flags['final_draft_empty']=[string]::IsNullOrEmpty((TaskEdit040).text)
        $flags['final_scope_18']=$null -ne (Text040 '^授权 App · 18$')
        $flags['final_harness_on']=$null -ne (Text040 '^Harness 开$')
        $flags['final_budget_30']=$null -ne (Text040 '^30 步$')
    } catch {$flags['cleanup_verified']=$false}}
    Adb040 shell am force-stop com.android.cli.interact.instrumentation|Out-Null
    $record=[ordered]@{app_version='0.4.0-dev';status=$status;failure=$failure;gui_network_requests=0;planner_network_requests=0;flags=$flags}
    $record|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $archive ('task-ui-'+$Attempt+'.json')) -Encoding UTF8
    [pscustomobject]@{status=$status;checks=$flags.Count;failed_flags=@($flags.Values|Where-Object {-not $_}).Count;failure=$failure}|ConvertTo-Json -Compress
}
