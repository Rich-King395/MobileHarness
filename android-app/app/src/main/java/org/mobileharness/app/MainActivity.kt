package org.mobileharness.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.mobileharness.app.core.*
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.platform.ForegroundStabilityGate
import org.mobileharness.app.platform.PlatformReadiness
import org.mobileharness.app.model.QwenGateway
import org.mobileharness.app.storage.MinimalAudit
import org.mobileharness.app.storage.ProfileStore
import org.mobileharness.app.storage.AuthorizedAppsStore
import org.mobileharness.app.ui.theme.MobileHarnessTheme
import org.mobileharness.app.ui.AppStrings
import org.mobileharness.app.ui.AppLanguage
import org.mobileharness.app.ui.UiMessages
import org.mobileharness.app.ui.ExperimentRecordCard
import org.mobileharness.app.ui.ExperimentAnalysisPanel

class MainActivity : ComponentActivity() {
    private var visible = false
    private val starting = MutableStateFlow(false)
    private val startupNote = MutableStateFlow("")
    private var startupJob: Job? = null
    private val app get() = application as HarnessApplication
    private val authorizedAppsStore by lazy { AuthorizedAppsStore(applicationContext) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ordinary pages can be mirrored for local monitoring. Credential entry is
        // protected separately, and the Agent's own-App capture gate is unchanged.
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        setContent {
            val configuration = LocalConfiguration.current
            val languageRevision by AppLanguage.revision.collectAsStateWithLifecycle()
            val localized = remember(configuration, languageRevision) { AppLanguage.context(this@MainActivity) }
            CompositionLocalProvider(LocalContext provides localized) {
                MobileHarnessTheme { HarnessScreen() }
            }
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.configurationChanged()
    }
    override fun onResume() {
        super.onResume()
        visible = true
        if (starting.value && startupJob?.isActive == true) {
            startupJob?.cancel()
            startupNote.value = uiText(R.string.ui_startup_cancelled)
        }
        HarnessAccessibilityService.sensitivePageVisible = true
        val s = app.runtime.snapshot.value
        if (s.state in setOf(RunState.AUTO, RunState.PREPARING, RunState.CHECKING)) {
            if (!s.handoffUsed) app.runtime.takeover() else app.runtime.stop()
        }
    }
    override fun onPause() {
        visible = false
        HarnessAccessibilityService.sensitivePageVisible = false
        super.onPause()
    }
    override fun onDestroy() {
        startupJob?.cancel()
        super.onDestroy()
    }
    private fun begin(request: TaskLaunchRequest, onStarted: () -> Unit) {
        if (starting.value) return
        val service = HarnessAccessibilityService.instance
        if (service?.connected != true || service.controls !== app.runtime || service.inputInFlight ||
            app.runtime.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED)) {
            startupNote.value = uiText(R.string.ui_startup_unavailable)
            return
        }
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val home = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        val homes = packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        if (home == null || !home.enabled || !home.exported || !home.applicationInfo.enabled ||
            homes.none { it.activityInfo.packageName == home.packageName && it.activityInfo.name == home.name }) {
            startupNote.value = uiText(R.string.ui_default_home_required)
            return
        }
        val homePackage = home.packageName
        val launchIntent = homeIntent.apply {
            component = ComponentName(homePackage, home.name)
            selector = null
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val options = SessionOptions(
            task = request.task, initialPackage = homePackage, authorizedPackages = request.authorizedPackages,
            harness = request.harness, budget = request.budget, gui = request.gui, planner = request.planner,
            startMode = StartMode.HOME_AUTO, homePackage = homePackage, authorizedAppLabels = request.authorizedAppLabels,
        )
        starting.value = true
        startupNote.value = uiText(R.string.ui_opening_home)
        startupJob = app.scope.launch {
            try {
                startActivity(launchIntent)
                val foregroundStability = ForegroundStabilityGate()
                val ready = withTimeoutOrNull(8_000) {
                    while (true) {
                        // Only inspect the resolved Home package during this pre-session launch.
                        // Never turn this into a background/manual-period window monitor.
                        if (!service.connected || service.controls !== app.runtime ||
                            app.runtime.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED)) return@withTimeoutOrNull false
                        if (foregroundStability.sample(!visible && service.isInitialAppForeground(options.initialPackage),
                                SystemClock.uptimeMillis())) return@withTimeoutOrNull true
                        delay(100)
                    }
                } == true
                if (!ready) {
                    startupNote.value = uiText(R.string.ui_home_not_ready)
                    Toast.makeText(this@MainActivity, uiText(R.string.ui_home_not_ready_toast), Toast.LENGTH_LONG).show()
                    return@launch
                }
                app.runtime.start(options)
                onStarted()
                startupNote.value = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                startupNote.value = uiText(R.string.ui_home_open_failed)
            } finally {
                starting.value = false
            }
        }
    }
    private fun returnToPhone() {
        moveTaskToBack(true)
        app.scope.launch { delay(400); if (!visible) app.runtime.giveBack() }
    }
    private fun uiText(resource: Int, vararg arguments: Any) = AppLanguage.context(this).getString(resource, *arguments)

