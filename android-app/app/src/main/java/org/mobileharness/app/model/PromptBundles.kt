package org.mobileharness.app.model

/** Explicit inheritance: missing automatic-start overrides never fall back silently. */
internal object PromptBundles {
    val automaticOverrides = setOf(
        "gui/system.txt", "gui/original.task.txt", "gui/recovery.task.txt",
        "gui/verify.task.txt", "gui/confirm.task.txt", "request-profiles.json",
    )

    fun assetPath(version: String, name: String): String {
        require(version in setOf("1.1.0", "1.2.0"))
        require(name.isNotBlank() && !name.startsWith('/') && name.split('/').none { it == ".." || it == "." })
        val source = if (version == "1.2.0" && (name in automaticOverrides || name == "manifest.json")) "1.2.0" else "1.1.0"
        return "prompts/v$source/$name"
    }
}
