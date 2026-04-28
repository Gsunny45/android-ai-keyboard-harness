package dev.patrickgold.florisboard.ime.ai

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.ai.orchestration.LlamaServerService
import dev.patrickgold.florisboard.ime.ai.providers.LlamaCppLocal
import dev.patrickgold.florisboard.ime.ai.settings.VoiceSettingsActivity
import dev.patrickgold.florisboard.ime.ai.trigger.TriggerConfigStore
import kotlinx.coroutines.launch

/**
 * CTE settings activity.
 *
 * Provides a minimal settings surface for the Context-aware Type Engine:
 *   - "Reload config from disk" button to hot-reload CTE configuration
 *   - "API Keys" button to manage provider API keys in encrypted storage
 *
 * Future iterations will add diagnostics and cost dashboards as described
 * in ONBOARDING.md.
 */
class CteSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure defaults are seeded on first visit
        TriggerConfigStore.getInstance(this).ensureDefaults()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(modifier = Modifier.height(48.dp))

                        Text(
                            text = "CTE Configuration",
                            style = MaterialTheme.typography.headlineSmall,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Context-aware Type Engine",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(40.dp))

                        // ── General ──
                        Button(
                            onClick = { reloadConfig() },
                            modifier = Modifier.width(280.dp),
                        ) {
                            Text("Reload config from disk")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { openApiKeys() },
                            modifier = Modifier.width(280.dp),
                        ) {
                            Text("Manage API Keys")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { openVoiceSettings() },
                            modifier = Modifier.width(280.dp),
                        ) {
                            Text("Voice Trigger Mappings")
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider(modifier = Modifier.width(280.dp))
                        Spacer(modifier = Modifier.height(16.dp))

                        // ── Local LLaMA section ──
                        Text(
                            text = "Local LLaMA Server",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "PATH 1: llama-server in Termux\nPATH 2: bundled .so (stub)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { startMonitor() },
                            modifier = Modifier.width(280.dp),
                        ) {
                            Text("Start Monitor (foreground)")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Test Connection button with state
                        var testing by remember { mutableStateOf(false) }
                        val scope = rememberCoroutineScope()

                        Button(
                            onClick = {
                                testing = true
                                scope.launch {
                                    testLocalConnection()
                                    testing = false
                                }
                            },
                            modifier = Modifier.width(280.dp),
                            enabled = !testing,
                        ) {
                            Text(if (testing) "Testing..." else "Test Connection")
                        }
                    }
                }
            }
        }
    }

    private fun reloadConfig() {
        val store = TriggerConfigStore.getInstance(this)
        val ok = store.reloadConfig()
        val message = if (ok) {
            "CTE config reloaded from ${store.getCteRoot().path}"
        } else {
            "CTE config reload failed — check logs"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun openApiKeys() {
        startActivity(Intent(this, CteKeysActivity::class.java))
    }

    private fun openVoiceSettings() {
        startActivity(Intent(this, VoiceSettingsActivity::class.java))
    }

    private fun startMonitor() {
        LlamaServerService.start(this)
        Toast.makeText(
            this,
            "LLaMA monitor started — check notification",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * Tests connectivity to the local llama-server by sending a 5-token prompt.
     * Shows a Toast with the result.
     */
    private suspend fun testLocalConnection() {
        val (latencyMs, success, error) = LlamaCppLocal.testConnection(
            url = "http://127.0.0.1:8080/v1/chat/completions",
        )
        val message = if (success) {
            "LLaMA OK — ${latencyMs}ms"
        } else {
            "LLaMA FAIL — ${error ?: "unknown"} (${latencyMs}ms)"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
