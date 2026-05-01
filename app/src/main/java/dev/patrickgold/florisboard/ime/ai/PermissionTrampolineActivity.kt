package dev.patrickgold.florisboard.ime.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Transparent trampoline activity that requests RECORD_AUDIO runtime permission
 * on behalf of FlorisImeService (which, as an IME Service, cannot call
 * requestPermissions() directly).
 *
 * Flow:
 *   1. IME detects RECORD_AUDIO is not granted → launches this activity
 *      (Intent.FLAG_ACTIVITY_NEW_TASK).
 *   2. On create, this activity uses registerForActivityResult to request
 *      the permission, showing the system dialog.
 *   3. On result, broadcasts ACTION_VOICE_PERMISSION_GRANTED or
 *      ACTION_VOICE_PERMISSION_DENIED so FlorisImeService can react.
 *   4. Activity finishes (fully transparent — user never sees it).
 */
class PermissionTrampolineActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PermissionTrampoline"

        /**
         * Action broadcast when RECORD_AUDIO is granted (or already was).
         * FlorisImeService listens for this to start voice recording.
         */
        const val ACTION_VOICE_PERMISSION_GRANTED =
            "dev.patrickgold.florisboard.VOICE_PERMISSION_GRANTED"

        /**
         * Action broadcast when RECORD_AUDIO is denied by the user.
         */
        const val ACTION_VOICE_PERMISSION_DENIED =
            "dev.patrickgold.florisboard.VOICE_PERMISSION_DENIED"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.d(TAG, "Permission result: ${if (granted) "GRANTED" else "DENIED"}")
        broadcastResult(granted)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Trampoline created — checking RECORD_AUDIO permission")

        // Check if permission is already granted (e.g. via ADB or previous grant)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "RECORD_AUDIO already granted — notifying IME")
            broadcastResult(granted = true)
            finish()
            return
        }

        // Request the permission — system dialog will be shown
        Log.d(TAG, "Requesting RECORD_AUDIO permission from user")
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun broadcastResult(granted: Boolean) {
        val action = if (granted) {
            ACTION_VOICE_PERMISSION_GRANTED
        } else {
            ACTION_VOICE_PERMISSION_DENIED
        }
        sendBroadcast(Intent(action))
    }
}
