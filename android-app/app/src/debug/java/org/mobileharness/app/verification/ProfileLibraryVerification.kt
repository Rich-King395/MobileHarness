package org.mobileharness.app.verification

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import org.mobileharness.app.core.ModelProfile
import org.mobileharness.app.storage.ProfileStore
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Synthetic storage-only checks. Never accesses the user's ProfileStore or a remote model. */
class ProfileLibraryVerification(private val context: Context) {
    data class CaseResult(val id: String, val passed: Boolean, val code: String)

    fun runAll(report: (CaseResult) -> Unit) {
        val cases = listOf<Pair<String, () -> Unit>>(
            "PL-01" to ::legacyMigration,
            "PL-02" to ::sharedSelectionsAndDeletion,
            "PL-03" to ::revisionAndEndpointIsolation,
            "PL-04" to ::atomicRecoveryAndCorruptCatalog,
            "PL-05" to ::failedMigrationReadKeepsLegacy,
            "PL-06" to ::failedMigrationWriteKeepsLegacy,
        )
        for ((id, body) in cases) {
            try { body(); report(CaseResult(id, true, "OK")) }
            catch (_: Exception) { report(CaseResult(id, false, "SYNTHETIC_STORAGE_ASSERTION_FAILED")); return }
        }
    }

    private fun legacyMigration() = isolated { ctx, folder ->
        val firstId = UUID.randomUUID().toString()
        val secondId = UUID.randomUUID().toString()
        legacy(folder, "gui", firstId, FIRST_SECRET)
        legacy(folder, "planner", secondId, SECOND_SECRET)
        val profiles = File(folder, "profiles")
        File(profiles, "gui.pending").writeBytes(File(profiles, "gui.enc").readBytes())
        File(profiles, "planner.pending").writeBytes(File(profiles, "planner.enc").readBytes())
        val store = ProfileStore(ctx)
        val rows = store.list()
        require(rows.size == 2 && rows.map { it.slotId }.distinct().size == 2)
        require(store.selectedId("gui") != store.selectedId("planner"))
        require(store.metadata("gui").id == firstId && store.metadata("planner").id == secondId)
        require(store.keyFor(store.metadata("gui")) == FIRST_SECRET)
        require(store.keyFor(store.metadata("planner")) == SECOND_SECRET)
        require(rows.all { it.hasKey && it.profile.apiKey.isEmpty() })
        require(listOf("gui.enc", "planner.enc", "gui.pending", "planner.pending").none { File(profiles, it).exists() })
        require(ProfileStore(ctx).list().map { it.slotId } == rows.map { it.slotId })
        require(ciphertextOnly(profiles, FIRST_SECRET, SECOND_SECRET))
    }

    private fun sharedSelectionsAndDeletion() = isolated { ctx, _ ->
        val store = ProfileStore(ctx)
        val first = store.saveConfiguration(null, "SYNTHETIC A", profile(), FIRST_SECRET)
        val second = store.saveConfiguration(null, "SYNTHETIC B", profile(), SECOND_SECRET)
        require(store.selectedId("gui") == null && store.selectedId("planner") == null)
        store.select("gui", first.slotId)
        store.select("planner", first.slotId)
        require(store.metadata("gui").id == first.profile.id && store.metadata("planner").id == first.profile.id)
        store.select("gui", second.slotId)
        require(store.metadata("planner").id == first.profile.id && store.metadata("gui").id == second.profile.id)
        store.delete("gui")
        require(!store.hasKey("gui") && store.list().size == 2 && store.hasKey("planner"))
        store.select("gui", first.slotId)
        store.deleteConfiguration(first.slotId)
        require(store.selectedId("gui") == null && store.selectedId("planner") == null)
        require(!store.hasKey("gui") && !store.hasKey("planner"))
        require(store.list().single().slotId == second.slotId && store.keyFor(first.profile).isEmpty())
        rejects { store.select("gui", "../profiles/gui.enc") }
        require(store.list().single().slotId == second.slotId)
    }

    private fun revisionAndEndpointIsolation() = isolated { ctx, folder ->
        val store = ProfileStore(ctx)
        val first = store.saveConfiguration(null, "SYNTHETIC A", profile(), FIRST_SECRET)
        store.select("gui", first.slotId)
        require(store.keyFor(first.profile.copy(model = "other-model")).isEmpty())
        require(store.keyFor(first.profile.copy(baseUrl = "https://second.example/v1")).isEmpty())
        rejects { store.saveConfiguration(first.slotId, first.name, first.profile.copy(baseUrl = "https://second.example/v1"), "") }
        require(store.metadata("gui").id == first.profile.id && store.keyFor(first.profile) == FIRST_SECRET)
        val edited = store.saveConfiguration(first.slotId, "SYNTHETIC edited", first.profile.copy(model = "other-model",
            baseUrl = "https://first.example/v1/chat/completions"), "")
        require(edited.slotId == first.slotId && edited.profile.id != first.profile.id)
        require(store.selectedId("gui") == first.slotId && store.metadata("gui").id == edited.profile.id)
        require(store.keyFor(first.profile).isEmpty() && store.keyFor(edited.profile) == FIRST_SECRET)
        val changed = store.saveConfiguration(first.slotId, "SYNTHETIC moved", edited.profile.copy(baseUrl = "https://second.example/v1"), SECOND_SECRET)
        require(store.keyFor(edited.profile).isEmpty() && store.keyFor(changed.profile) == SECOND_SECRET)
        require(store.list().single().profile.apiKey.isEmpty())
        require(ciphertextOnly(File(folder, "profiles"), FIRST_SECRET, SECOND_SECRET))
    }

