package dev.patrickgold.florisboard.ime.ai.bridges

import android.content.Context
import android.os.LocaleList
import android.view.inputmethod.EditorInfo
import java.util.Locale

/**
 * Tracks which app the user is currently interacting with.
 *
 * The IME's [InputMethodService] receives an [EditorInfo] whenever the
 * text field changes focus, which includes the host app's package name.
 * This manager caches the last-known package so bridges and the voice
 * pipeline can route behaviour per-app (e.g. auto-skill in Obsidian).
 */
class AppProfileManager {

    private var currentEditorInfo: EditorInfo? = null

    /** The package ID of the foreground (IME-host) app, or null. */
    var currentPackageId: String? = null
        private set

    /** The primary locale of the active text field, or null. Used to set recognizer language. */
    var currentLocale: Locale? = null
        private set

    /**
     * Called by the IME service when [InputMethodService.onStartInputView]
     * or [InputMethodService.onFinishInputView] fires.
     */
    fun onEditorInfoReceived(info: EditorInfo?) {
        currentEditorInfo = info
        currentPackageId = info?.packageName
        currentLocale = info?.hintLocales?.get(0) ?: Locale.getDefault()
    }

    /** Convenience: returns [currentPackageId] as a non-null string or "unknown". */
    fun currentPackageIdOrDefault(): String = currentPackageId ?: "unknown"
}
