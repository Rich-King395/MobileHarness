param([ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Attempt='attempt1')
$ErrorActionPreference='Stop'
$adb='D:\Android\Sdk\platform-tools\adb.exe'
$cli='D:\Android\Sdk\cmdline-tools\latest\bin\android.exe'
$root=Split-Path $PSScriptRoot -Parent
$archive=Join-Path $root "docs/acceptance/results/task-records-0.7.0/ui-$Attempt.json"
if(Test-Path -LiteralPath $archive){throw 'ATTEMPT_ALREADY_RECORDED'}
$env:JAVA_HOME='D:\Android\Jdks\jdk-17.0.20.1+1'
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'
Remove-Item Env:ANDROID_CLI_LAYOUT_V1 -ErrorAction SilentlyContinue
$flags=[ordered]@{};$status='RUNNING';$code=''
function Adb070 {$a=@($args);$r=& $adb @a;if($LASTEXITCODE -ne 0){throw 'ADB_FAILED'};return $r}
function Ready070 {Adb070 shell am broadcast -n org.mobileharness.app/.DebugOverlayReadinessReceiver -a org.mobileharness.app.action.DEBUG_READ_OVERLAY_READINESS|Out-Null;return (Adb070 shell run-as org.mobileharness.app cat no_backup/overlay-readiness-verification.json|ConvertFrom-Json)}
function Nodes070($n){if($null -eq $n){return};$n;foreach($c in $n.children){Nodes070 $c}}
function Own070 {
    $ready=Ready070
    if($ready.runtime_state -notin @('IDLE','ENDED') -or -not $ready.readiness_unlocked){throw 'OWN_RUNTIME_NOT_READY'}
    $windows=& $cli --no-metrics layout 2>$null|ConvertFrom-Json
    if($LASTEXITCODE -ne 0){throw 'LAYOUT_UNAVAILABLE'}
    foreach($w in $windows){if($w.'window-title' -match 'MobileHarness|org\.mobileharness\.app'){Nodes070 $w.content}}
}
function Rect070($n){if($n.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){throw 'OWN_BOUNDS_MISSING'};return @{l=[int]$Matches[1];t=[int]$Matches[2];r=[int]$Matches[3];b=[int]$Matches[4]}}
function Tap070($n){$r=Rect070 $n;Adb070 shell input tap ([int](($r.l+$r.r)/2)) ([int](($r.t+$r.b)/2))|Out-Null}
function Find070([string]$Pattern){@((Own070)|Where-Object{$_.text -match $Pattern -and -not $_.'off-screen'})|Select-Object -First 1}
function Scroll070([bool]$Up){
    $s=@((Own070)|Where-Object{$_.interactions -contains 'SCROLLABLE' -and -not $_.'off-screen'}|Sort-Object{ $r=Rect070 $_;$r.b-$r.t } -Descending)|Select-Object -First 1
    if($null -eq $s){throw 'OWN_SCROLL_NOT_FOUND'}
    $r=Rect070 $s;$h=$r.b-$r.t;$x=[int](($r.l+$r.r)/2)
    $start=if($Up){$r.t+$h*.25}else{$r.t+$h*.8}
    $end=if($Up){$r.t+$h*.8}else{$r.t+$h*.25}
    Adb070 shell input swipe $x ([int]$start) $x ([int]$end) 500|Out-Null
}
function Reach070([string]$Pattern){for($i=0;$i -lt 6;$i++){$n=Find070 $Pattern;if($n){return $n};Scroll070 $false};throw 'OWN_LABEL_NOT_REACHABLE'}
function Check070([string]$Name,[bool]$Value){$flags[$Name]=$Value;if(-not $Value){throw $Name.ToUpperInvariant()}}
try {
    $ready=Ready070
    if($ready.runtime_state -notin @('IDLE','ENDED') -or -not $ready.service_connected -or -not $ready.readiness_unlocked){throw 'OWN_RUNTIME_NOT_READY'}
    Adb070 shell am start -n org.mobileharness.app/.DebugRecordUiActivity|Out-Null
    Check070 'synthetic_record_component_visible' ($null -ne (Find070 '^Record UI fixture$'))
    Check070 'english_gui_usage' ($null -ne (Find070 '^GUI steps · 7/30$'))
    Check070 'english_planner_count' ($null -ne (Find070 '^Planner calls · 2/2$'))
    Tap070 (Find070 '^Details$')
    Check070 'english_original_task_heading' ($null -ne (Find070 '^Original task$'))
    Check070 'full_synthetic_task_visible' ($null -ne (Find070 'TASK_RECORD_UI_070'))
    Check070 'planner_one_status' ($null -ne (Reach070 '^Planner 1 · Response accepted$'))
    Check070 'planner_one_decision' ($null -ne (Reach070 '^Decision · Read-only check$'))
    Check070 'planner_two_service_error' ($null -ne (Reach070 '^Planner 2 · Service error$'))
    Check070 'planner_http_status' ($null -ne (Find070 '^HTTP 429$'))
    Scroll070 $true;Scroll070 $true
    Tap070 (Find070 '^简体中文$')
    Check070 'chinese_gui_usage' ($null -ne (Find070 '^GUI 步数 · 7/30$'))
    Check070 'chinese_original_task_heading' ($null -ne (Find070 '^原始任务$'))
    Check070 'chinese_planner_decision' ($null -ne (Reach070 '^决策 · 只读查证$'))
    Check070 'legacy_task_not_invented' ($null -ne (Reach070 '^此记录未保存任务原文$'))
    # The first card is expanded; the next collapsed card is the synthetic legacy record.
    Tap070 (Reach070 '^查看详情$')
    Check070 'harness_off_distinguished_from_missing_details' ($null -ne (Reach070 '^未调用 · Harness 已关闭$'))
    Adb070 shell am start -f 0x24000000 -n org.mobileharness.app/.MainActivity|Out-Null
    Tap070 (Find070 '^(Task|任务)$')
    Check070 'local_task_storage_notice' ($null -ne (Find070 '^(Task text is saved encrypted on this device for experiment records\.|任务原文会加密保存在本机实验记录中。)$'))
    Tap070 (Find070 '^(Records|记录)$')
    Check070 'actual_records_page_opens' ($null -ne (Find070 '^(Run records|运行记录)$'))
    $baseline=Get-Content -LiteralPath (Join-Path $root 'docs/acceptance/results/task-records-0.7.0/before-install.json') -Raw|ConvertFrom-Json
    if($baseline.event_file_count -gt 0){
        Check070 'actual_record_gui_counters_visible' ($null -ne (Find070 '^(GUI steps|GUI 步数) · \d+/\d+$'))
        Check070 'actual_records_offer_details' ($null -ne (Find070 '^(Details|查看详情)$'))
    } else {
        $emptyLabels=@()
        foreach($language in @('values','values-zh')){
            [xml]$xml=Get-Content -LiteralPath (Join-Path $root "android-app/app/src/main/res/$language/main_ui_strings.xml")
            $emptyLabels+=@($xml.resources.string|Where-Object{$_.name-eq'ui_no_records'}|ForEach-Object{[regex]::Escape($_.InnerText)})
        }
        Check070 'actual_record_empty_state_matches_preexisting_zero_records' ($null -ne (Find070 ('^('+($emptyLabels-join'|')+')$')))
    }
    Tap070 (Find070 '^(Task|任务)$')
    $status='PASS'
} catch {
    $status='FAIL';$message=$_.Exception.Message
    $code=if($message -match '^[A-Z_]+$'){$message}else{'UI_CHECKER_EXCEPTION'}
} finally {
    Adb070 shell am force-stop com.android.cli.interact.instrumentation|Out-Null
    [ordered]@{status=$status;code=$code;flags=$flags;synthetic_ui_only=$true;existing_record_counts_only=$true;gui_network_requests=0;planner_network_requests=0;screenshots=0;profile_keys_read=$false;existing_records_deleted=$false}|ConvertTo-Json -Depth 5|Tee-Object -FilePath $archive
}
if($status -ne 'PASS'){throw 'RECORD_UI_VERIFICATION_FAILED'}
