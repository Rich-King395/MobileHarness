package org.mobileharness.app.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Explicitly saved App scope only. No task, picture, interaction history, or API credential. */
class AuthorizedAppsStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "authorized-apps").apply { check(mkdirs() || isDirectory) }
    private val path = File(directory, "scope-v1.enc")
    private val atomic = AtomicFile(path)

    fun load(): Set<String> = synchronized(LOCK) { loadUnlocked() }

    private fun loadUnlocked(): Set<String> {
        val bytes = try { atomic.openRead().use { it.readBytes() } }
        catch (missing: FileNotFoundException) {
            if (!path.exists() && !File(directory, "scope-v1.enc.bak").exists()) return emptySet()
            throw IllegalStateException("AUTHORIZED_APPS_UNAVAILABLE")
        }
        try {
            require(bytes.size in 30..1_000_000)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
            return try {
                val json = JSONObject(String(plain, Charsets.UTF_8))
                require(json.getInt("version") == 1 && json.length() == 2)
                val packages = json.getJSONArray("packages")
                require(packages.length() <= MAX_PACKAGES)
                val result = linkedSetOf<String>()
                for (index in 0 until packages.length()) {
                    val pkg = packages.getString(index)
                    require(validPackage(pkg) && result.add(pkg))
                }
                result.toSet()
            } finally { plain.fill(0) }
        } catch (_: Exception) {
            // Corruption does not become a silently accepted empty scope or overwrite the file.
            throw IllegalStateException("AUTHORIZED_APPS_UNAVAILABLE")
        } finally { bytes.fill(0) }
    }

    fun save(packages: Set<String>) = synchronized(LOCK) { saveUnlocked(packages) }

    private fun saveUnlocked(packages: Set<String>) {
        require(packages.size <= MAX_PACKAGES && packages.all(::validPackage))
        val json = JSONObject().put("version", 1).put("packages", JSONArray(packages.sorted()))
        val plain = json.toString().toByteArray(Charsets.UTF_8)
        val encrypted = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.iv + cipher.doFinal(plain)
        } finally { plain.fill(0) }
        val stream = atomic.startWrite()
        try {
            stream.write(encrypted)
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        } finally { encrypted.fill(0) }
        check(loadUnlocked() == packages) { "AUTHORIZED_APPS_SAVE_FAILED" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun validPackage(value: String): Boolean = value.length <= 255 && PACKAGE.matches(value)

    private companion object {
        const val KEY_ALIAS = "mobileharness.authorized.apps.encryption.v1"
        const val MAX_PACKAGES = 4096
        val PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
        val LOCK = Any()
    }
}
