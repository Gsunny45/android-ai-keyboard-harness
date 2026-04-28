package dev.patrickgold.florisboard.ime.ai

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.ai.providers.KeyVault
import dev.patrickgold.florisboard.ime.ai.trigger.TriggerConfigStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Activity for managing provider API keys stored in the encrypted KeyVault.
 *
 * Reads the provider keyRef list from triggers.json on disk, then for each
 * provider displays the status (configured / missing) and offers an "Edit"
 * button that opens a dialog to paste or update the key.
 */
class CteKeysActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CteKeysActivity"
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val keyVault = KeyVault.getInstance(this)
        val providers = loadProvidersFromDisk()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KeysScreen(
                        providers = providers,
                        vault = keyVault,
                    )
                }
            }
        }
    }

    /**
     * Reads triggers.json from the CTE config directory and extracts
     * each provider entry that has a keyRef.
     */
    private fun loadProvidersFromDisk(): List<ProviderKeyEntry> {
        val configDir = TriggerConfigStore.getInstance(this).getConfigsDir()
        val triggersFile = File(configDir, "triggers.json")

        if (!triggersFile.exists()) {
            Log.w(TAG, "triggers.json not found at ${triggersFile.path}")
            return emptyList()
        }

        return try {
            val text = triggersFile.readText()
            val parsed = json.decodeFromString<TriggersConfigFile>(text)
            parsed.providers.mapNotNull { (id, cfg) ->
                cfg.keyRef?.let { keyRef ->
                    ProviderKeyEntry(
                        providerId = id,
                        keyRef = keyRef,
                        url = cfg.url,
                    )
                }
            }.sortedBy { it.keyRef }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse triggers.json", e)
            emptyList()
        }
    }

    // ── Data classes for parsing ─────────────────────────────────────────

    @Serializable
    data class TriggersConfigFile(
        val providers: Map<String, ProviderEntry> = emptyMap(),
    )

    @Serializable
    data class ProviderEntry(
        val url: String = "",
        val keyRef: String? = null,
    )

    data class ProviderKeyEntry(
        val providerId: String,
        val keyRef: String,
        val url: String,
    )
}

// ── Compose UI ─────────────────────────────────────────────────────────────

@Composable
private fun KeysScreen(
    providers: List<CteKeysActivity.ProviderKeyEntry>,
    vault: KeyVault,
) {
    // Triggers recomposition after a key is saved
    var refreshKey by remember { mutableStateOf(0L) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "API Keys",
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Manage provider API keys. Keys are stored in AES-256-GCM encrypted storage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (providers.isEmpty()) {
            Text(
                text = "No providers with keyRef found in triggers.json",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            providers.forEach { entry ->
                // Re-read from vault after every save
                val currentKey = vault.get(entry.keyRef)
                ProviderKeyCard(
                    entry = entry,
                    hasKey = currentKey != null,
                    maskedKey = maskKey(currentKey),
                    onSaved = { refreshKey = System.currentTimeMillis() },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ProviderKeyCard(
    entry: CteKeysActivity.ProviderKeyEntry,
    hasKey: Boolean,
    maskedKey: String?,
    onSaved: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.providerId,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "keyRef: ${entry.keyRef}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick = { showDialog = true },
                ) {
                    Text(if (hasKey) "Edit" else "Set Key")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Key status row
            Text(
                text = if (hasKey) {
                    "Key: $maskedKey"
                } else {
                    "Key: not set"
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (hasKey) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }

    if (showDialog) {
        KeyEditDialog(
            providerId = entry.providerId,
            keyRef = entry.keyRef,
            onDismiss = { showDialog = false },
            onSave = { newKey ->
                KeyVault.getInstance(context).set(entry.keyRef, newKey)
                showDialog = false
                Toast.makeText(context, "${entry.providerId} key saved", Toast.LENGTH_SHORT).show()
                onSaved()
            },
        )
    }
}

@Composable
private fun KeyEditDialog(
    providerId: String,
    keyRef: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Key: $providerId") },
        text = {
            Column {
                Text(
                    text = "keyRef: $keyRef",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("API Key") },
                    placeholder = { Text("Paste your API key here") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "Hide key" else "Show key")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        onSave(input.trim())
                    }
                },
                enabled = input.isNotBlank(),
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

/** Masks a key string showing only the last 4 characters. */
private fun maskKey(key: String?): String? {
    if (key == null || key.length < 4) return key
    val visible = key.takeLast(4)
    return "${"*".repeat(key.length - 4)}$visible"
}