    @Composable private fun HarnessScreen() {
        val context = LocalContext.current
        val state by app.runtime.snapshot.collectAsStateWithLifecycle()
        val connected by app.connected.collectAsStateWithLifecycle()
        val phone by app.phoneConnection.collectAsStateWithLifecycle()
        val isStarting by starting.collectAsStateWithLifecycle()
        val launchNote by startupNote.collectAsStateWithLifecycle()
        val emptyReadiness = remember { MutableStateFlow(PlatformReadiness()) }
        val platform by (phone?.readiness ?: emptyReadiness).collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var tab by remember { mutableIntStateOf(0) }
        var revision by remember { mutableIntStateOf(0) }
        var gui by remember { mutableStateOf(ModelProfile()) }
        var planner by remember { mutableStateOf(ModelProfile()) }
        var guiReady by remember { mutableStateOf(false) }
        var plannerReady by remember { mutableStateOf(false) }
        var configurations by remember { mutableStateOf<List<ProfileStore.SavedProfile>>(emptyList()) }
        var guiSlot by remember { mutableStateOf<String?>(null) }
        var plannerSlot by remember { mutableStateOf<String?>(null) }
        var profileBusy by remember { mutableStateOf(false) }
        var profileError by remember { mutableStateOf("") }
        var ready by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf("") }
        var sessions by remember { mutableStateOf<List<MinimalAudit.SessionSummary>>(emptyList()) }
        var selectedSessions by remember { mutableStateOf<Set<String>>(emptySet()) }
        // Deliberately not rememberSaveable: recreation cancels the pending selection.
        var pendingExportSessions by remember { mutableStateOf<Set<String>?>(null) }
        var recordBusy by remember { mutableStateOf(false) }
        var recordFilter by remember { mutableIntStateOf(0) }
        var recordPage by remember { mutableIntStateOf(0) }
        val draft = remember { TaskDraft() } // RAM only; survives tab changes, never process recreation.
        var showSetup by remember { mutableStateOf(false) }
        var showLanguage by remember { mutableStateOf(false) }
        var showRecordsHelp by remember { mutableStateOf(false) }
        val inactive = state.state in setOf(RunState.IDLE, RunState.ENDED)
        LaunchedEffect(revision, state.sessionId, state.state, state.guiUsed, state.plannerUsed) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { LibraryMetadata(app.profiles.list(), app.profiles.selectedId("gui"), app.profiles.selectedId("planner"),
                    app.profiles.metadata("gui"), app.profiles.metadata("planner"), app.profiles.hasKey("gui"), app.profiles.hasKey("planner"), app.audit.sessionSummaries()) }
            }
            loaded.onSuccess {
                configurations = it.configurations; guiSlot = it.guiSlot; plannerSlot = it.plannerSlot
                gui = it.gui; planner = it.planner; guiReady = it.guiReady; plannerReady = it.plannerReady
                sessions = it.sessions
                selectedSessions = selectedSessions.intersect(sessions.filter { row -> row.ended }.map { row -> row.id }.toSet())
            }.onFailure { note = context.getString(R.string.ui_config_read_failed) }
            ready = loaded.isSuccess
        }
        fun changeProfiles(operation: () -> Unit, onSuccess: () -> Unit = {}) {
            if (profileBusy || recordBusy || pendingExportSessions != null || starting.value || app.runtime.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED)) return
            profileBusy = true
            profileError = ""
            ready = false
            draft.consent = false
            scope.launch {
                val saved = withContext(Dispatchers.IO) { runCatching(operation) }
                saved.onSuccess { note = ""; onSuccess() }.onFailure { profileError = context.getString(R.string.ui_config_update_failed) }
                profileBusy = false
                revision++
            }
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            note = if (it) context.getString(R.string.ui_notifications_granted) else context.getString(R.string.ui_notifications_not_granted)
        }
        fun writeExport(uri: Uri?, csv: Boolean) {
            val frozenSelection = pendingExportSessions
            pendingExportSessions = null
            if (uri != null && frozenSelection.isNullOrEmpty()) {
                note = context.getString(R.string.ui_export_selection_expired)
            } else if (uri != null && app.runtime.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED)) {
                note = context.getString(R.string.ui_export_running_cancelled)
            } else if (uri != null && frozenSelection != null) {
                recordBusy = true
                scope.launch {
                    try {
                        note = withContext(Dispatchers.IO) {
                            runCatching {
                                val content = if (csv) app.audit.exportAnalysisCsv(frozenSelection) else app.audit.exportMetadata(frozenSelection)
                                if (content.isEmpty()) false else {
                                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                                        ?: error("EXPORT_FAILED")
                                    true
                                }
                            }.fold({ if (it) context.getString(R.string.ui_export_success) else context.getString(R.string.ui_export_no_records) }, { context.getString(R.string.ui_export_failed) })
                        }
                    } finally { recordBusy = false }
                }
            }
        }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri -> writeExport(uri, false) }
        val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> writeExport(uri, true) }
        fun changeRecord(operation: () -> Boolean, success: Int, failure: Int) {
            if (recordBusy || pendingExportSessions != null || starting.value ||
                app.runtime.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED)) return
            recordBusy = true
            scope.launch {
                try {
                    val saved = withContext(Dispatchers.IO) { runCatching(operation).getOrDefault(false) }
                    revision++
                    note = context.getString(if (saved) success else failure)
                } finally { recordBusy = false }
            }
        }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("MobileHarness", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showLanguage = true }) {
                        Icon(painterResource(R.drawable.ic_language), contentDescription = context.getString(R.string.language_title))
                    }
                    TextButton(onClick = { showSetup = true }) { Text(if (connected) context.getString(R.string.ui_connected_badge) else context.getString(R.string.ui_connect_phone)) }
                }
                TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                    listOf(context.getString(R.string.ui_task), context.getString(R.string.ui_models), context.getString(R.string.ui_records)).forEachIndexed { i, title -> Tab(selected = i == tab, onClick = { tab = i }, selectedContentColor = MaterialTheme.colorScheme.onSurface, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(title) }) }
                }
                val pageScroll = rememberScrollState()
                Column(Modifier.fillMaxSize()
                    .then(if (tab == 0 && inactive) Modifier else Modifier.verticalScroll(pageScroll))
                    .padding(horizontal = if (tab == 0) 20.dp else 24.dp, vertical = if (tab == 0) 12.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(if (tab == 0) 12.dp else 16.dp)) {
                    if (!inactive) StatusCard(state, connected)
                    if (note.isNotBlank()) Text(UiMessages.localize(context, note), color = MaterialTheme.colorScheme.primary)
                    if (launchNote.isNotBlank()) Text(UiMessages.localize(context, launchNote), color = MaterialTheme.colorScheme.primary)
                    if (isStarting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!inactive) {
                        if (state.state == RunState.MANUAL) BlueButton(onClick = { returnToPhone() }, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.ui_return_to_phone)) }
                        if (state.state == RunState.BLOCKED) OutlinedButton(onClick = {
                            moveTaskToBack(true); app.scope.launch { delay(400); if (!visible) app.runtime.retry() }
                        }, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.ui_retry_in_app)) }
                        if (state.state == RunState.UNCERTAIN) OutlinedButton(onClick = { app.runtime.recheck() }) { Text(context.getString(R.string.ui_recheck_pause)) }
                        BlueButton(onClick = { app.runtime.stop() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.ui_stop_task)) }
                    }
                    when (tab) {
                        0 -> {
                            if (connected && !platform.overlayStopAvailable) Text(context.getString(R.string.ui_overlay_unavailable), color = MaterialTheme.colorScheme.error)
                            val ownsControls = HarnessAccessibilityService.instance?.controls === app.runtime
                            if (connected && !ownsControls) Text(context.getString(R.string.ui_verification_controls_unavailable), color = MaterialTheme.colorScheme.error)
                            if (inactive) {
                                if (state.state == RunState.ENDED) Text(AppStrings.runtimeMessage(context, state.message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TaskForm(draft, ready && inactive && !isStarting && !profileBusy && !recordBusy && pendingExportSessions == null, connected && platform.overlayStopAvailable && ownsControls,
                                    guiReady, plannerReady, gui, planner,
                                    configurations.firstOrNull { it.slotId == guiSlot }?.name.orEmpty(),
                                    configurations.firstOrNull { it.slotId == plannerSlot }?.name.orEmpty(), { tab = 1 }) { options, onStarted -> begin(options, onStarted) }
                            }
                        }
                        1 -> {
                            ModelLibrary(configurations, guiSlot, plannerSlot,
                                enabled = inactive && !isStarting && ready && !profileBusy && !recordBusy && pendingExportSessions == null, busy = profileBusy,
                                locked = !inactive || isStarting, error = profileError,
                                onSelect = { role, slot -> changeProfiles({ app.profiles.select(role, slot) }) },
                                onSave = { slot, name, profile, secret, onSaved ->
                                    changeProfiles({ app.profiles.saveConfiguration(slot, name, profile, secret) }, onSaved)
                                }, onDelete = { slot -> changeProfiles({ app.profiles.deleteConfiguration(slot) }) })
                        }
                        2 -> {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(context.getString(R.string.ui_run_records), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { showRecordsHelp = true }) { Text(context.getString(R.string.ui_data_details)) }
                            }
                            val filtered = sessions.filter { row -> when (recordFilter) {
                                1 -> row.statistics?.harnessEnabled == true
                                2 -> row.statistics?.harnessEnabled == false
                                3 -> row.ended && row.analysis?.userAssessment == null
                                else -> true
                            } }
                            val canManageRecords = inactive && !isStarting && !recordBusy && pendingExportSessions == null
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(R.string.analysis_filter_all, R.string.analysis_filter_on, R.string.analysis_filter_off, R.string.analysis_filter_unrated).forEachIndexed { index, label ->
                                    FilterChip(selected = recordFilter == index, enabled = canManageRecords,
                                        onClick = { recordFilter = index; recordPage = 0; selectedSessions = emptySet() }, label = { Text(context.getString(label)) })
                                }
                            }
                            val scopeRows = if (selectedSessions.isEmpty()) filtered else filtered.filter { it.id in selectedSessions }
                            ExperimentAnalysisPanel(scopeRows.filter { it.ended }.mapNotNull { it.analysis }, selectedSessions.isNotEmpty())
                            if (filtered.isEmpty()) Text(context.getString(if (sessions.isEmpty()) R.string.ui_no_records else R.string.analysis_no_matching_records), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (filtered.any { it.ended }) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(enabled = canManageRecords, onClick = { selectedSessions = filtered.filter { it.ended }.map { it.id }.toSet() }) { Text(context.getString(R.string.analysis_selection_all)) }
                                TextButton(enabled = canManageRecords && selectedSessions.isNotEmpty(), onClick = { selectedSessions = emptySet() }) { Text(context.getString(R.string.analysis_selection_clear)) }
                            }
                            val pages = ((filtered.size + 9) / 10).coerceAtLeast(1)
                            val page = recordPage.coerceAtMost(pages - 1)
                            // Only this page composes/decrypts task cards; aggregate/export never load task attachments.
                            filtered.drop(page * 10).take(10).forEach { row ->
                                key(row.id) {
                                    ExperimentRecordCard(row, row.id in selectedSessions,
                                        canManage = canManageRecords, reloadKey = revision,
                                        loadTask = app.audit::readLocalTask,
                                        onSelect = { checked -> selectedSessions = if (checked) selectedSessions + row.id else selectedSessions - row.id },
                                        onDelete = {
                                            changeRecord({ app.audit.deleteSession(row.id) }, R.string.ui_session_deleted, R.string.record_delete_failed)
                                        }, onAssess = { assessment ->
                                            changeRecord({ app.audit.assessSession(row.id, assessment) }, R.string.analysis_assessment_saved, R.string.analysis_assessment_failed)
                                        })
                                }
                            }
                            if (pages > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                TextButton(enabled = page > 0 && canManageRecords, onClick = { recordPage = page - 1 }) { Text(context.getString(R.string.analysis_previous_page)) }
                                Text(context.getString(R.string.analysis_page_count, page + 1, pages), style = MaterialTheme.typography.labelMedium)
                                TextButton(enabled = page + 1 < pages && canManageRecords, onClick = { recordPage = page + 1 }) { Text(context.getString(R.string.analysis_next_page)) }
                            }
                            BlueButton(enabled = canManageRecords && selectedSessions.isNotEmpty(), onClick = {
                                val frozen = selectedSessions.intersect(sessions.filter { it.ended }.map { it.id }.toSet())
                                if (frozen.isNotEmpty()) { pendingExportSessions = frozen; exportCsv.launch("mobileharness-analysis.csv") }
                            }, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.analysis_export_csv)) }
                            OutlinedButton(enabled = canManageRecords && selectedSessions.isNotEmpty(), onClick = {
                                val eligible = sessions.filter { it.ended }.map { it.id }.toSet()
                                val frozen = selectedSessions.intersect(eligible).toSet()
                                if (frozen.isNotEmpty()) {
                                    pendingExportSessions = frozen
                                    export.launch("mobileharness-events.jsonl")
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.analysis_export_events)) }
                            OutlinedButton(enabled = canManageRecords && sessions.any { it.ended }, onClick = {
                                changeRecord({ app.audit.deleteAll() }, R.string.ui_all_records_deleted, R.string.record_delete_failed)
                            }, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.ui_delete_all_records)) }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
        if (showLanguage) LanguageDialog(
            current = AppLanguage.choice(context),
            enabled = inactive && !isStarting && !profileBusy && !recordBusy && pendingExportSessions == null,
            onDismiss = { showLanguage = false },
            onSelect = { selected ->
                if (inactive && !isStarting && !profileBusy && !recordBusy && pendingExportSessions == null) {
                    val changed = AppLanguage.changeChoice(this@MainActivity, selected)
                    if (changed) {
                        showLanguage = false
                        HarnessAccessibilityService.instance?.refreshLanguage()
                    } else note = context.getString(R.string.language_change_failed)
                }
            },
        )
        if (showSetup) AlertDialog(onDismissRequest = { showSetup = false },
            title = { Text(context.getString(R.string.ui_connection_permissions)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (connected) context.getString(R.string.ui_accessibility_connected) else context.getString(R.string.ui_accessibility_enable_instructions))
                    OutlinedButton(onClick = { showSetup = false; startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.ui_accessibility_settings)) }
                    if (Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = {
                        showSetup = false; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.ui_notification_controls)) }
                    Text(context.getString(R.string.ui_connection_return_notice), style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = { showSetup = false }) { Text(context.getString(R.string.ui_got_it)) } })
        if (showRecordsHelp) AlertDialog(onDismissRequest = { showRecordsHelp = false }, title = { Text(context.getString(R.string.ui_local_data)) },
            text = { Text(context.getString(R.string.ui_local_data_details)) },
            confirmButton = { TextButton(onClick = { showRecordsHelp = false }) { Text(context.getString(R.string.ui_got_it)) } })
    }

    @Composable private fun TaskForm(draft: TaskDraft, enabled: Boolean, serviceReady: Boolean, guiReady: Boolean, plannerReady: Boolean, gui: ModelProfile, planner: ModelProfile,
        guiName: String, plannerName: String, onModels: () -> Unit, onStart: (TaskLaunchRequest, () -> Unit) -> Unit) {
        val context = LocalContext.current
        val apps = remember {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
                .distinctBy { it.first }.filter { it.first != packageName }.sortedBy { it.second }
        }
        val scope = rememberCoroutineScope()
        var scopeRevision by remember { mutableIntStateOf(0) }
        var scopeSaving by remember { mutableStateOf(false) }
        var scopeError by remember { mutableStateOf("") }
        LaunchedEffect(scopeRevision) {
            val loaded = withContext(Dispatchers.IO) { runCatching { authorizedAppsStore.load() } }
            loaded.onSuccess { saved ->
                val allowed = saved.intersect(apps.map { it.first }.toSet())
                if (draft.allowed != allowed) draft.consent = false
                draft.allowed = allowed
                draft.scopeReady = true
                scopeError = ""
            }.onFailure { draft.scopeReady = false; scopeError = context.getString(R.string.ui_scope_read_failed) }
        }
        if (!draft.scopeReady && scopeError.isNotBlank()) {
            Text(UiMessages.localize(context, scopeError), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { scopeRevision++ }) { Text(context.getString(R.string.ui_retry)) }
        }
        TaskComposer(draft, apps, enabled && draft.scopeReady && !scopeSaving, serviceReady, guiReady, plannerReady, gui, planner,
            guiName, plannerName, onModels, scopeSaving = scopeSaving, scopeError = scopeError,
            onSaveScope = { selected, onSaved ->
                if (!scopeSaving && !starting.value && app.runtime.snapshot.value.state in setOf(RunState.IDLE, RunState.ENDED)) {
                    scopeSaving = true
                    scopeError = ""
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { runCatching { authorizedAppsStore.save(selected) } }
                        saved.onSuccess {
                            draft.allowed = selected.intersect(apps.map { it.first }.toSet())
                            draft.consent = false
                            draft.scopeReady = true
                            onSaved()
                        }.onFailure { scopeError = context.getString(R.string.ui_scope_save_failed) }
                        scopeSaving = false
                    }
                }
            }) {
            onStart(TaskLaunchRequest(draft.task, draft.allowed, apps.filter { it.first in draft.allowed }.toMap(), draft.harness, draft.budget.toInt(), gui, planner)) {
                draft.task = ""; draft.consent = false
            }
        }
    }

    @Composable private fun StatusCard(s: RunSnapshot, connected: Boolean) {
        val context = LocalContext.current
        val label = AppStrings.stateLabel(context, s.state)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(context.getString(R.string.ui_gui_request_counts, s.guiUsed, s.budget, s.plannerUsed))
                Text(if (connected) context.getString(R.string.ui_accessibility_connected) else context.getString(R.string.ui_accessibility_enable_first), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.message.isNotBlank()) Text(AppStrings.runtimeMessage(context, s.message), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private data class LibraryMetadata(
    val configurations: List<ProfileStore.SavedProfile>, val guiSlot: String?, val plannerSlot: String?,
    val gui: ModelProfile, val planner: ModelProfile, val guiReady: Boolean, val plannerReady: Boolean,
    val sessions: List<MinimalAudit.SessionSummary>,
)

/** Input stays in memory; runtime options are constructed only after resolving an actual Home. */
private data class TaskLaunchRequest(
    val task: String, val authorizedPackages: Set<String>, val authorizedAppLabels: Map<String, String>,
    val harness: Boolean, val budget: Int, val gui: ModelProfile, val planner: ModelProfile,
)

private typealias SaveProfile = (String?, String, ModelProfile, String, () -> Unit) -> Unit

@Composable private fun BlueButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, content = content)
}

