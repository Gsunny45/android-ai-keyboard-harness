package dev.patrickgold.florisboard.ime.ai.orchestration

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Resolves app context (vault name, file path) from the foreground window.
 *
 * Supports:
 *   - Obsidian window titles: "MyVault - daily/2026-04-25.md - Obsidian"
 *   - Generic title passthrough for other apps
 *
 * Used by the trigger resolution pipeline to populate context variables
 * like {{vault.name}} and {{file.path}} in template strings.
 */
data class AppContext(
    val vaultName: String? = null,
    val filePath: String? = null,
    val windowTitle: String? = null,
)

class ContextResolver {

    companion object {
        // Obsidian window title format: "VaultName - path/to/note.md - Obsidian"
        private val OBSIDIAN_TITLE = Regex("""^(.+?)\s*-\s*(.+?)\s*-\s*Obsidian$""")
    }

    /**
     * Resolve app context from an AccessibilityNodeInfo obtained via
     * the IME's AccessibilityService.
     */
    fun resolve(nodeInfo: AccessibilityNodeInfo): AppContext {
        val title = extractWindowTitle(nodeInfo)
        return resolveFromTitle(title)
    }

    /**
     * Parse vault context from a raw window title string.
     * Useful for testing or when the title is obtained through other channels.
     */
    fun resolveFromTitle(title: String?): AppContext {
        if (title.isNullOrBlank()) return AppContext()
        val match = OBSIDIAN_TITLE.find(title.trim())
        if (match == null) return AppContext(windowTitle = title)
        return AppContext(
            vaultName = match.groupValues[1].trim(),
            filePath = match.groupValues[2].trim(),
            windowTitle = title,
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun extractWindowTitle(nodeInfo: AccessibilityNodeInfo): String? {
        // Prefer the title via AccessibilityWindowInfo (preferred API path)
        // On API 33+ we check root node window title; fallback to text search
        val title = try {
            nodeInfo.text?.toString()
        } catch (_: Exception) { null }
        if (!title.isNullOrBlank() && title.contains("Obsidian", ignoreCase = true)) return title

        // Fallback: depth-first search for a node containing "Obsidian" in its text
        return findObsidianTitleText(nodeInfo)
    }

    /**
     * Walk the accessibility tree looking for a node whose text contains
     * "Obsidian" — this is the window title rendered as a static label.
     */
    private fun findObsidianTitleText(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()
        if (text != null && text.contains("Obsidian", ignoreCase = true)) return text
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findObsidianTitleText(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }
}
