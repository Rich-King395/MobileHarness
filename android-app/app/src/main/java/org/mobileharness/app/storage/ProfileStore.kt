package org.mobileharness.app.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.mobileharness.app.core.ModelProfile
import org.mobileharness.app.model.QwenGateway
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted configuration library. Metadata never includes a cached or returned plaintext key. */
class ProfileStore(context: Context) {
    data class SavedProfile(val slotId: String, val name: String, val profile: ModelProfile, val hasKey: Boolean)

    private val directory = File(context.noBackupFilesDir, "profiles").apply { check(mkdirs() || isDirectory) }
    private val catalog = AtomicFile(File(directory, "catalog-v2.enc"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun decrypt(bytes: ByteArray): JSONObject {
        require(bytes.size > 29) { "INVALID_PROFILE_DATA" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        return try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
    }

    private fun readCatalog(): JSONObject? {
        val bytes = try { catalog.openRead().use { it.readBytes() } }
        catch (_: FileNotFoundException) {
            // Absence is different from an unreadable or corrupt existing catalog.
            if (catalog.baseFile.exists() || File(catalog.baseFile.path + ".bak").exists()) throw IllegalStateException("PROFILE_READ_FAILED")
            return null
        }
        return decrypt(bytes).also(::validateCatalog)
    }

    private fun writeCatalog(value: JSONObject) {
        validateCatalog(value)
        val plain = value.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = try { cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        val stream = catalog.startWrite()
        try {
            stream.write(encrypted)
            catalog.finishWrite(stream)
        } catch (failure: Exception) {
            catalog.failWrite(stream)
            throw failure
        } finally { encrypted.fill(0) }
        // AtomicFile can report close/rename failures through platform logging instead of
        // throwing. Authenticate and compare the saved data before removing legacy sources.
        check(sameCatalog(value, readCatalog() ?: error("PROFILE_WRITE_FAILED"))) { "PROFILE_WRITE_FAILED" }
    }

    private fun load(): JSONObject {
        readCatalog()?.let { cleanLegacyFiles(); return it }
        val value = JSONObject().put("version", 2).put("profiles", JSONArray()).put("selected", JSONObject())
        var migrated = false
        for (role in ROLES) {
            val source = File(directory, "$role.enc")
            if (!source.exists()) continue
            val old = decrypt(source.readBytes())
            val slot = UUID.randomUUID().toString()
            value.getJSONArray("profiles").put(JSONObject()
                .put("slot_id", slot).put("name", if (role == "gui") "GUI 当前配置" else "Planner 当前配置")
                .put("base_url", old.getString("base_url")).put("model", old.getString("model"))
                .put("id", old.getString("id")).put("key", old.getString("key")))
            value.getJSONObject("selected").put(role, slot)
            migrated = true
        }
        validateCatalog(value)
        if (migrated) {
            // Read both roles before committing. Never merge equal endpoints or clear a
            // source after an unsuccessful write of the authenticated new catalog.
            writeCatalog(value)
            cleanLegacyFiles()
        }
        return value
    }

    private fun cleanLegacyFiles() {
        for (role in ROLES) {
            for (suffix in listOf("enc", "pending")) {
                val source = File(directory, "$role.$suffix")
                check(!source.exists() || source.delete()) { "PROFILE_CLEANUP_FAILED" }
            }
        }
    }

    private fun validateCatalog(value: JSONObject) {
        require(value.getInt("version") == 2) { "INVALID_PROFILE_VERSION" }
        val slots = HashSet<String>()
        val revisions = HashSet<String>()
        val entries = value.getJSONArray("profiles")
        for (index in 0 until entries.length()) {
            val row = entries.getJSONObject(index)
            require(isUuid(row.getString("slot_id")) && slots.add(row.getString("slot_id"))) { "INVALID_PROFILE_SLOT" }
            require(isUuid(row.getString("id")) && revisions.add(row.getString("id"))) { "INVALID_PROFILE_REVISION" }
            require(validName(row.getString("name"))) { "INVALID_PROFILE_NAME" }
            require(row.getString("model").isNotBlank()) { "INVALID_PROFILE_MODEL" }
            QwenGateway.endpoint(row.getString("base_url"))
            row.getString("key")
        }
        val selections = value.getJSONObject("selected")
        require(selections.keys().asSequence().all { it in ROLES }) { "INVALID_PROFILE_ROLE" }
        for (role in ROLES) if (selections.has(role)) require(selections.getString(role) in slots) { "INVALID_PROFILE_SELECTION" }
    }

    private fun sameCatalog(first: JSONObject, second: JSONObject): Boolean {
        if (first.getInt("version") != second.getInt("version")) return false
        val a = first.getJSONArray("profiles")
        val b = second.getJSONArray("profiles")
        if (a.length() != b.length()) return false
        for (index in 0 until a.length()) {
            for (field in listOf("slot_id", "name", "base_url", "model", "id", "key")) {
                if (a.getJSONObject(index).getString(field) != b.getJSONObject(index).getString(field)) return false
            }
        }
        return ROLES.all { first.getJSONObject("selected").optString(it) == second.getJSONObject("selected").optString(it) }
    }

    private fun entry(value: JSONObject, slotId: String): JSONObject? {
        val rows = value.getJSONArray("profiles")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("slot_id") == slotId }
    }

    private fun metadata(row: JSONObject) = SavedProfile(row.getString("slot_id"), row.getString("name"),
        ModelProfile(row.getString("base_url"), row.getString("model"), "", row.getString("id")), row.getString("key").isNotBlank())

    fun list(): List<SavedProfile> = synchronized(LOCK) {
        val rows = load().getJSONArray("profiles")
        (0 until rows.length()).map { metadata(rows.getJSONObject(it)) }
    }

    fun selectedId(role: String): String? = synchronized(LOCK) {
        requireRole(role)
        load().getJSONObject("selected").optString(role).ifEmpty { null }
    }

    fun select(role: String, slotId: String?): Unit = synchronized(LOCK) {
        requireRole(role)
        val value = load()
        if (slotId == null) value.getJSONObject("selected").remove(role)
        else {
            require(isUuid(slotId) && entry(value, slotId) != null) { "PROFILE_NOT_FOUND" }
            value.getJSONObject("selected").put(role, slotId)
        }
        writeCatalog(value)
    }

    fun saveConfiguration(slotId: String?, name: String, profile: ModelProfile, newKey: String): SavedProfile = synchronized(LOCK) {
        val value = load()
        saveEntry(value, slotId, name, profile, newKey).also { writeCatalog(value) }
    }

    private fun saveEntry(value: JSONObject, slotId: String?, name: String, profile: ModelProfile, newKey: String): SavedProfile {
        val label = name.trim()
        require(validName(label)) { "INVALID_PROFILE_NAME" }
        require(profile.model.isNotBlank()) { "INVALID_PROFILE_MODEL" }
        val endpoint = QwenGateway.endpoint(profile.baseUrl.trim())
        val old = slotId?.let {
            require(isUuid(it)) { "INVALID_PROFILE_SLOT" }
            entry(value, it) ?: throw IllegalArgumentException("PROFILE_NOT_FOUND")
        }
        if (newKey.isBlank() && old != null && old.getString("key").isNotBlank()) {
            require(QwenGateway.endpoint(old.getString("base_url")) == endpoint) { "KEY_REQUIRED_FOR_NEW_SERVICE" }
        }
        val secret = newKey.ifBlank { old?.getString("key").orEmpty() }
        require(secret.isNotBlank()) { "KEY_REQUIRED" }
        val next = JSONObject().put("slot_id", slotId ?: UUID.randomUUID().toString()).put("name", label)
            .put("base_url", profile.baseUrl.trim()).put("model", profile.model.trim())
            .put("id", UUID.randomUUID().toString()).put("key", secret)
        val rows = value.getJSONArray("profiles")
        if (old == null) rows.put(next)
        else for (index in 0 until rows.length()) if (rows.getJSONObject(index).getString("slot_id") == slotId) rows.put(index, next)
        return metadata(next)
    }

    fun deleteConfiguration(slotId: String): Unit = synchronized(LOCK) {
        require(isUuid(slotId)) { "INVALID_PROFILE_SLOT" }
        val value = load()
        val rows = value.getJSONArray("profiles")
        val kept = JSONArray()
        for (index in 0 until rows.length()) if (rows.getJSONObject(index).getString("slot_id") != slotId) kept.put(rows.getJSONObject(index))
        value.put("profiles", kept)
        for (role in ROLES) if (value.getJSONObject("selected").optString(role) == slotId) value.getJSONObject("selected").remove(role)
        writeCatalog(value)
    }

    /** Compatibility API: the role now points at a library configuration. */
    fun metadata(role: String): ModelProfile = synchronized(LOCK) {
        requireRole(role)
        val value = load()
        entry(value, value.getJSONObject("selected").optString(role))?.let { metadata(it).profile } ?: ModelProfile()
    }

    fun hasKey(role: String): Boolean = synchronized(LOCK) {
        requireRole(role)
        val value = load()
        !entry(value, value.getJSONObject("selected").optString(role))?.getString("key").isNullOrBlank()
    }

    fun save(role: String, profile: ModelProfile, newKey: String): ModelProfile = synchronized(LOCK) {
        requireRole(role)
        val value = load()
        val selected = value.getJSONObject("selected").optString(role).ifEmpty { null }
        val old = selected?.let { entry(value, it) }
        val saved = saveEntry(value, selected, old?.getString("name") ?: if (role == "gui") "GUI 当前配置" else "Planner 当前配置", profile, newKey)
        value.getJSONObject("selected").put(role, saved.slotId)
        writeCatalog(value)
        saved.profile
    }

    fun keyFor(profile: ModelProfile): String = synchronized(LOCK) {
        val rows = load().getJSONArray("profiles")
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            if (row.getString("id") == profile.id && row.getString("model") == profile.model &&
                QwenGateway.endpoint(row.getString("base_url")) == QwenGateway.endpoint(profile.baseUrl)) return@synchronized row.getString("key")
        }
        ""
    }

    /** Unbind only this role. A shared configuration remains available to its other users. */
    fun delete(role: String) { select(role, null) }

    private fun requireRole(role: String) = require(role in ROLES) { "INVALID_PROFILE_ROLE" }
    private fun validName(value: String) = value.isNotBlank() && value.length <= 80 && value.none(Char::isISOControl)
    private fun isUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private companion object {
        const val KEY_ALIAS = "mobileharness.profile.encryption.v1"
        val ROLES = listOf("gui", "planner")
        // Serialize multiple store instances that may share the same private directory.
        val LOCK = Any()
    }
}