@Composable
private fun ModelLibrary(
    configurations: List<ProfileStore.SavedProfile>, guiSlot: String?, plannerSlot: String?,
    enabled: Boolean, busy: Boolean, locked: Boolean = false, error: String = "",
    onSelect: (String, String?) -> Unit, onSave: SaveProfile, onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    var choosingRole by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ProfileStore.SavedProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProfileStore.SavedProfile?>(null) }
    var showProtocol by remember { mutableStateOf(false) }
    val gui = configurations.firstOrNull { it.slotId == guiSlot }
    val planner = configurations.firstOrNull { it.slotId == plannerSlot }

    Text(context.getString(R.string.ui_models), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelRoleButton("GUI Agent", gui, enabled) { choosingRole = "gui" }
        ModelRoleButton("Planner", planner, enabled) { choosingRole = "planner" }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(context.getString(R.string.ui_my_configs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = { creating = true }, enabled = enabled) { Text(context.getString(R.string.ui_add)) }
    }
    if (configurations.isEmpty() && !busy) Text(context.getString(R.string.ui_no_configs), color = MaterialTheme.colorScheme.onSurfaceVariant)
    configurations.forEach { record ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(record.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(record.profile.model, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(profileHost(context, record.profile), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (record.hasKey) context.getString(R.string.ui_key_saved) else context.getString(R.string.ui_key_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { editing = record }, enabled = enabled) { Text(context.getString(R.string.ui_edit)) }
                    TextButton(onClick = { deleting = record }, enabled = enabled) { Text(context.getString(R.string.ui_delete)) }
                }
            }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (error.isNotBlank() && !creating && editing == null) Text(UiMessages.localize(context, error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { showProtocol = true }, contentPadding = PaddingValues(0.dp)) {
        Text(context.getString(R.string.ui_execution_protocol), style = MaterialTheme.typography.bodySmall)
    }
    if (locked) Text(context.getString(R.string.ui_configs_locked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    choosingRole?.let { role ->
        ModelChoiceSheet(role, configurations, if (role == "gui") guiSlot else plannerSlot, enabled,
            onDismiss = { choosingRole = null }, onChoose = { slot -> choosingRole = null; onSelect(role, slot) })
    }
    if (creating || editing != null) ProfileEditorSheet(editing, enabled, busy, error,
        onDismiss = { if (!busy) { creating = false; editing = null } }, onSave = { name, profile, secret, onSaved ->
            onSave(editing?.slotId, name, profile, secret) { onSaved(); creating = false; editing = null }
        })
    deleting?.let { target ->
        val affected = listOfNotNull("GUI Agent".takeIf { guiSlot == target.slotId }, "Planner".takeIf { plannerSlot == target.slotId })
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(context.getString(R.string.ui_delete_config)) },
            text = { Text(context.getString(R.string.ui_delete_config_body, target.name) + if (affected.isEmpty()) "" else context.getString(R.string.ui_delete_config_affected, affected.joinToString(context.getString(R.string.ui_list_separator)))) },
            confirmButton = { TextButton(enabled = enabled, onClick = { deleting = null; onDelete(target.slotId) }) { Text(context.getString(R.string.ui_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(context.getString(R.string.ui_cancel)) } })
    }
    if (showProtocol) AlertDialog(onDismissRequest = { showProtocol = false }, title = { Text(context.getString(R.string.ui_api_details)) },
        text = { Text(context.getString(R.string.ui_api_details_body)) },
        confirmButton = { TextButton(onClick = { showProtocol = false }) { Text(context.getString(R.string.ui_got_it)) } })
}

@Composable private fun ModelRoleButton(title: String, record: ProfileStore.SavedProfile?, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(record?.name ?: context.getString(R.string.ui_choose_config), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", style = MaterialTheme.typography.titleLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ModelChoiceSheet(
    role: String, configurations: List<ProfileStore.SavedProfile>, selected: String?, enabled: Boolean,
    onDismiss: () -> Unit, onChoose: (String?) -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f).padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Text(if (role == "gui") "GUI Agent" else "Planner", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected == null, enabled = enabled, role = Role.RadioButton, onClick = { onChoose(null) }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == null, onClick = null, enabled = enabled)
                        Text(context.getString(R.string.ui_not_selected), Modifier.padding(start = 12.dp))
                    }
                }
                items(configurations, key = { it.slotId }) { record ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).selectable(record.slotId == selected, enabled = enabled, role = Role.RadioButton, onClick = { onChoose(record.slotId) }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(record.slotId == selected, onClick = null, enabled = enabled)
                        Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                            Text(record.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(record.profile.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ProfileEditorSheet(
    record: ProfileStore.SavedProfile?, enabled: Boolean, busy: Boolean, error: String,
    onDismiss: () -> Unit, onSave: (String, ModelProfile, String, () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val original = record?.profile
    var name by remember(original?.id) { mutableStateOf(record?.name.orEmpty()) }
    var url by remember(original?.id) { mutableStateOf(original?.baseUrl.orEmpty()) }
    var model by remember(original?.id) { mutableStateOf(original?.model.orEmpty()) }
    var secret by remember(original?.id) { mutableStateOf("") }
    val validUrl = runCatching { QwenGateway.endpoint(url.trim()); true }.getOrDefault(false)
    val changedService = original != null && runCatching { QwenGateway.endpoint(url.trim()) != QwenGateway.endpoint(original.baseUrl) }.getOrDefault(true)
    val canSave = enabled && name.trim().length in 1..80 && validUrl && model.isNotBlank() &&
        (secret.isNotBlank() || (record?.hasKey == true && !changedService))
    val canDismiss by rememberUpdatedState(!busy)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || canDismiss })
    ModalBottomSheet(onDismissRequest = { if (!busy) { secret = ""; onDismiss() } }, sheetState = sheetState,
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).imePadding().padding(horizontal = 24.dp)) {
            Text(if (record == null) context.getString(R.string.ui_new_config) else context.getString(R.string.ui_edit_config), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text(context.getString(R.string.ui_config_name)) }, placeholder = { Text(context.getString(R.string.ui_config_name_example)) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text(context.getString(R.string.ui_https_endpoint)) }, enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text(context.getString(R.string.ui_model_name)) }, enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text(if (record?.hasKey == true) context.getString(R.string.ui_key_keep_if_blank) else "API Key") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (changedService) Text(context.getString(R.string.ui_endpoint_changed_key), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isNotBlank()) Text(UiMessages.localize(context, error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            BlueButton(enabled = canSave, onClick = {
                val next = (original ?: ModelProfile()).copy(baseUrl = url.trim(), model = model.trim(), apiKey = "")
                onSave(name.trim(), next, secret) { secret = "" }
            }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).heightIn(min = 52.dp)) { Text(if (busy) context.getString(R.string.ui_saving) else context.getString(R.string.ui_save_encrypted)) }
        }
    }
}

private fun profileHost(context: Context, profile: ModelProfile): String = runCatching { Uri.parse(profile.baseUrl).host }.getOrNull().orEmpty().ifBlank { context.getString(R.string.ui_endpoint_not_set) }

/** Volatile task draft. Do not replace with SavedStateHandle/rememberSaveable or disk storage. */
@Composable private fun LanguageDialog(
    current: AppLanguage.Choice,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSelect: (AppLanguage.Choice) -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.language_title)) },
        text = {
            Column {
                if (!enabled) Text(context.getString(R.string.language_finish_task),
                    modifier = Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (choice in AppLanguage.Choice.entries) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = current == choice, enabled = enabled, role = Role.RadioButton,
                            onClick = { onSelect(choice) }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = current == choice, enabled = enabled, onClick = null)
                        Text(context.getString(when (choice) {
                            AppLanguage.Choice.SYSTEM -> R.string.language_system
                            AppLanguage.Choice.CHINESE -> R.string.language_chinese
                            AppLanguage.Choice.ENGLISH -> R.string.language_english
                        }), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.language_close)) } },
    )
}

