param(
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Attempt = 'attempt1',
    [switch]$RestoreOnly
)
$ErrorActionPreference = 'Stop'
$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
$cli = 'D:\Android\Sdk\cmdline-tools\latest\bin\android.exe'
$root = Split-Path $PSScriptRoot -Parent
$archive = Join-Path $root "docs/acceptance/results/localization-0.6.0/ui-$Attempt.json"
if (Test-Path -LiteralPath $archive) { throw 'ATTEMPT_ALREADY_RECORDED' }
$env:JAVA_HOME = 'D:\Android\Jdks\jdk-17.0.20.1+1'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'
Remove-Item Env:ANDROID_CLI_LAYOUT_V1 -ErrorAction SilentlyContinue
$flags = [ordered]@{}
$ownedDraft = $false
$status = 'RUNNING'
$failure = ''
$sample = 'MH_LOCALIZATION_060'
function Adb060 {
    $arguments = @($args)
    $value = & $adb @arguments
    if ($LASTEXITCODE -ne 0) { throw 'ADB_FAILED' }
    return $value
}
function Readiness060 {
    Adb060 shell am broadcast -n org.mobileharness.app/.DebugOverlayReadinessReceiver -a org.mobileharness.app.action.DEBUG_READ_OVERLAY_READINESS | Out-Null
    return (Adb060 shell run-as org.mobileharness.app cat no_backup/overlay-readiness-verification.json | ConvertFrom-Json)
}
function Nodes060($node) {
    if ($null -eq $node) { return }
    $node
    foreach ($child in $node.children) { Nodes060 $child }
}
function Own060 {
    $ready = Readiness060
    if ($ready.runtime_state -notin @('IDLE','ENDED') -or -not $ready.readiness_unlocked) { throw 'OWN_RUNTIME_NOT_READY' }
    $tree = & $cli --no-metrics layout 2>$null | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'LAYOUT_UNAVAILABLE' }
    foreach ($window in $tree) {
        $nodes = @(Nodes060 $window.content)
        $dialog = @($nodes | Where-Object { $_.text -in @('App language','应用语言','Run settings','运行设置','Local data','本机数据','Data sent for this task','此次远端发送') }).Count -gt 0
        if ($window.'window-title' -match 'MobileHarness|org\.mobileharness\.app' -or $dialog) { $nodes }
    }
}
function Find060([string[]]$Texts, [switch]$Description) {
    for ($index = 0; $index -lt 4; $index++) {
        $found = @((Own060) | Where-Object { if ($Description) { $_.'content-desc' -in $Texts } else { $_.text -in $Texts } } | Where-Object { -not $_.'off-screen' })
        if ($found.Count -gt 0) { return $found[0] }
        Start-Sleep -Milliseconds 150
    }
    throw 'EXPECTED_OWN_CONTROL_NOT_FOUND'
}
function Tap060($node) {
    if ($node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'OWN_BOUNDS_UNAVAILABLE' }
    Adb060 shell input tap ([int](([int]$Matches[1]+[int]$Matches[3])/2)) ([int](([int]$Matches[2]+[int]$Matches[4])/2)) | Out-Null
}
function Check060([string]$Name, [bool]$Value) {
    $flags[$Name] = $Value
    if (-not $Value) { throw $Name.ToUpperInvariant() }
}
function Text060([string]$Key) {
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $root 'android-app/app/src/main/res/values') -Filter '*.xml') {
        [xml]$xml = Get-Content -LiteralPath $file.FullName
        $node = @($xml.resources.string | Where-Object { $_.name -eq $Key })
        if ($node.Count -gt 0) { return [string]$node[0].InnerText }
    }
    throw 'RESOURCE_KEY_UNAVAILABLE'
}
function Language060([string]$Label) {
    Tap060 (Find060 @('App language','应用语言') -Description)
    Tap060 (Find060 @($Label))
}
function Edit060 {
    $fields = @((Own060) | Where-Object { $_.class -eq 'android.widget.EditText' -and -not $_.'off-screen' })
    if ($fields.Count -ne 1) { throw 'EXPECTED_SINGLE_TASK_EDITOR' }
    return $fields[0]
}
function SetDraft060([string]$Text) {
    Tap060 (Edit060)
    $focused = @((Own060) | Where-Object { $_.class -eq 'android.widget.EditText' -and $_.state -contains 'FOCUSED' })
    if ($focused.Count -ne 1) { throw 'OWN_EDITOR_NOT_FOCUSED' }
    Adb060 shell input keycombination 113 29 | Out-Null
    Adb060 shell input keyevent 67 | Out-Null
    if ($Text) { Adb060 shell input text $Text | Out-Null }
    Adb060 shell input keyevent 4 | Out-Null
}
try {
    $ready = Readiness060
    if ($ready.runtime_state -notin @('IDLE','ENDED') -or -not $ready.service_connected -or -not $ready.readiness_unlocked) { throw 'OWN_RUNTIME_NOT_READY' }
    Adb060 shell am start -f 0x24000000 -n org.mobileharness.app/.MainActivity | Out-Null
    if ($RestoreOnly) {
        Language060 'Follow system'
        $locale = (Adb060 shell cmd locale get-app-locales org.mobileharness.app) -join ''
        Check060 'original_system_language_choice_restored' ($locale -match '\[\]$')
    } else {
        Tap060 (Find060 @('Task','任务'))
        Check060 'initial_task_draft_empty' ([string]::IsNullOrEmpty((Edit060).text))
        Language060 'Follow system'
        $systemLocale = (Adb060 shell cmd locale get-app-locales org.mobileharness.app) -join ''
        Check060 'system_follow_mode_before_roundtrip' ($systemLocale -match '\[\]$')
        $ownedDraft = $true
        SetDraft060 $sample
        Check060 'typed_task_present_before_language_change' ((Edit060).text -eq $sample)
        Language060 '简体中文'
        Check060 'chinese_task_page' ($null -ne (Find060 @('今天需要做什么？')))
        Check060 'chinese_switch_keeps_ram_draft' ((Edit060).text -eq $sample)
        Language060 'English'
        Check060 'english_task_page' ($null -ne (Find060 @((Text060 'ui_task_heading'))))
        Check060 'english_switch_keeps_ram_draft' ((Edit060).text -eq $sample)
        Check060 'scope23_preserved' ($null -ne (Find060 @((Text060 'ui_scope_count').Replace('%1$d','23'))))
        Check060 'english_budget_label' ($null -ne (Find060 @((Text060 'ui_steps').Replace('%1$s','30'))))
        $locale = (Adb060 shell cmd locale get-app-locales org.mobileharness.app) -join ''
        Check060 'english_choice_synced_to_android' ($locale -match '\[en\]$')
        Tap060 (Find060 @('Models'))
        Check060 'english_model_library' ($null -ne (Find060 @((Text060 'ui_my_configs'))))
        $modelNodes = @(Own060)
        Check060 'two_encrypted_configurations_retained' (@($modelNodes | Where-Object { $_.text -eq (Text060 'ui_key_saved') }).Count -eq 2)
        Tap060 (Find060 @('Records'))
        Check060 'english_records_page' ($null -ne (Find060 @((Text060 'ui_run_records'))))
        Tap060 (Find060 @((Text060 'ui_data_details')))
        Check060 'english_data_disclosure' ($null -ne (Find060 @((Text060 'ui_local_data'))))
        Tap060 (Find060 @((Text060 'ui_got_it')))
        Tap060 (Find060 @('Task'))
        Check060 'draft_survives_tabs' ((Edit060).text -eq $sample)
        SetDraft060 ''
        $ownedDraft = $false
        Check060 'synthetic_task_cleared' ([string]::IsNullOrEmpty((Edit060).text))
        $task = @(Own060)
        $consent = @($task | Where-Object { $_.interactions -contains 'CHECKABLE' })
        Check060 'remote_consent_remains_unchecked' ($consent.Count -eq 1 -and @($consent | Where-Object { $_.state -contains 'CHECKED' }).Count -eq 0)
        Check060 'service_remains_bound' ((Readiness060).service_connected)
    }
    $status = 'PASS'
} catch {
    $status = 'FAIL'
    $candidateCode = $_.Exception.Message
    $failure = if ($candidateCode -match '^[A-Z_]+$') { $candidateCode } else { 'UI_CHECKER_EXCEPTION' }
    $flags['checker_failure_line'] = $_.InvocationInfo.ScriptLineNumber
} finally {
    # Cleanup only the synthetic draft owned by this check. No export/delete/config save.
    if ($ownedDraft) {
        try {
            Tap060 (Find060 @('Task','任务'))
            if ((Edit060).text -eq $sample) { SetDraft060 '' }
        } catch { $flags['synthetic_cleanup_not_established'] = $true }
    }
    Adb060 shell am force-stop com.android.cli.interact.instrumentation | Out-Null
    [ordered]@{status=$status;code=$failure;flags=$flags;gui_network_requests=0;planner_network_requests=0;device_screenshots=0;profile_keys_read=$false;authorization_rewritten=$false} |
        ConvertTo-Json -Depth 5 | Tee-Object -FilePath $archive
}
if ($status -ne 'PASS') { throw 'LOCALIZATION_UI_CHECK_FAILED' }
