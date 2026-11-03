$ErrorActionPreference = 'Stop'
$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
$cli = 'D:\Android\Sdk\cmdline-tools\latest\bin\android.exe'
$root = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = 'D:\Android\Jdks\jdk-17.0.20.1+1'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'
Remove-Item Env:ANDROID_CLI_LAYOUT_V1 -ErrorAction SilentlyContinue

function Adb050 {
    $commandArguments = @($args)
    $output = & $adb @commandArguments
    if ($LASTEXITCODE -ne 0) { throw 'ADB failed' }
    return $output
}
function Readiness {
    Adb050 shell am broadcast -n org.mobileharness.app/.DebugOverlayReadinessReceiver -a org.mobileharness.app.action.DEBUG_READ_OVERLAY_READINESS | Out-Null
    return (Adb050 shell run-as org.mobileharness.app cat no_backup/overlay-readiness-verification.json | ConvertFrom-Json)
}
function Nodes($node) {
    if ($null -eq $node) { return }
    $node
    foreach ($child in $node.children) { Nodes $child }
}
function OwnNodes {
    $ready = Readiness
    if ($ready.runtime_state -notin @('IDLE', 'ENDED') -or -not $ready.readiness_unlocked) { throw 'Own UI inspection requires inactive unlocked runtime' }
    $tree = & $cli --no-metrics layout 2>$null | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Own layout unavailable' }
    foreach ($window in $tree) {
        if ($window.'window-title' -match 'MobileHarness|org.mobileharness.app') { Nodes $window.content }
    }
}
function Center($node) {
    if ($node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Own bounds unavailable' }
    return ,@([int](([int]$Matches[1] + [int]$Matches[3]) / 2), [int](([int]$Matches[2] + [int]$Matches[4]) / 2))
}

try {
    $initial = Readiness
    if ($initial.runtime_state -notin @('IDLE', 'ENDED')) { throw 'An active task prevents this check' }
    Adb050 shell am start -f 0x24000000 -n org.mobileharness.app/.MainActivity | Out-Null
    $task = @(OwnNodes)
    $scope = @($task | Where-Object { $_.text -eq '授权 App · 23' }).Count -gt 0
    $taskFields = @($task | Where-Object { $_.class -eq 'android.widget.EditText' })
    $emptyDraft = $taskFields.Count -eq 1 -and [string]::IsNullOrEmpty($taskFields[0].text)
    # Compose exposes the consent Row as a CHECKABLE View, not a native CheckBox.
    $checkboxes = @($task | Where-Object { $_.interactions -contains 'CHECKABLE' })
    $unchecked = $checkboxes.Count -eq 1 -and @($checkboxes | Where-Object { $_.state -contains 'CHECKED' }).Count -eq 0
    $modelTab = @($task | Where-Object { $_.text -eq '模型' })
    if ($modelTab.Count -ne 1) { throw 'Own model tab unavailable' }
    $point = Center $modelTab[0]
    Adb050 shell input tap $point[0] $point[1] | Out-Null
    $models = @(OwnNodes)
    $encrypted = @($models | Where-Object { $_.text -eq '密钥已加密保存' }).Count
    $taskTabs = @($models | Where-Object { $_.text -eq '任务' } | Sort-Object { (Center $_)[1] } -Descending)
    if ($taskTabs.Count -eq 0) { throw 'Own task tab unavailable' }
    $point = Center $taskTabs[0]
    Adb050 shell input tap $point[0] $point[1] | Out-Null
    $pathResult = (Adb050 shell pm path org.mobileharness.app) -join ''
    if ($pathResult -notmatch '^package:(/data/app/[^\s]+/base\.apk)$') { throw 'Unexpected APK path' }
    $installed = ((Adb050 shell sha256sum $Matches[1]) -split '\s+')[0]
    $apk = Join-Path $root 'android-app/app/build/outputs/apk/debug/app-debug.apk'
    $expected = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLower()
    $final = Readiness
    $result = [ordered]@{
        status = 'PASS'
        candidate = 'candidate2_final'
        installed_apk_sha256 = $installed
        installed_hash_matches = ($installed -eq $expected)
        runtime_state = $final.runtime_state
        service_connected = $final.service_connected
        controls_match_runtime = $final.controls_match_runtime
        scope23_label_unchanged = $scope
        task_draft_empty = $emptyDraft
        remote_consent_unchecked = $unchecked
        encrypted_profile_badges = $encrypted
        app_authorization_rewritten = $false
        profile_keys_read = $false
        user_screenshots_saved = $false
        earlier_checker_errors = @('WINDOW_TITLE_FILTER', 'POWERSHELL_RETURN_SPACING', 'COMPOSE_EMPTY_FIELD_AND_CHECKABLE_VIEW')
    }
    $allPass = $scope -and $emptyDraft -and $unchecked -and $encrypted -eq 2 -and $installed -eq $expected -and $final.service_connected -and $final.controls_match_runtime
    if (-not $allPass) { $result.status = 'NOT_ESTABLISHED' }
    $outputPath = Join-Path $root 'docs/acceptance/results/action-recovery-0.5.0/final-device-state.json'
    $result | ConvertTo-Json | Tee-Object -FilePath $outputPath
    if (-not $allPass) { throw 'Final predicates were not all established' }
} finally {
    Adb050 shell am force-stop com.android.cli.interact.instrumentation | Out-Null
}
