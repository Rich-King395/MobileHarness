param(
    [Parameter(Mandatory=$true)][ValidateSet('Configure','Start','Poll','StopBlocked','Restore','Oracle')][string]$Action,
    [ValidateSet('contacts','calendar','clock','calc_recognition')][string]$Case='contacts',
    [string]$ArchiveDirectory=''
)
$ErrorActionPreference='Stop'
$adb='D:\Android\Sdk\platform-tools\adb.exe'
$android='D:\Android\Sdk\cmdline-tools\latest\bin\android.exe'
$workspace=Split-Path $PSScriptRoot -Parent
$archive=if($ArchiveDirectory){[IO.Path]::GetFullPath($ArchiveDirectory)}else{Join-Path $workspace 'docs/acceptance/results/three-app-smoke-0.3.4'}
$resultsRoot=[IO.Path]::GetFullPath((Join-Path $workspace 'docs/acceptance/results'))+[IO.Path]::DirectorySeparatorChar
if(-not $archive.StartsWith($resultsRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Archive path must stay inside workspace acceptance results'}
$plan=Get-Content (Join-Path $archive 'plan.json') -Raw|ConvertFrom-Json
$casePlan=$plan.cases|Where-Object {$_.id -eq $Case}
if(@($casePlan).Count -ne 1){throw 'Case plan is missing or ambiguous'}
Remove-Item Env:ANDROID_CLI_LAYOUT_V1 -ErrorAction SilentlyContinue
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 "-Djdk.net.unixdomain.tmpdir=D:\Files\CityURAProject\Mobile Agent\MobileHarness\android-app\.gradle\socket-tmp"'
function Invoke-Adb { param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments); $output=& $adb @Arguments; if($LASTEXITCODE -ne 0){throw 'ADB command failed'}; return $output }
function Nodes($items){foreach($n in $items){$n; Nodes $n.children}}
function Windows { $raw=& $android --no-metrics layout 2>$null; if($LASTEXITCODE -ne 0){throw 'Layout unavailable'}; return (($raw -join [Environment]::NewLine)|ConvertFrom-Json) }
function MainNodes { foreach($w in (Windows)){if($w.'window-title' -match 'MobileHarness|org\.mobileharness\.app') {Nodes $w.content}} }
function SheetNodes { foreach($w in (Windows)){ $nodes=@(Nodes $w.content); if(@($nodes|Where-Object {$_.text -eq '授权 App'}).Count -gt 0){return $nodes} }; throw 'Authorization sheet unavailable' }
function Tap($n){
    if($null -eq $n -or $n.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){throw 'Invalid UI bounds'}
    $left=[int]$Matches[1];$top=[int]$Matches[2];$right=[int]$Matches[3];$bottom=[int]$Matches[4]
    if($left -lt 0 -or $top -lt 0 -or $right -gt 1080 -or $bottom -gt 2280 -or $bottom -le $top){throw 'UI target off screen'}
    Invoke-Adb @('shell','input','tap',[string][int](($left+$right)/2),[string][int](($top+$bottom)/2))|Out-Null
}
function MainText([string]$pattern){
    for($try=0;$try -lt 4;$try++){
        $nodes=@(MainNodes); $targets=@($nodes|Where-Object {$_.text -match $pattern -and -not $_.'off-screen'})
        if($targets.Count -eq 1){try{Tap $targets[0];return}catch{if($_.Exception.Message -ne 'UI target off screen'){throw}}}
        if($targets.Count -gt 1){throw 'Ambiguous own UI label'}
        $scroll=@($nodes|Where-Object {$_.interactions -contains 'SCROLLABLE'})|Select-Object -First 1
        if($null -eq $scroll){throw 'Own UI target unavailable'}
        Invoke-Adb @('shell','input','swipe','950','2000','950','1100','700')|Out-Null
    }
    throw 'Own UI target not reachable'
}
function Input([string]$value){if($value -notmatch '^[A-Za-z0-9 ._-]*$'){throw 'Unexpected synthetic input characters'}; Invoke-Adb @('shell','input','text',$value.Replace(' ','%s'))|Out-Null}
function CloseKeyboard { $windows=@(Windows); if(@($windows|Where-Object {$_.type -eq 'INPUT_METHOD'}).Count -gt 0){Invoke-Adb @('shell','input','keyevent','4')|Out-Null} }
function SetTask([string]$task){
    $nodes=@(MainNodes); $edits=@($nodes|Where-Object {$_.class -match 'EditText'})
    if($edits.Count -ne 1){throw 'Own task field unavailable'}
    Tap $edits[0]
    $focused=@((MainNodes)|Where-Object {$_.class -match 'EditText' -and $_.state -contains 'FOCUSED'})
    if($focused.Count -ne 1){throw 'Task input is not focused'}
    Invoke-Adb @('shell','input','keycombination','113','29')|Out-Null
    Invoke-Adb @('shell','input','keyevent','67')|Out-Null
    if($task.Length -gt 0){Input $task}
    $verify=@(MainNodes)
    if($task.Length -gt 0 -and @($verify|Where-Object {$_.text -eq $task}).Count -eq 0){throw 'Synthetic task text mismatch'}
    CloseKeyboard
}
function SetScope([string]$pkg){
    MainText '^授权 App · '
    $nodes=@(SheetNodes)
    $clear=@($nodes|Where-Object {$_.text -eq '清空'})
    if($clear.Count -ne 1){throw 'Scope clear unavailable'}
    Tap $clear[0]
    if($pkg){
        $edits=@((SheetNodes)|Where-Object {$_.class -match 'EditText'})
        if($edits.Count -ne 1){throw 'Scope search unavailable'}
        Tap $edits[0]
        $focused=@((SheetNodes)|Where-Object {$_.class -match 'EditText' -and $_.state -contains 'FOCUSED'})
        if($focused.Count -ne 1){throw 'Scope search is not focused'}
        Input $pkg
        $nodes=@(SheetNodes)
        $rows=@($nodes|Where-Object {($_.interactions -contains 'CHECKABLE') -and @((Nodes $_)|Where-Object {$_.text -eq $pkg}).Count -gt 0})
        if($rows.Count -ne 1){throw 'Requested App scope row not unique'}
        Tap $rows[0]
        CloseKeyboard
        if(@((SheetNodes)|Where-Object {$_.text -eq '已选 1 个'}).Count -ne 1){throw 'Single App scope not selected'}
    } else {
        if(@((SheetNodes)|Where-Object {$_.text -eq '已选 0 个'}).Count -ne 1){throw 'Empty scope not selected'}
    }
    $save=@((SheetNodes)|Where-Object {$_.text -eq '保存授权范围'})
    if($save.Count -ne 1){throw 'Scope save unavailable'}
    Tap $save[0]
}
function LatestFile { $files=Invoke-Adb @('shell','run-as','org.mobileharness.app','ls','-t','no_backup/events'); return ($files|Where-Object {$_ -match '^[0-9a-f-]{36}\.jsonl$'}|Select-Object -First 1) }
function ReadEvents([string]$file){$raw=Invoke-Adb @('shell','run-as','org.mobileharness.app','cat',"no_backup/events/$file"); return @($raw|Where-Object {$_}|ForEach-Object {$_|ConvertFrom-Json})}
function ReadReady {Invoke-Adb @('shell','am','broadcast','-n','org.mobileharness.app/.DebugOverlayReadinessReceiver','-a','org.mobileharness.app.action.DEBUG_READ_OVERLAY_READINESS')|Out-Null; return (((Invoke-Adb @('shell','run-as','org.mobileharness.app','cat','no_backup/overlay-readiness-verification.json')) -join '')|ConvertFrom-Json)}
function Main {Invoke-Adb @('shell','am','start','-f','0x24000000','-n','org.mobileharness.app/.MainActivity')|Out-Null}

switch($Action){
 'Configure' {
    $latest=LatestFile; if($latest -and (ReadEvents $latest)[-1].event -ne 'session_ended'){throw 'Existing session has not ended'}
    $ready=ReadReady; if(-not($ready.service_connected -and $ready.controls_match_runtime)){throw 'Service not idle or unavailable'}
    Invoke-Adb @('shell','input','keyevent','224')|Out-Null
    Invoke-Adb @('shell','input','keyevent','82')|Out-Null
    Main
    SetTask $casePlan.task
    SetScope $casePlan.package
    $nodes=@(MainNodes)
    if(@($nodes|Where-Object {$_.text -eq 'Harness 开启'}).Count -ne 1 -or @($nodes|Where-Object {$_.text -eq '30 步 · 设置'}).Count -ne 1){throw 'Harness or budget differs from baseline'}
    $consent=@($nodes|Where-Object {($_.interactions -contains 'CHECKABLE') -and @((Nodes $_)|Where-Object {$_.text -match '^我同意将任务'}).Count -gt 0})
    if($consent.Count -ne 1){throw 'Screenshot consent control unavailable'}
    if($consent[0].state -notcontains 'CHECKED'){Tap $consent[0]}
    [pscustomobject]@{case=$Case;configured=$true;normal_ui=$true;single_app_scope=$true;budget=30;harness=$true}|ConvertTo-Json -Compress
 }
 'Start' {
    if(Test-Path (Join-Path $archive "$Case.events.jsonl")){throw 'Refusing to overwrite completed trial'}
    if(Test-Path (Join-Path $archive "$Case.session.json")){throw 'Case already started; inspect existing session instead of restarting'}
    $before=LatestFile
    $ready=ReadReady; if(-not($ready.service_connected -and $ready.readiness_unlocked -and $ready.controls_match_runtime)){throw 'Phone not ready'}
    $nodes=@(MainNodes)
    if(@($nodes|Where-Object {$_.text -eq $casePlan.task}).Count -ne 1 -or
       @($nodes|Where-Object {$_.text -eq '授权 App · 1 个'}).Count -ne 1 -or
       @($nodes|Where-Object {$_.text -eq 'Harness 开启'}).Count -ne 1 -or
       @($nodes|Where-Object {$_.text -eq '30 步 · 设置'}).Count -ne 1){throw 'Configured test baseline changed'}
    MainText '^开始任务$'
    Invoke-Adb @('shell','am','force-stop','com.android.cli.interact.instrumentation')|Out-Null
    Start-Sleep -Milliseconds 3000
    $after=LatestFile
    if(-not $after -or $after -eq $before){throw 'No new session was started'}
    [ordered]@{case=$Case;event_file=$after}|ConvertTo-Json|Set-Content (Join-Path $archive "$Case.session.json") -Encoding utf8
    [pscustomobject]@{case=$Case;started=$true;session_id=$after.Replace('.jsonl','')}|ConvertTo-Json -Compress
 }
 'Poll' {
    $session=Get-Content (Join-Path $archive "$Case.session.json") -Raw|ConvertFrom-Json
    $events=ReadEvents $session.event_file
    $last=$events[-1];$ended=$last.event -eq 'session_ended';$error=@($events|Where-Object {$_.event -eq 'error'}|Select-Object -Last 1)
    if($ended){
        $raw=Invoke-Adb @('shell','run-as','org.mobileharness.app','cat',"no_backup/events/$($session.event_file)")
        $targetPath=Join-Path $archive "$Case.events.jsonl"
        $content=($raw -join [Environment]::NewLine)+[Environment]::NewLine
        if(Test-Path -LiteralPath $targetPath){
            if([IO.File]::ReadAllText($targetPath).TrimEnd() -ne $content.TrimEnd()){throw 'Existing event archive differs; refusing overwrite'}
        } else {[IO.File]::WriteAllText($targetPath,$content,[Text.UTF8Encoding]::new($false))}
    }
    [pscustomobject]@{case=$Case;ended=$ended;events=$events.Count;gui_started=@($events|Where-Object {$_.event -eq 'model_call' -and $_.data.role -eq 'GUI' -and $_.data.status -eq 'started'}).Count;last_event=$last.event;end_data=if($ended){$last.data}else{$null};error=if($error.Count){$error[0].data}else{$null}}|ConvertTo-Json -Depth 5 -Compress
 }
 'StopBlocked' {
    $session=Get-Content (Join-Path $archive "$Case.session.json") -Raw|ConvertFrom-Json
    $events=ReadEvents $session.event_file
    if($events[-1].event -eq 'session_ended'){throw 'Session already ended'}
    if(@($events|Where-Object {$_.event -eq 'error'}).Count -eq 0){throw 'No recorded blocking error; refusing automatic interruption'}
    Main; MainText '^停止任务$'
    [pscustomobject]@{case=$Case;operator_stop_after_error=$true;model_retry=$false}|ConvertTo-Json -Compress
 }
 'Restore' {
    $latest=LatestFile; if((ReadEvents $latest)[-1].event -ne 'session_ended'){throw 'Active session prevents restore'}
    Main; SetTask ''; SetScope ''
    $nodes=@(MainNodes)
    $consent=@($nodes|Where-Object {($_.interactions -contains 'CHECKABLE') -and @((Nodes $_)|Where-Object {$_.text -match '^我同意将任务'}).Count -gt 0})
    if($consent.Count -ne 1){throw 'Consent restoration control unavailable'}
    if($consent[0].state -contains 'CHECKED'){Tap $consent[0]}
    [pscustomobject]@{restored_original_empty_scope=$true;cleared_synthetic_draft=$true;restored_original_consent_unchecked=$true}|ConvertTo-Json -Compress
 }
 'Oracle' {
    if(Test-Path (Join-Path $archive "$Case.oracle.json")){throw 'Oracle archive already exists; preserve it before re-inspection'}
    $session=Get-Content (Join-Path $archive "$Case.session.json") -Raw|ConvertFrom-Json
    if((ReadEvents $session.event_file)[-1].event -ne 'session_ended'){throw 'Independent oracle requires ended session'}
    $target=@()
    foreach($w in (Windows)){
        $nodes=@(Nodes $w.content)
        if($w.type -eq 'APPLICATION' -and ($w.'window-title' -like "$($casePlan.package)/*" -or @($nodes|Where-Object {$_.'resource-id' -like "$($casePlan.package):id/*"}).Count -gt 0)){$target += $nodes}
    }
    $result=[ordered]@{case=$Case;source='INDEPENDENT_POST_SESSION_LAYOUT';target_app_window_present=$target.Count -gt 0;personal_contents_exported=$false}
    switch($Case){
      'contacts' {
        $edits=@($target|Where-Object {$_.class -match 'EditText' -and -not $_.'hidden'})
        $search=@($edits|Where-Object {$_.'resource-id' -match 'search|query'})
        $match=@($search|Where-Object {$_.text -eq 'MH20261031Smoke'})
        $result.actual_search_editable_present=$search.Count -gt 0
        $result.synthetic_query_matches_actual_editable=$match.Count -eq 1
        $result.task_achieved=$match.Count -eq 1
      }
      'calendar' {
        $chips=@($target|Where-Object {$_.'resource-id' -eq 'com.google.android.calendar:id/month_chip'})
        $nov=@($chips|Where-Object {($_.state -contains 'CHECKED') -and @((Nodes $_)|Where-Object {$_.text -match '^Nov(ember)?$'}).Count -gt 0})
        $dec=@($chips|Where-Object {@((Nodes $_)|Where-Object {$_.text -match '^Dec(ember)?$'}).Count -gt 0})
        $jan=@($chips|Where-Object {@((Nodes $_)|Where-Object {$_.text -match '^Jan(uary)?$'}).Count -gt 0})
        $boundary=@($target|Where-Object {$_.'resource-id' -eq 'com.google.android.calendar:id/year_label' -and $_.text -eq '2027'})
        function X($n){if($n.bounds -match '^\[(\d+),'){return [int]$Matches[1]};return -1}
        $boundaryRight=($nov.Count -eq 1 -and $dec.Count -eq 1 -and $jan.Count -eq 1 -and $boundary.Count -eq 1 -and
            (X $nov[0]) -lt (X $dec[0]) -and (X $dec[0]) -lt (X $boundary[0]) -and (X $boundary[0]) -lt (X $jan[0]))
        $dates=@()
        foreach($n in $target){
            $d=[string]$n.'content-desc'
            if(-not $n.'resource-id' -and $d -match 'Sunday|Monday|Tuesday|Wednesday|Thursday|Friday|Saturday' -and $d -match 'November|Nov\b'){
                $weekday=[regex]::Match($d,'Sunday|Monday|Tuesday|Wednesday|Thursday|Friday|Saturday').Value
                # Accessibility labels may zero-pad 01..09. This is oracle parsing only.
                $num=[regex]::Match($d,'(?<!\d)([12][0-9]|3[01]|0?[1-9])(?!\d)')
                if($num.Success){$dates += [pscustomobject]@{day=[int]$num.Value;weekday=$weekday;bounds=$n.bounds}}
            }
        }
        $weekdayConsistent=($dates.Count -ge 28 -and @($dates|Where-Object {([datetime]::new(2026,11,$_.day)).DayOfWeek.ToString() -ne $_.weekday}).Count -eq 0)
        $columns=@($dates|ForEach-Object {if($_.bounds -match '^\[(\d+),'){[int]$Matches[1]}}|Select-Object -Unique).Count
        $rows=@($dates|ForEach-Object {if($_.bounds -match '^\[\d+,(\d+)\]'){[int]$Matches[1]}}|Select-Object -Unique).Count
        $grid=($columns -eq 7 -and $rows -ge 4 -and @($target|Where-Object {$_.'resource-id' -eq 'com.google.android.calendar:id/action_today'}).Count -eq 1)
        $result.november_chip_checked=$nov.Count -eq 1
        $result.january_2027_boundary_after_november_and_december=$boundaryRight
        $result.year_2026_inferred_from_month_strip=$boundaryRight
        $result.month_grid_present=$grid
        $result.parsed_public_date_cells=$dates.Count
        $result.november_date_weekdays_match_2026=$weekdayConsistent
        $result.date_1_is_sunday=@($dates|Where-Object {$_.day -eq 1 -and $_.weekday -eq 'Sunday'}).Count -eq 1
        $result.task_achieved=($boundaryRight -and $weekdayConsistent -and $grid)
      }
      'clock' {
        $selected=@($target|Where-Object {(($_.state -contains 'SELECTED') -or ($_.state -contains 'CHECKED')) -and
            (($_.'content-desc' -eq 'Stopwatch') -or @((Nodes $_)|Where-Object {$_.text -eq 'Stopwatch'}).Count -gt 0)})
        $pane=@($target|Where-Object {$_.'resource-id' -match '(stopwatch_view|stopwatch_time|stopwatch_fragment|stopwatch_list|stopwatch_circle|stopwatch_page|stopwatch_content|stopwatch_controls|stopwatch_container)'})
        $result.stopwatch_tab_selected=$selected.Count -gt 0
        $result.stopwatch_pane_present=$pane.Count -gt 0
        $result.task_achieved=($selected.Count -gt 0 -and $pane.Count -gt 0)
      }
      'calc_recognition' {
        $formula=@($target|Where-Object {$_.'resource-id' -eq 'com.google.android.calculator:id/formula'})
        $preview=@($target|Where-Object {$_.'resource-id' -eq 'com.google.android.calculator:id/result_preview'})
        $result.formula_control_present=$formula.Count -eq 1
        $result.formula_empty=($formula.Count -eq 1 -and [string]::IsNullOrWhiteSpace([string]$formula[0].text))
        $result.preview_empty=($preview.Count -eq 1 -and [string]::IsNullOrWhiteSpace([string]$preview[0].text))
        $result.task_achieved=($result.formula_empty -and $result.preview_empty)
      }
    }
    $result|ConvertTo-Json|Set-Content (Join-Path $archive "$Case.oracle.json") -Encoding utf8
    $result|ConvertTo-Json -Compress
    Invoke-Adb @('shell','am','force-stop','com.android.cli.interact.instrumentation')|Out-Null
 }
}
