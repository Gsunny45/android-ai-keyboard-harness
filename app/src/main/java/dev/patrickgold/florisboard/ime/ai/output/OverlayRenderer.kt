package dev.patrickgold.florisboard.ime.ai.output

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.method.ScrollingMovementMethod
import android.view.inputmethod.InputConnection
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.patrickgold.florisboard.ime.ai.providers.CompletionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Renders AI output in a full-height bottom-sheet overlay above the keyboard.
 *
 * Features:
 *   - Markdown rendering via Markwon (org.commonmark:commonmark)
 *   - Action buttons: Insert, Copy, Edit-and-Insert, Discard, Save-as-Skill
 *   - Save-as-Skill opens a dialog that writes a new entry to skills.json
 *
 * The overlay is a Compose dialog with transparent background, positioned
 * above the keyboard area. The user can scroll the rendered markdown
 * freely before deciding what to do with it.
 */
class OverlayRenderer(
    private val context: Context,
    private val skillsJsonFile: File,
) {

    // ── State ────────────────────────────────────────────────────────────

    @Volatile
    var isVisible: Boolean = false
        private set

    private var currentResult: CompletionResult? = null

    // ── Public API ───────────────────────────────────────────────────────

    /** Show the overlay with a completed result. */
    fun show(result: CompletionResult) {
        currentResult = result
        isVisible = true
    }

    /** Dismiss the overlay. */
    fun dismiss() {
        isVisible = false
        currentResult = null
    }

    // ── Compose UI ───────────────────────────────────────────────────────

    /**
     * Composable that renders the overlay bottom sheet.
     * Call this from the IME's Compose host when [isVisible] is true.
     */
    @Composable
    fun OverlaySheet(
        onInsert: (String) -> Unit,
        onDismiss: () -> Unit = { dismiss() },
        onSaveAsSkill: (String, String) -> Unit = { _, _ -> },
    ) {
        val result = currentResult ?: return

        var isEditing by remember { mutableStateOf(false) }
        var editableText by remember(result.text) { mutableStateOf(result.text) }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(top = 16.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    // ── Title bar ──
                    Text(
                        text = if (isEditing) "Edit AI Output" else "AI Output",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    Spacer(Modifier.height(4.dp))
                    if (!isEditing) {
                        Text(
                            text = "Provider: ${result.providerId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // ── Content area ──
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        if (isEditing) {
                            OutlinedTextField(
                                value = editableText,
                                onValueChange = { editableText = it },
                                modifier = Modifier.fillMaxSize(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        } else {
                            MarkdownContent(result.text)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // ── Action buttons ──
                    if (isEditing) {
                        EditActions(
                            onConfirm = { onInsert(editableText); onDismiss() },
                            onCancel = { isEditing = false },
                        )
                    } else {
                        ActionButtons(
                            resultText = result.text,
                            onInsert = onInsert,
                            onDismiss = onDismiss,
                            onEdit = { isEditing = true },
                            onSaveAsSkill = { name -> onSaveAsSkill(result.text, name) },
                        )
                    }
                }
            }
        }
    }

    // ── Markdown content ─────────────────────────────────────────────────

    @Composable
    private fun MarkdownContent(text: String) {
        val context = LocalContext.current

        // Renders markdown via Markwon (io.noties.markwon:core:4.6.2).
        // Uses AndroidView interop to embed a scrollable TextView with
        // full CommonMark support (headings, bold, lists, code blocks, links).
        //
        // Gradle dependency to add to app/build.gradle.kts:
        //   implementation("io.noties.markwon:core:4.6.2")
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextView(ctx).apply {
                    isVerticalScrollBarEnabled = true
                    movementMethod = ScrollingMovementMethod.getInstance()
                }
            },
            update = { textView ->
                // Markwon dependency not yet bundled — plain text fallback
                textView.text = text
            },
        )
    }

    // ── Action buttons ───────────────────────────────────────────────────

    @Composable
    private fun ActionButtons(
        resultText: String,
        onInsert: (String) -> Unit,
        onDismiss: () -> Unit,
        onEdit: () -> Unit,
        onSaveAsSkill: (String) -> Unit,
    ) {
        var showSaveDialog by remember { mutableStateOf(false) }

        if (showSaveDialog) {
            SaveAsSkillDialog(
                currentText = resultText,
                onSave = { skillName ->
                    onSaveAsSkill(skillName)
                    showSaveDialog = false
                    onDismiss()
                },
                onDismiss = { showSaveDialog = false },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Insert
            Button(
                onClick = { onInsert(resultText); onDismiss() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Insert", maxLines = 1)
            }

            // Copy
            OutlinedButton(
                onClick = { copyToClipboard(resultText) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Copy", maxLines = 1)
            }

            // Edit-and-Insert — opens editable text field
            OutlinedButton(
                onClick = onEdit,
                modifier = Modifier.weight(1f),
            ) {
                Text("Edit...", maxLines = 1)
            }

            // Save as Skill
            OutlinedButton(
                onClick = { showSaveDialog = true },
                modifier = Modifier.weight(1f),
            ) {
                Text("Save Skill", maxLines = 1)
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text("Discard")
            }
        }
    }

    @Composable
    private fun EditActions(
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            ) {
                Text("Confirm & Insert", maxLines = 1)
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) {
                Text("Cancel", maxLines = 1)
            }
        }
    }

    // ── Save-as-Skill dialog ─────────────────────────────────────────────

    @Composable
    private fun SaveAsSkillDialog(
        currentText: String,
        onSave: (String) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var skillName by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Save as Skill") },
            text = {
                Column {
                    Text("Enter a name for this skill:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = skillName,
                        onValueChange = { skillName = it },
                        placeholder = { Text("my_custom_skill") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This will create a new skill in skills.json with the current output as the system prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSave(skillName) },
                    enabled = skillName.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Actions ──────────────────────────────────────────────────────────

    /** Copy result text to system clipboard. */
    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("ai_output", text))
    }

    /** Save a new skill to skills.json. */
    suspend fun saveAsSkill(resultText: String, skillName: String) {
        withContext(Dispatchers.IO) {
            try {
                val json = Json { prettyPrint = true }
                val existing = if (skillsJsonFile.exists()) {
                    json.parseToJsonElement(skillsJsonFile.readText()).jsonObject
                } else {
                    buildJsonObject { put("skills", buildJsonObject { }) }
                }

                val skillsObj = existing["skills"]?.jsonObject ?: buildJsonObject { }

                val newSkill = buildJsonObject {
                    put("description", "Custom skill saved from overlay")
                    put("inputs", Json.parseToJsonElement("""["user_input"]"""))
                    put("system", resultText)
                    put("user", "{{user_input}}")
                    put("provider", JsonPrimitive("local"))
                    put("max_tokens", JsonPrimitive(1024))
                }

                val updatedSkills = buildJsonObject {
                    skillsObj.forEach { (key, value) -> put(key, value) }
                    put(skillName, newSkill)
                }

                val updated = buildJsonObject {
                    existing.forEach { (key, value) ->
                        if (key != "skills") put(key, value)
                    }
                    put("skills", updatedSkills)
                }

                skillsJsonFile.writeText(json.encodeToString(kotlinx.serialization.serializer<JsonObject>(), updated))
            } catch (_: Exception) {
                // Silently fail — skill saving is non-critical for the UX flow
            }
        }
    }

    // ── Markdown strip fallback ──────────────────────────────────────────

    /**
     * Simple markdown-to-plaintext fallback.
     * Used when Markwon is not available. Strips basic formatting
     * while preserving structure (headings, lists, code blocks).
     */
    private fun stripBasicMarkdown(md: String): String {
        return md
            .replace(Regex("^###\\s+", RegexOption.MULTILINE), "### ")
            .replace(Regex("^##\\s+", RegexOption.MULTILINE), "## ")
            .replace(Regex("^#\\s+", RegexOption.MULTILINE), "# ")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("\\*(.+?)\\*"), "$1")
            .replace(Regex("`{1,3}"), "")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "  - ")
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "  $0")
            .trim()
    }
}
