package dev.patrickgold.florisboard.ime.ai.bridges

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dev.patrickgold.florisboard.ime.ai.orchestration.AppContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Bridges the IME to an Obsidian vault via Storage Access Framework (SAF).
 *
 * Responsibilities:
 *   1. Parse an Obsidian window title to extract vault name and file path.
 *   2. Read the frontmatter of the currently open note via SAF.
 *
 * The user must grant directory access to the vault root through SAF.
 * The granted URI is persisted in SharedPreferences.
 *
 * ## Obsidian window title format
 * {{{
 *   VaultName - path/to/note.md - Obsidian
 * }}}
 */
class ObsidianBridge(
    private val context: Context,
) {
    companion object {
        private const val TAG = "ObsidianBridge"
        private const val PREFS_NAME = "obsidian_bridge"
        private const val KEY_VAULT_URI = "vault_tree_uri"

        /** Obsidian window title regex: "VaultName - path/to/note.md - Obsidian" */
        private val OBSIDIAN_TITLE = Regex("""^(.+?)\s*-\s*(.+?)\s*-\s*Obsidian$""")
    }

    // ── Vault URI management ─────────────────────────────────────────────

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Persist a SAF tree URI for the vault root directory.
     * Call this after the user picks a directory via
     * `Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)` and takes a
     * persistable URI permission.
     */
    fun setVaultTreeUri(uri: Uri) {
        prefs.edit().putString(KEY_VAULT_URI, uri.toString()).apply()
        // Take persistable permission so the URI survives reboots
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not take persistable URI permission", e)
        }
    }

    /** Returns the persisted vault tree URI, or null. */
    fun getVaultTreeUri(): Uri? {
        val s = prefs.getString(KEY_VAULT_URI, null) ?: return null
        return Uri.parse(s)
    }

    /** True if a vault directory has been configured via SAF. */
    fun hasVaultAccess(): Boolean = getVaultTreeUri() != null

    // ── Title parsing ────────────────────────────────────────────────────

    /**
     * Parse an Obsidian window title into [AppContext].
     *
     * Returns null if the title does not match the Obsidian pattern.
     */
    fun parseTitle(title: String?): ObsidianParseResult? {
        if (title.isNullOrBlank()) return null
        val match = OBSIDIAN_TITLE.find(title.trim()) ?: return null
        return ObsidianParseResult(
            vaultName = match.groupValues[1].trim(),
            filePath = match.groupValues[2].trim(),
        )
    }

    /**
     * Parse just the file path from an Obsidian window title.
     */
    fun extractFilePath(title: String?): String? = parseTitle(title)?.filePath

    /**
     * Parse just the vault name from an Obsidian window title.
     */
    fun extractVaultName(title: String?): String? = parseTitle(title)?.vaultName

    // ── Frontmatter reading via SAF ──────────────────────────────────────

    /**
     * Read the frontmatter of an Obsidian note via SAF.
     *
     * @param filePath  Relative path within the vault (e.g. "daily/2026-04-25.md").
     * @return The raw YAML frontmatter string (including the `---` delimiters),
     *         or null if the file cannot be read / vault is not configured.
     */
    fun readFrontmatter(filePath: String?): FrontmatterResult? {
        if (filePath.isNullOrBlank()) return null
        val vaultUri = getVaultTreeUri() ?: return null

        return try {
            val vaultRoot = DocumentFile.fromTreeUri(context, vaultUri) ?: return null
            val file = navigateToFile(vaultRoot, filePath) ?: return null

            val inputStream = context.contentResolver.openInputStream(file.uri) ?: return null
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()
            inputStream.close()

            extractFrontmatter(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read frontmatter for $filePath", e)
            null
        }
    }

    /**
     * High-level: given an [AppContext] (from [AccessibilityBridge]),
     * read the relevant note's frontmatter if this is an Obsidian context.
     */
    fun readFrontmatterFromContext(appContext: AppContext?): FrontmatterResult? {
        if (appContext == null) return null
        val filePath = appContext.filePath
        if (filePath.isNullOrBlank()) return null
        // Verify vault access
        if (!hasVaultAccess()) return null
        return readFrontmatter(filePath)
    }

    /**
     * Clear the persisted vault URI (e.g. on user revoke).
     */
    fun clearVaultAccess() {
        prefs.edit().remove(KEY_VAULT_URI).apply()
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Navigate a [DocumentFile] tree to a relative file path.
     * Splits on "/" and descends into child directories.
     */
    private fun navigateToFile(root: DocumentFile, filePath: String): DocumentFile? {
        val parts = filePath.split("/")
        var current: DocumentFile = root
        for (i in parts.indices) {
            val part = parts[i]
            val isLast = i == parts.lastIndex
            current = if (isLast) {
                current.findFile(part) ?: return null
            } else {
                current.findFile(part) ?: return null
            }
        }
        return current
    }

    /**
     * Extract YAML frontmatter from note content.
     * Returns everything between the first and second `---` delimiters,
     * including the delimiters themselves.
     */
    private fun extractFrontmatter(content: String): FrontmatterResult? {
        val lines = content.lines()
        if (lines.size < 2) return null
        if (lines[0].trim() != "---") return null

        val endIndex = lines.indexOfFirst { it.trim() == "---" && it !== lines[0] }
        if (endIndex < 1) return null

        val frontmatterLines = lines.subList(0, endIndex + 1)
        val frontmatterText = frontmatterLines.joinToString("\n")
        val bodyLines = lines.drop(endIndex + 1).joinToString("\n").trim()

        // Parse into key-value pairs
        val properties = mutableMapOf<String, String>()
        for (i in 1 until endIndex) {
            val line = lines[i].trim()
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim().removeSurrounding("\"")
                if (key.isNotBlank()) {
                    properties[key] = value
                }
            }
        }

        return FrontmatterResult(
            raw = frontmatterText,
            properties = properties,
            bodyStart = bodyLines,
        )
    }
}

/**
 * The result of parsing an Obsidian window title.
 */
data class ObsidianParseResult(
    val vaultName: String,
    val filePath: String,
)

/**
 * The result of reading a note's frontmatter.
 *
 * @property raw         The raw frontmatter text (YAML + delimiters).
 * @property properties  Parsed key-value pairs from the frontmatter.
 * @property bodyStart   The note body content after the frontmatter.
 */
data class FrontmatterResult(
    val raw: String,
    val properties: Map<String, String>,
    val bodyStart: String,
)