    private fun atomicRecoveryAndCorruptCatalog() = isolated { ctx, folder ->
        val store = ProfileStore(ctx)
        val saved = store.saveConfiguration(null, "SYNTHETIC A", profile(), FIRST_SECRET)
        store.select("gui", saved.slotId)
        val base = File(folder, "profiles/catalog-v2.enc")
        // Crash during an AtomicFile replacement: .new is incomplete, committed base remains.
        AtomicFile(base).startWrite().use { it.write(ByteArray(17) { 42 }) }
        require(ProfileStore(ctx).metadata("gui").id == saved.profile.id)
        require(store.keyFor(saved.profile) == FIRST_SECRET && !File(base.path + ".new").exists())
        val committed = base.readBytes()
        try {
            // An existing corrupt catalog must fail closed, never become a new empty library.
            base.writeBytes(ByteArray(40) { 42 })
            rejects { ProfileStore(ctx).list() }
            require(base.readBytes().contentEquals(ByteArray(40) { 42 }))
        } finally { base.writeBytes(committed); committed.fill(0) }
        require(ProfileStore(ctx).metadata("gui").id == saved.profile.id)
    }

    private fun failedMigrationReadKeepsLegacy() = isolated { ctx, folder ->
        val firstId = UUID.randomUUID().toString()
        legacy(folder, "gui", firstId, FIRST_SECRET)
        val first = File(folder, "profiles/gui.enc")
        val before = first.readBytes()
        val broken = File(folder, "profiles/planner.enc").apply { writeBytes(ByteArray(40) { 42 }) }
        rejects { ProfileStore(ctx).list() }
        require(first.exists() && first.readBytes().contentEquals(before) && broken.exists())
        require(!File(folder, "profiles/catalog-v2.enc").exists())
        before.fill(0)
    }

    private fun failedMigrationWriteKeepsLegacy() = isolated { ctx, folder ->
        val firstId = UUID.randomUUID().toString()
        val secondId = UUID.randomUUID().toString()
        legacy(folder, "gui", firstId, FIRST_SECRET)
        legacy(folder, "planner", secondId, SECOND_SECRET)
        val profiles = File(folder, "profiles")
        // An occupied .new directory makes the replacement fail before a commit can occur.
        val blocker = File(profiles, "catalog-v2.enc.new").apply { check(mkdir()) }
        val child = File(blocker, "blocker").apply { writeBytes(byteArrayOf(42)) }
        rejects { ProfileStore(ctx).list() }
        require(File(profiles, "gui.enc").exists() && File(profiles, "planner.enc").exists())
        require(!File(profiles, "catalog-v2.enc").exists())
        require(child.delete() && blocker.delete())
        val recovered = ProfileStore(ctx)
        require(recovered.metadata("gui").id == firstId && recovered.metadata("planner").id == secondId)
        require(recovered.keyFor(recovered.metadata("gui")) == FIRST_SECRET)
        require(recovered.keyFor(recovered.metadata("planner")) == SECOND_SECRET)
        require(!File(profiles, "gui.enc").exists() && !File(profiles, "planner.enc").exists())
    }

    private fun legacy(folder: File, role: String, id: String, secret: String) {
        val profiles = File(folder, "profiles").apply { check(mkdirs() || isDirectory) }
        val json = JSONObject().put("base_url", "https://first.example/v1").put("model", "synthetic-model")
            .put("id", id).put("key", secret)
        val plain = json.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = try { cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        try { File(profiles, "$role.enc").writeBytes(encrypted) } finally { encrypted.fill(0) }
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun ciphertextOnly(folder: File, vararg secrets: String): Boolean = folder.listFiles().orEmpty()
        .filter { it.isFile }.all { file ->
            val bytes = file.readBytes()
            try { secrets.none { bytes.toString(Charsets.ISO_8859_1).contains(it) } }
            finally { bytes.fill(0) }
        }

    private fun rejects(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        require(rejected)
    }

    private fun profile() = ModelProfile(baseUrl = "https://first.example/v1", model = "synthetic-model")

    private fun isolated(block: (Context, File) -> Unit) {
        val base = context.noBackupFilesDir.canonicalFile
        val folder = File(base, "profile-library-${UUID.randomUUID()}").apply { check(mkdir()) }
        val wrapped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }
        try { block(wrapped, folder) } finally {
            check(folder.canonicalFile.parentFile == base && folder.name.startsWith("profile-library-"))
            check(folder.deleteRecursively())
        }
    }

    private companion object {
        const val KEY_ALIAS = "mobileharness.profile.encryption.v1"
        const val FIRST_SECRET = "synthetic-profile-secret-A-not-used-remotely"
        const val SECOND_SECRET = "synthetic-profile-secret-B-not-used-remotely"
    }
}
