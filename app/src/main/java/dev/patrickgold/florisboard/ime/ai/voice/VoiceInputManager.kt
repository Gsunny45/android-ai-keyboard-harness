package dev.patrickgold.florisboard.ime.ai.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.patrickgold.florisboard.ime.ai.bridges.AppProfileManager
import dev.patrickgold.florisboard.ime.ai.trigger.TriggerParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Manages voice input via the external whisper-to-input app.
 *
 * ## Flow
 *   1. User triggers voice → [startRecording] sends an intent to
 *      `org.woheller69.whisper`.
 *   2. The whisper app records audio, runs Whisper.cpp, and writes
 *      the transcript to a file, returning the content URI via
 *      [Intent.ACTION_GET_CONTENT].
 *   3. [handleGetContentResult] reads the transcript from the URI.
 *   4. [SpokenTriggerNormalizer] normalises spoken trigger tokens.
 *   5. Normalized result is fed into [TriggerParser] for dispatch.
 *
 * ## Integration points
 *   - `org.woheller69.whisper` — the whisper-to-input companion app.
 *   - [SpokenTriggerNormalizer] — 20-entry spoken-form mapping table.
 *   - [TriggerParser] — parses /trigger and <<pipeline>> commands.
 *   - [AppProfileManager] — provides the foreground app package name
 *     for auto-routing behaviour (e.g. auto-skill in Obsidian).
 */
class VoiceInputManager(
    private val context: Context,
    private val normalizer: SpokenTriggerNormalizer,
    private val triggerParser: TriggerParser,
    private val appProfileManager: AppProfileManager,
) {

    // ── State ────────────────────────────────────────────────────────────

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _currentTranscript = MutableStateFlow<String?>(null)
    val currentTranscript: StateFlow<String?> = _currentTranscript

    // ── Recording lifecycle ──────────────────────────────────────────────

    /**
     * Start voice recording.
     *
     * Launches `org.woheller69.whisper` via an explicit intent.
     * The whisper app will record audio, transcribe via Whisper.cpp,
     * and — when done — return the result through [handleGetContentResult].
     */
    fun startRecording() {
        if (_isRecording.value) return

        val intent = context.packageManager?.getLaunchIntentForPackage(WHISPER_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }

        _isRecording.value = true
        _currentTranscript.value = null
    }

    /**
     * Stop recording.
     *
     * Sends a broadcast stop action if the whisper app supports it;
     * otherwise the app manages its own lifecycle.
     */
    fun stopRecording() {
        val stopIntent = Intent(STOP_RECORDING_ACTION).apply {
            `package` = WHISPER_PACKAGE
        }
        try {
            context.sendBroadcast(stopIntent)
        } catch (_: Exception) {
            // Broadcast not supported — the whisper app manages its own lifecycle
        }
        _isRecording.value = false
    }

    // ── Result handling ──────────────────────────────────────────────────

    /**
     * Handle the [Intent.ACTION_GET_CONTENT] result from the whisper app.
     *
     * The whisper app writes the transcript to a temporary file and
     * returns its content URI. This method reads the URI, normalizes
     * the transcript via [SpokenTriggerNormalizer], and feeds the
     * result into [TriggerParser].
     *
     * @param data  The intent returned by the whisper app, containing
     *              a content URI in [Intent.getData].
     * @return The processed output string (trigger + args, or plain text),
     *         or null if the result was empty / had no data URI.
     */
    suspend fun handleGetContentResult(data: Intent?): String? {
        if (data == null) return null

        val uri = data.data
        if (uri == null) return null

        val transcript = readTranscriptFromUri(uri) ?: return null
        _currentTranscript.value = transcript
        _isRecording.value = false

        return processTranscript(transcript)
    }

    /**
     * Alternative entry point: when the transcript is obtained through
     * other means (e.g. clipboard, notification), call this with the
     * raw text.
     */
    suspend fun processText(transcript: String): String? {
        if (transcript.isBlank()) return null
        _currentTranscript.value = transcript
        _isRecording.value = false
        return processTranscript(transcript)
    }

    /**
     * Read and process the latest transcript (compatibility alias).
     *
     * @deprecated Use [handleGetContentResult] with the intent data.
     * @return The processed output, or null.
     */
    @Deprecated("Use handleGetContentResult(Intent)")
    suspend fun readAndProcessTranscript(): String? {
        return readTranscriptFromContentProvider()
    }

    /** Force the recording state to stop (e.g. on IME window hidden). */
    fun reset() {
        _isRecording.value = false
        _currentTranscript.value = null
    }

    // ── Internal pipeline ────────────────────────────────────────────────

    private fun processTranscript(transcript: String): String {
        // Step 1: Normalize spoken trigger tokens
        val normalized = normalizer.normalize(transcript)
        val inputText = if (normalized.hasTrigger) {
            "${normalized.triggerId} ${normalized.args}"
        } else {
            transcript
        }

        // Step 2: Route through TriggerParser
        val parsed = triggerParser.parse(inputText)

        // Step 3: Auto-skill in Obsidian (if enabled and no trigger spoken)
        val foregroundApp = appProfileManager.currentPackageId
        val isObsidian = foregroundApp == OBSIDIAN_PACKAGE

        return if (isObsidian && !normalized.hasTrigger) {
            // Auto-route plain text to vault_aware_draft skill
            "/skill vault_aware_draft $transcript"
        } else if (parsed.hasTrigger) {
            // Reconstruct trigger + space-separated args
            val args = parsed.args.joinToString(" ")
            "${parsed.triggerId}${if (args.isNotBlank()) " $args" else ""}"
        } else {
            inputText
        }
    }

    // ── Content reading from URI ─────────────────────────────────────────

    /**
     * Reads transcript text from a content:// URI.
     * Used after [Intent.ACTION_GET_CONTENT] returns the file URI.
     */
    private suspend fun readTranscriptFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText().trim()
            }?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Legacy: reads transcript from the content provider pattern.
     * This is kept for backward compatibility with older whisper apps
     * that expose a content:// provider directly.
     */
    private suspend fun readTranscriptFromContentProvider(): String? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse("content://$CONTENT_PROVIDER_AUTHORITY/transcript")
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText().trim()
            }?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Package name of the whisper-to-input companion app. */
        const val WHISPER_PACKAGE = "org.woheller69.whisper"

        /** Package name of Obsidian. */
        private const val OBSIDIAN_PACKAGE = "md.obsidian"

        /** ContentProvider authority for legacy transcript access. */
        private const val CONTENT_PROVIDER_AUTHORITY = "org.woheller69.whisper"

        /** Intent action to stop recording (broadcast). */
        private const val STOP_RECORDING_ACTION = "org.woheller69.whisper.STOP_RECORDING"
    }
}
