package org.mobileharness.app.ui

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mobileharness.app.HarnessApplication
import org.mobileharness.app.core.RunState
import java.util.Locale

/** UI locale only. No task, model, authorization, credential or audit data is changed. */
object AppLanguage {
    enum class Choice(val tag: String) { SYSTEM(""), CHINESE("zh-Hans"), ENGLISH("en") }
    private const val PREFERENCES = "ui_language"
    private const val KEY = "language"
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    fun choice(base: Context): Choice {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = base.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) Choice.SYSTEM else fromLanguage(locales[0].language)
        }
        return Choice.entries.firstOrNull { it.tag == base.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, "") }
            ?: Choice.SYSTEM
    }

    /** Caller also guards startup/export/profile edits. A live run can never change locale here. */
    fun changeChoice(base: Context, selected: Choice): Boolean {
        val app = base.applicationContext as? HarnessApplication ?: return false
        if (app.runtime.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED)) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                base.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(selected.tag)
            } else {
                // Only a public language enum is stored; never session text or secrets.
                base.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, selected.tag).apply()
            }
            configurationChanged()
            true
        }.getOrDefault(false)
    }

    fun configurationChanged() { changes.value += 1 }

    fun context(base: Context): Context {
        findPreview(base)?.let { preview ->
            val desired = LocaleList.forLanguageTags(preview.tag)
            return if (base.resources.configuration.locales == desired) base else PreviewContext(base, preview.tag)
        }
        val selected = choice(base)
        val locale = when (selected) {
            Choice.CHINESE -> Locale.forLanguageTag("zh-Hans")
            Choice.ENGLISH -> Locale.ENGLISH
            Choice.SYSTEM -> {
                val system = if (Build.VERSION.SDK_INT >= 33) base.getSystemService(LocaleManager::class.java).systemLocales
                    else Resources.getSystem().configuration.locales
                (0 until system.size()).map { system[it] }.firstOrNull { it.language == "en" || it.language == "zh" }
                    ?: Locale.ENGLISH
            }
        }
        val locales = LocaleList(locale)
        if (base.resources.configuration.locales == locales) return base
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(locales)
            setLayoutDirection(locale)
        }
        // Keep the original Activity in the ContextWrapper chain. Compose launchers
        // discover their ActivityResultRegistryOwner through that chain.
        return LocalizedContext(base, configuration)
    }

    /** Isolated resource context for synthetic previews/verification; never writes a preference. */
    fun previewContext(base: Context, tag: String): Context {
        require(Locale.forLanguageTag(tag).language in setOf("en", "zh"))
        return PreviewContext(base, tag)
    }

    private class PreviewContext(base: Context, val tag: String) : ContextWrapper(base) {
        private val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            val locale = Locale.forLanguageTag(tag)
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
        })
        override fun getResources() = localized.resources
        override fun getAssets() = localized.assets
    }

    private class LocalizedContext(base: Context, configuration: Configuration) : ContextWrapper(base) {
        private val localized = base.createConfigurationContext(configuration)
        override fun getResources() = localized.resources
        override fun getAssets() = localized.assets
    }

    private fun findPreview(base: Context): PreviewContext? {
        var current = base
        repeat(16) {
            if (current is PreviewContext) return current as PreviewContext
            val wrapper = current as? ContextWrapper ?: return null
            val next = wrapper.baseContext
            if (next === current) return null
            current = next
        }
        return null
    }

    private fun fromLanguage(language: String) = when (language) {
        "en" -> Choice.ENGLISH
        "zh" -> Choice.CHINESE
        else -> Choice.SYSTEM
    }
}
