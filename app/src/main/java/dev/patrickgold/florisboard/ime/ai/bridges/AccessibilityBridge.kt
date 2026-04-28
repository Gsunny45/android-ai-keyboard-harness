@file:JvmName("AccessibilityBridge")
package dev.patrickgold.florisboard.ime.ai.bridges

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import dev.patrickgold.florisboard.ime.ai.orchestration.AppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A snapshot of the currently focused text field and its host app.
 *
 * @property packageName     Package name of the foreground app (from [EditorInfo]).
 * @property windowTitle     Window title obtained via accessibility, or null.
 * @property selectedText    Text currently selected in the field, or null.
 * @property textBeforeCursor  Text before the cursor position (up to 1024 chars).
 * @property textAfterCursor   Text after the cursor position (up to 1024 chars).
 * @property editorInfo      The raw [EditorInfo] from the IME callback.
 * @property appContext      Parsed [AppContext] (Obsidian vault info, if applicable).
 */
data class BridgeSnapshot(
    val packageName: String?,
    val windowTitle: String?,
    val selectedText: String?,
    val textBeforeCursor: String?,
    val textAfterCursor: String?,
    val editorInfo: EditorInfo?,
    val appContext: AppContext = AppContext(),
)

/**
 * Bridges the IME to the foreground app's text field and window state.
 *
 * Reads:
 *   - Foreground app package name
 *   - Window title (via accessibility, if available)
 *   - Selected text in the current text field
 *   - Text before / after the cursor
 *
 * Caching:
 *   - Internal snapshot is cached for 3 seconds.
 *   - Call [invalidate] on TYPE_WINDOW_STATE_CHANGED to force a refresh.
 *
 * This class **does not** declare an AccessibilityService. It reads
 * window title information through the [AccessibilityManager] if the
 * host process already has accessibility capabilities. For the IME, the
 * primary data source is [InputConnection] / [EditorInfo].
 */
class AccessibilityBridge(
    private val context: Context,
) {
    // ── Cache state ──────────────────────────────────────────────────────

    @Volatile
    private var cachedSnapshot: BridgeSnapshot? = null

    @Volatile
    private var cacheExpiryMs: Long = 0L

    /** Cache time-to-live in milliseconds (3 seconds). */
    private val cacheTtlMs: Long = 3_000L

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Returns a cached or fresh snapshot of the current text-field state.
     *
     * @param editorInfo        The [EditorInfo] from the IME's
     *                          [android.inputmethodservice.InputMethodService.onStartInputView].
     * @param inputConnection   The active [InputConnection] for the current field.
     * @return A [BridgeSnapshot] with whatever data is available.
     */
    fun snapshot(
        editorInfo: EditorInfo?,
        inputConnection: InputConnection?,
    ): BridgeSnapshot {
        val now = currentTimeMs()
        val cached = cachedSnapshot
        if (cached != null && now < cacheExpiryMs) {
            return cached
        }

        val fresh = buildSnapshot(editorInfo, inputConnection)
        cachedSnapshot = fresh
        cacheExpiryMs = now + cacheTtlMs
        return fresh
    }

    /**
     * Forces the cache to expire on the next [snapshot] call.
     * Call this when a TYPE_WINDOW_STATE_CHANGED accessibility event
     * is received, or when the IME detects the input target changed.
     */
    fun invalidate() {
        cachedSnapshot = null
        cacheExpiryMs = 0L
    }

    /**
     * Convenience: returns just the window title, or null.
     */
    fun resolveWindowTitle(editorInfo: EditorInfo?): String? {
        return snapshot(editorInfo, null).windowTitle
    }

    // ── Snapshot builder ─────────────────────────────────────────────────

    private fun buildSnapshot(
        editorInfo: EditorInfo?,
        inputConnection: InputConnection?,
    ): BridgeSnapshot {
        val packageName = editorInfo?.packageName
        val windowTitle = resolveWindowTitleFromAccessibility()
        val selectedText = inputConnection?.getSelectedText(0)?.toString()
        val textBeforeCursor = inputConnection?.getTextBeforeCursor(MAX_TEXT_LENGTH, 0)?.toString()
        val textAfterCursor = inputConnection?.getTextAfterCursor(MAX_TEXT_LENGTH, 0)?.toString()
        val appContext = AppContext(windowTitle = windowTitle)

        return BridgeSnapshot(
            packageName = packageName,
            windowTitle = windowTitle,
            selectedText = selectedText?.ifBlank { null },
            textBeforeCursor = textBeforeCursor?.ifBlank { null },
            textAfterCursor = textAfterCursor?.ifBlank { null },
            editorInfo = editorInfo,
            appContext = appContext,
        )
    }

    /**
     * Attempts to resolve the foreground window title.
     *
     * Uses [AccessibilityManager] to find active windows and reads the
     * first one's title. This works if the IME host process has the
     * accessibility service permission, or on Android 10+ if accessibility
     * is enabled for the keyboard app.
     */
    /** Stub — resolves window title if an AccessibilityService is available. */
    private fun resolveWindowTitleFromAccessibility(): String? {
        // Window title resolution requires an AccessibilityService declared in
        // AndroidManifest.xml. Since the IME does not declare one, this always
        // returns null. Future iterations can add an accessibility service and
        // query AccessibilityWindowInfo for the window title.
        return null
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun currentTimeMs(): Long = System.currentTimeMillis()

    companion object {
        /** Max chars to read before/after cursor. */
        private const val MAX_TEXT_LENGTH = 1024
    }
}
