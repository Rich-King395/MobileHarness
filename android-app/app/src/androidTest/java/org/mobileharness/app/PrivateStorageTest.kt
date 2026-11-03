package org.mobileharness.app

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mobileharness.app.core.ModelProfile
import org.mobileharness.app.core.SessionOptions
import org.mobileharness.app.storage.MinimalAudit
import org.mobileharness.app.storage.ProfileStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Tests only synthetic content in an isolated private subdirectory; no phone observations. */
@RunWith(AndroidJUnit4::class)
class PrivateStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isolated(block: (Context, File) -> Unit) {
        val base = context.noBackupFilesDir.canonicalFile
        val folder = File(base, "acceptance-${UUID.randomUUID()}").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }
        try { block(wrapped, folder) } finally {
            check(folder.canonicalFile.parentFile == base && folder.name.startsWith("acceptance-"))
            folder.deleteRecursively()
        }
    }

    @Test fun encryptedProfileAndCrossServiceIsolation() = isolated { ctx, directory ->
        val secret = "synthetic-test-credential-never-used-remotely"
        val store = ProfileStore(ctx)
        val saved = store.save("gui", ModelProfile(baseUrl = "https://first.example/v1"), secret)
        assertEquals("", saved.apiKey)
        assertEquals("", store.metadata("gui").apiKey)
        assertEquals(secret, store.keyFor(saved))
        assertFalse(File(directory, "profiles/catalog-v2.enc").readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        try {
            store.save("gui", saved.copy(baseUrl = "https://second.example/v1"), "")
            fail("Cross-service implicit credential reuse was accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals("https://first.example/v1", store.metadata("gui").baseUrl)
        val sameService = store.save("gui", saved.copy(baseUrl = "https://first.example/v1/chat/completions"), "")
        assertEquals(secret, store.keyFor(sameService))
        val changed = store.save("gui", sameService.copy(baseUrl = "https://second.example/v1"), "synthetic-second-credential")
        assertEquals("synthetic-second-credential", store.keyFor(changed))
        store.delete("gui")
        assertFalse(store.hasKey("gui"))
    }

    @Test fun minimalAuditRejectsContentAndSealsInterruptedSessions() = isolated { ctx, directory ->
        val audit = MinimalAudit(ctx)
        val profile = ModelProfile(apiKey = "SYNTHETIC_SECRET")
        val id = UUID.randomUUID().toString()
        audit.begin(id, SessionOptions("SYNTHETIC_TASK", "org.synthetic", setOf("org.synthetic"), false, 6, profile, profile.copy(id = UUID.randomUUID().toString())))
        audit.event("action", mapOf("phase" to "ORIGINAL", "gui_step" to 1, "action_type" to "input_text", "result" to "completed"))
        audit.event("action", mapOf("phase" to "ORIGINAL", "gui_step" to 1, "action_type" to "input_text", "result" to "completed", "text" to "SYNTHETIC_INPUT"))
        audit.event("error", mapOf("category" to "MODEL", "code" to "INVALID_OUTPUT"))
        val path = File(directory, "events/$id.jsonl")
        val records = path.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        assertEquals(3, records.size)
        assertFalse(path.readText().contains("SYNTHETIC"))
        assertEquals("", audit.exportMetadata(setOf(id))) // unfinished sessions cannot be exported
        records.forEach { assertEquals(setOf("schema_version", "session_id", "seq", "elapsed_ms", "event", "data"), it.keys().asSequence().toSet()) }
        // A killed process may leave the next JSON record only partly written.
        path.appendText("{\"schema_version\":")
        MinimalAudit(ctx).markInterrupted()
        audit.exportMetadata(setOf(id)).lineSequence().filter { it.isNotBlank() }.forEach { JSONObject(it) }
        val last = JSONObject(audit.exportMetadata(setOf(id)).trim().lineSequence().last())
        assertEquals("interrupted", last.getJSONObject("data").getString("reason"))
        assertFalse(last.getJSONObject("data").has("task_success"))
        audit.deleteAll()
        assertEquals("", audit.exportMetadata(setOf(id)))
        assertEquals("", audit.exportMetadata(emptySet()))
    }

    @Test fun sessionSelectionDeletionAndInvalidIdsStayWithinChosenMetadata() = isolated { ctx, directory ->
        val audit = MinimalAudit(ctx)
        val profile = ModelProfile(apiKey = "SYNTHETIC_NOT_EXPORTED")
        val options = SessionOptions("SYNTHETIC_TASK_NOT_EXPORTED", "org.synthetic", setOf("org.synthetic"), false, 6,
            profile, profile.copy(id = UUID.randomUUID().toString()))
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        fun finishSession(id: String, used: Int) {
            audit.begin(id, options)
            for (index in 1..used) {
                val call = mapOf("role" to "GUI", "phase" to "ORIGINAL", "call_index" to index,
                    "gui_steps_used" to index, "planner_calls_used" to 0, "duration_ms" to 0)
                audit.event("model_call", call + ("status" to "started"))
                audit.event("model_call", call + ("status" to "succeeded"))
            }
            audit.event("session_ended", mapOf("reason" to "user_stop", "completion_source" to "none",
                "gui_steps_used" to used, "planner_calls_used" to 0))
        }
        fun exportedIds(ids: Set<String>) = audit.exportMetadata(ids).lineSequence().filter { it.isNotBlank() }
            .map { JSONObject(it).getString("session_id") }.toSet()
        finishSession(first, 1)
        finishSession(second, 2)
        val summaries = audit.sessionSummaries()
        assertEquals(setOf(first, second), summaries.map { it.id }.toSet())
        assertTrue(summaries.all { it.ended && it.reason == "user_stop" && it.budget == 6 })
        assertEquals(1, summaries.single { it.id == first }.guiUsed)
        assertEquals(2, summaries.single { it.id == second }.guiUsed)
        assertEquals(setOf(first), exportedIds(setOf(first)))
        assertEquals(setOf(second), exportedIds(setOf(second)))
        assertEquals(setOf(first, second), exportedIds(setOf(first, second)))
        assertEquals("", audit.exportMetadata(emptySet()))
        assertFalse(audit.exportMetadata(setOf(first, second)).contains("SYNTHETIC"))
        // A picker receives a frozen copy, never the later mutable UI selection.
        val selection = mutableSetOf(first)
        val frozen = selection.toSet()
        selection.clear(); selection.add(second)
        assertEquals(setOf(first), exportedIds(frozen))
        val absent = UUID.randomUUID().toString()
        assertEquals("", audit.exportMetadata(setOf(absent)))
        assertFalse(audit.deleteSession(absent))
        for (invalid in listOf("../profiles/gui.enc", "", "not-a-session-id")) {
            try { audit.exportMetadata(setOf(first, invalid)); fail("Invalid export identifier accepted") }
            catch (_: IllegalArgumentException) { }
            try { audit.deleteSession(invalid); fail("Invalid delete identifier accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(audit.deleteSession(first))
        assertEquals(setOf(second), audit.sessionSummaries().map { it.id }.toSet())
        assertEquals(setOf(second), exportedIds(setOf(first, second)))
        val active = UUID.randomUUID().toString()
        audit.begin(active, options)
        assertFalse(audit.deleteSession(active))
        assertEquals("", audit.exportMetadata(setOf(active)))
        audit.event("task_assessment", mapOf("assessment" to "user_reported_complete"))
        val activePath = File(directory, "events/$active.jsonl")
        assertEquals(1, activePath.readLines().size) // no assessment of an unfinished task
        audit.deleteAll()
        assertEquals(setOf(active), audit.sessionSummaries().map { it.id }.toSet())
        audit.event("session_ended", mapOf("reason" to "user_stop", "completion_source" to "none",
            "gui_steps_used" to 0, "planner_calls_used" to 0))
        assertTrue(audit.deleteSession(active))
        audit.event("task_assessment", mapOf("assessment" to "user_reported_incomplete"))
        assertFalse(activePath.exists()) // deleted current session cannot reappear as a ghost record
        assertEquals(0, audit.count())
    }

    @Test fun packagedContractAndComponentBoundaries() {
        val bytes = context.assets.open("prompts/v1.1.0/manifest.json").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("0434d82c2f01bc4bd39b93df1b65ef176c25031760ef86d9b6ec8988f4c294ad", hash)
        val manager = context.packageManager
        val service = manager.getServiceInfo(ComponentName(context, "org.mobileharness.app.platform.HarnessAccessibilityService"), 0)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.permission)
        val receiver = manager.getReceiverInfo(ComponentName(context, "org.mobileharness.app.platform.ControlReceiver"), 0)
        assertFalse(receiver.exported)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
