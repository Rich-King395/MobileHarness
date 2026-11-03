package org.mobileharness.app.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local-only task attachment. Never part of the content-free event log or its export. */
class LocalTaskRecordStore(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    enum class Status { AVAILABLE, LEGACY_MISSING, UNAVAILABLE }

    data class Lookup(val status: Status, val task: String? = null) {
        init { require((status == Status.AVAILABLE) == (task != null)) { "INVALID_TASK_LOOKUP" } }
        // A diagnostic dump of a lookup must not turn into an accidental task-content log.
        override fun toString(): String = "TaskLookup(status=$status)"
    }

    // Do not touch storage in the constructor: a blocked directory must fail through
    // begin/save's audit gate, rather than crashing a screen that merely obtains its audit.
    private val directory = File(context.noBackupFilesDir, "local-task-records")
    init {
        require(keyAlias.length <= 128 && keyAlias.matches(Regex("[a-zA-Z0-9._-]+"))) {
            "INVALID_LOCAL_TASK_KEY_ALIAS"
        }
    }

    /** Fail closed before task execution if the encrypted original cannot be committed. */
    fun save(sessionId: String, task: String): Unit = synchronized(LOCK) {
        requireId(sessionId)
        require(task.isNotBlank()) { "INVALID_LOCAL_TASK" }
        val target = attachment(sessionId)
        var newAttachment = false
        try {
            check(directory.isDirectory || directory.mkdirs()) { "LOCAL_TASK_DIRECTORY_UNAVAILABLE" }
            if (attachmentFiles(target).any { it.exists() }) {
                // A session ID cannot be repurposed for a different original task.
                check(read(sessionId).let { it.status == Status.AVAILABLE && it.task == task }) {
                    "LOCAL_TASK_WRITE_FAILED"
                }
                return@synchronized
            }
            newAttachment = true
            val plain = JSONObject().put("schema_version", SCHEMA_VERSION)
                .put("session_id", sessionId).put("task", task).toString().toByteArray(Charsets.UTF_8)
            val encrypted = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(create = true))
                cipher.updateAAD(associatedData(sessionId))
                cipher.iv + cipher.doFinal(plain)
            } finally { plain.fill(0) }
            try {
                val stream = target.startWrite()
                try {
                    stream.write(encrypted)
                    target.finishWrite(stream)
                } catch (_: Exception) {
                    target.failWrite(stream)
                    throw IllegalStateException("LOCAL_TASK_WRITE_FAILED")
                }
            } finally { encrypted.fill(0) }
            // AtomicFile may log a close/rename failure without throwing. Authenticate and
            // compare the committed attachment before allowing session_started to be written.
            check(read(sessionId).let { it.status == Status.AVAILABLE && it.task == task }) {
                "LOCAL_TASK_WRITE_FAILED"
            }
        } catch (_: Exception) {
            if (newAttachment) delete(sessionId)
            // No JSON, provider exception or task text is exposed through the failure message.
            throw IllegalStateException("LOCAL_TASK_WRITE_FAILED")
        }
    }

    fun read(sessionId: String): Lookup = synchronized(LOCK) {
        requireId(sessionId)
        try {
            if (!directory.exists()) return@synchronized Lookup(Status.LEGACY_MISSING)
            if (!directory.isDirectory) return@synchronized Lookup(Status.UNAVAILABLE)
        } catch (_: Exception) { return@synchronized Lookup(Status.UNAVAILABLE) }
        val target = attachment(sessionId)
        val encrypted = try { target.openRead().use { it.readBytes() } }
        catch (_: FileNotFoundException) {
            return@synchronized Lookup(if (attachmentFiles(target).any { it.exists() })
                Status.UNAVAILABLE else Status.LEGACY_MISSING)
        } catch (_: Exception) { return@synchronized Lookup(Status.UNAVAILABLE) }
        try {
            check(encrypted.size > IV_BYTES + TAG_BYTES) { "INVALID_LOCAL_TASK" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(create = false),
                GCMParameterSpec(TAG_BYTES * 8, encrypted.copyOfRange(0, IV_BYTES)))
            cipher.updateAAD(associatedData(sessionId))
            val plain = cipher.doFinal(encrypted.copyOfRange(IV_BYTES, encrypted.size))
            try {
                val value = JSONObject(String(plain, Charsets.UTF_8))
                check(value.keys().asSequence().toSet() == setOf("schema_version", "session_id", "task") &&
                    value.opt("schema_version") == SCHEMA_VERSION && value.opt("session_id") == sessionId &&
                    value.opt("task") is String) { "INVALID_LOCAL_TASK" }
                val task = value.getString("task")
                check(task.isNotBlank()) { "INVALID_LOCAL_TASK" }
                Lookup(Status.AVAILABLE, task)
            } finally { plain.fill(0) }
        } catch (_: Exception) { Lookup(Status.UNAVAILABLE) }
        finally { encrypted.fill(0) }
    }

    /** Delete ciphertext even if its key is missing or it cannot be authenticated. */
    fun delete(sessionId: String): Boolean = synchronized(LOCK) {
        requireId(sessionId)
        val target = attachment(sessionId)
        try {
            if (!directory.exists()) return@synchronized true
            if (!directory.isDirectory) return@synchronized false
            target.delete()
            // Some platform AtomicFile implementations predate the .new sidecar. Verify and
            // remove every known transaction file, including a failed/abandoned new write.
            attachmentFiles(target).forEach { if (it.exists()) it.delete() }
            attachmentFiles(target).none { it.exists() }
        } catch (_: Exception) { false }
    }

    /** Startup recovery only: caller protects every valid or unreadable session log. */
    fun deleteOrphans(protectedSessionIds: Set<String>): Boolean = synchronized(LOCK) {
        require(protectedSessionIds.all(UUID_PATTERN::matches)) { "INVALID_SESSION_ID" }
        val files = try {
            if (!directory.exists()) return@synchronized true
            directory.listFiles() ?: return@synchronized false
        } catch (_: Exception) { return@synchronized false }
        val ids = files.mapNotNull { file ->
            val name = file.name.removeSuffix(".new").removeSuffix(".bak")
            if (!name.endsWith(".enc")) null
            else name.removeSuffix(".enc").takeIf(UUID_PATTERN::matches)
        }.toSet()
        ids.filter { it !in protectedSessionIds }.map(::delete).all { it }
    }

    private fun encryptionKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(create) { "LOCAL_TASK_KEY_UNAVAILABLE" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun attachment(id: String) = AtomicFile(File(directory, "$id.enc"))
    private fun attachmentFiles(file: AtomicFile) = listOf(file.baseFile,
        File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new"))
    private fun associatedData(id: String) = "mobileharness.local-task.v$SCHEMA_VERSION:$id".toByteArray(Charsets.UTF_8)
    private fun requireId(id: String) = require(UUID_PATTERN.matches(id)) { "INVALID_SESSION_ID" }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val DEFAULT_KEY_ALIAS = "mobileharness.local-task.encryption.v1"
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        // Multiple audit/store instances, including an isolated debug context, share a key.
        val LOCK = Any()
    }
}