private class TaskDraft {
    var task by mutableStateOf("")
    var harness by mutableStateOf(true)
    var budget by mutableStateOf("30")
    var consent by mutableStateOf(false)
    var allowed by mutableStateOf(emptySet<String>())
    var scopeReady by mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskComposer(
    draft: TaskDraft,
    apps: List<Pair<String, String>>,
    enabled: Boolean,
    serviceReady: Boolean,
    guiReady: Boolean,
    plannerReady: Boolean,
    gui: ModelProfile,
    planner: ModelProfile,
    guiName: String = "GUI",
    plannerName: String = "Planner",
    onModels: () -> Unit = {},
    scopeSaving: Boolean = false,
    scopeError: String = "",
    onSaveScope: (Set<String>, () -> Unit) -> Unit = { _, onSaved -> onSaved() },
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    var pickerOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var disclosureOpen by remember { mutableStateOf(false) }
    // A changed recipient or Harness setting requires fresh consent.
    LaunchedEffect(gui.id, gui.baseUrl, gui.model, planner.id, planner.baseUrl, planner.model, draft.harness) { draft.consent = false }
    val startBlockReason = when {
        !enabled -> when {
            scopeSaving -> context.getString(R.string.ui_scope_saving)
            !draft.scopeReady -> scopeError.ifBlank { context.getString(R.string.ui_scope_loading) }
            else -> context.getString(R.string.ui_task_preparing)
        }
        draft.task.isBlank() -> context.getString(R.string.ui_enter_task)
        draft.allowed.isEmpty() -> context.getString(R.string.ui_set_scope)
        !guiReady || (draft.harness && !plannerReady) -> context.getString(R.string.ui_choose_required_models)
        !serviceReady -> context.getString(R.string.ui_connect_required_controls)
        (draft.budget.toIntOrNull() ?: 0) <= 0 -> context.getString(R.string.ui_valid_budget_required)
        !draft.consent -> context.getString(R.string.ui_confirm_remote_send)
        else -> ""
    }
    // The body scrolls independently so the existing start action remains above the IME.
    // Task drafts and consent remain RAM-only; no state restoration or persistence is added.
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(context.getString(R.string.ui_task_heading), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            OutlinedTextField(
                value = draft.task, onValueChange = { draft.task = it }, enabled = enabled,
                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                label = { Text(context.getString(R.string.ui_task)) }, placeholder = { Text(context.getString(R.string.ui_task_placeholder)) },
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
            Text(context.getString(R.string.record_local_task_notice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickerOpen = true }, enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                    Text(context.getString(R.string.ui_scope_count, draft.allowed.size), style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = { settingsOpen = true }, enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                    Text(context.getString(R.string.ui_harness_mode, context.getString(if (draft.harness) R.string.ui_on else R.string.ui_off)), style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = { settingsOpen = true }, enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                    Text(context.getString(R.string.ui_steps, draft.budget.ifBlank { "—" }), style = MaterialTheme.typography.labelLarge)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(context.getString(R.string.ui_remote_services), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { disclosureOpen = true }) { Text(context.getString(R.string.ui_sending_details), style = MaterialTheme.typography.labelLarge) }
                }
                RecipientSummary("GUI", guiName, gui, guiReady, onModels)
                if (draft.harness) RecipientSummary("Planner", plannerName, planner, plannerReady, onModels)
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(value = draft.consent, enabled = enabled, role = Role.Checkbox, onValueChange = { draft.consent = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = draft.consent, onCheckedChange = null, enabled = enabled)
                    Text(context.getString(R.string.ui_remote_consent), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(end = 4.dp))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(UiMessages.localize(context, startBlockReason).ifBlank { " " }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            BlueButton(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp),
                enabled = enabled && serviceReady && draft.consent && guiReady && (!draft.harness || plannerReady) &&
                    draft.task.isNotBlank() && draft.allowed.isNotEmpty() && (draft.budget.toIntOrNull() ?: 0) > 0) {
                Text(context.getString(R.string.ui_start_task), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
    if (pickerOpen) AppAuthorizationSheet(apps, draft.allowed, enabled, scopeSaving, scopeError,
        onDismiss = { if (!scopeSaving) pickerOpen = false }, onSave = { selected -> onSaveScope(selected) { pickerOpen = false } })
    if (settingsOpen) ModalBottomSheet(onDismissRequest = { settingsOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(context.getString(R.string.ui_run_settings), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(context.getString(R.string.ui_harness_planning), style = MaterialTheme.typography.titleMedium)
                    Text(context.getString(R.string.ui_harness_planning_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(draft.harness, { draft.harness = it }, enabled = enabled)
            }
            OutlinedTextField(draft.budget, { if (it.all(Char::isDigit) && it.length <= 5) draft.budget = it },
                label = { Text(context.getString(R.string.ui_gui_total_steps)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(context.getString(R.string.ui_budget_details), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BlueButton(onClick = { settingsOpen = false }, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.ui_done)) }
        }
    }
    if (disclosureOpen) ModalBottomSheet(onDismissRequest = { disclosureOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(context.getString(R.string.ui_remote_send_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(context.getString(if (draft.harness) R.string.ui_remote_send_on else R.string.ui_remote_send_off))
            Text("GUI Agent\n${gui.baseUrl}\n${gui.model}", style = MaterialTheme.typography.bodyMedium)
            if (draft.harness) Text("Planner\n${planner.baseUrl}\n${planner.model}", style = MaterialTheme.typography.bodyMedium)
            Text(context.getString(R.string.ui_image_retention_details), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BlueButton(onClick = { disclosureOpen = false }, modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.ui_got_it)) }
        }
    }
}

@Composable private fun RecipientSummary(role: String, name: String, profile: ModelProfile, ready: Boolean, onModels: () -> Unit) {
    val context = LocalContext.current
    val host = if (ready) profileHost(context, profile) else context.getString(R.string.ui_not_configured)
    TextButton(onClick = onModels, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(10.dp)) {
            Box(Modifier.size(32.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                Text(role.take(1), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.Start) {
            Text(context.getString(R.string.ui_recipient_name, role, name.ifBlank { context.getString(R.string.ui_choose_model) }), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", modifier = Modifier.padding(end = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AppAuthorizationSheet(
    apps: List<Pair<String, String>>, savedScope: Set<String>, enabled: Boolean, busy: Boolean, error: String,
    onDismiss: () -> Unit, onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(savedScope.toSet()) }
    val filtered = apps.filter { search.isBlank() || it.second.contains(search, true) || it.first.contains(search, true) }
    val canDismiss by rememberUpdatedState(!busy)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || canDismiss })
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).imePadding().padding(horizontal = 24.dp)) {
            Text(context.getString(R.string.ui_authorize_apps), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(context.getString(R.string.ui_scope_details), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            OutlinedTextField(search, { search = it }, label = { Text(context.getString(R.string.ui_search_apps)) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(context.getString(R.string.ui_selected_count, checked.size), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { checked = emptySet() }, enabled = enabled && checked.isNotEmpty()) { Text(context.getString(R.string.ui_clear)) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (filtered.isEmpty()) item { Text(context.getString(R.string.ui_no_apps_found), Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(filtered, key = { it.first }) { (pkg, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(pkg in checked, enabled = enabled, role = Role.Checkbox,
                        onValueChange = { selected -> checked = if (selected) checked + pkg else checked - pkg }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(pkg in checked, onCheckedChange = null, enabled = enabled)
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (error.isNotBlank()) Text(UiMessages.localize(context, error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            BlueButton(onClick = { onSave(checked.toSet()) }, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text(if (busy) context.getString(R.string.ui_saving) else context.getString(R.string.ui_save_scope)) }
        }
    }
}

@Preview(name = "TaskEmpty", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
internal fun TaskEmptyPreview() { TaskPreview(false, false) }

@Preview(name = "TaskReady", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
internal fun TaskReadyPreview() { TaskPreview(true, false) }

@Preview(name = "TaskReadyCompactLargeText", widthDp = 360, heightDp = 720, fontScale = 1.3f, showBackground = true)
@Composable
internal fun TaskReadyCompactLargeTextPreview() { TaskPreview(true, false) }

@Preview(name = "TaskReadyLandscape", widthDp = 752, heightDp = 393, showBackground = true)
@Composable
internal fun TaskReadyLandscapePreview() { TaskPreview(true, false) }

@Preview(name = "TaskDark", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
internal fun TaskDarkPreview() { TaskPreview(true, true) }

/** Synthetic, offline preview: no service, credentials, package enumeration, or remote call. */
@Composable private fun TaskPreview(selected: Boolean, dark: Boolean) {
    val context = LocalContext.current
    MobileHarnessTheme(darkTheme = dark) {
        val draft = remember { TaskDraft().apply {
            scopeReady = true
            if (selected) { task = context.getString(R.string.ui_preview_task); allowed = setOf("example.calculator") }
        } }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("MobileHarness", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {}) { Text(context.getString(R.string.ui_connected_badge)) }
                }
                TabRow(0, containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) { listOf(context.getString(R.string.ui_task), context.getString(R.string.ui_models), context.getString(R.string.ui_records)).forEachIndexed { i, title -> Tab(i == 0, onClick = {}, text = { Text(title) }) } }
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TaskComposer(draft, listOf("example.calculator" to context.getString(R.string.ui_preview_calculator)), true, true, true, true,
                        ModelProfile(baseUrl = "https://api.example.com/v1", model = "qwen3.8-max", id = "preview-gui"),
                        ModelProfile(baseUrl = "https://api.example.com/v1", model = "qwen3.8-max", id = "preview-planner"),
                        guiName = context.getString(R.string.ui_preview_primary), plannerName = context.getString(R.string.ui_preview_backup), onModels = {}, onStart = {})
                }
            }
        }
    }
}

@Preview(name = "ModelLibrary", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
internal fun ModelLibraryPreview() {
    val context = LocalContext.current
    MobileHarnessTheme {
        val records = remember { listOf(
            ProfileStore.SavedProfile("preview-a", context.getString(R.string.ui_preview_primary), ModelProfile(baseUrl = "https://api.example.com/v1", model = "qwen3.8-max", id = "revision-a"), true),
            ProfileStore.SavedProfile("preview-b", context.getString(R.string.ui_preview_backup), ModelProfile(baseUrl = "https://backup.example.com/v1", model = "qwen3.8-max", id = "revision-b"), true),
        ) }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("MobileHarness", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {}) { Text(context.getString(R.string.ui_connected_badge)) }
                }
                TabRow(1) { listOf(context.getString(R.string.ui_task), context.getString(R.string.ui_models), context.getString(R.string.ui_records)).forEachIndexed { i, title -> Tab(i == 1, onClick = {}, text = { Text(title) }) } }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ModelLibrary(records, "preview-a", "preview-b", enabled = true, busy = false,
                        onSelect = { _, _ -> }, onSave = { _, _, _, _, done -> done() }, onDelete = {})
                }
            }
        }
    }
}
