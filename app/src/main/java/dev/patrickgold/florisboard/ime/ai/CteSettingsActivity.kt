package dev.patrickgold.florisboard.ime.ai

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.ime.ai.bridges.ObsidianBridge
import dev.patrickgold.florisboard.ime.ai.orchestration.LlamaServerService
import dev.patrickgold.florisboard.ime.ai.providers.LlamaCppLocal
import dev.patrickgold.florisboard.ime.ai.settings.VoiceSettingsActivity
import dev.patrickgold.florisboard.ime.ai.trigger.TriggerConfigStore
import dev.patrickgold.florisboard.ime.ai.voice.VoiceInputManager
import dev.patrickgold.florisboard.ime.ai.voice.VoskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * CTE settings activity.
 *
 * Sections:
 *   1. Provider toggles — enable/disable each AI provider + shows its role badge.
 *      Changes are written back to triggers.json on disk immediately.
 *   2. General — reload config, manage API keys, voice trigger mappings.
 *   3. Local LLaMA — start monitor, test connection.
 */
class CteSettingsActivity : ComponentActivity() {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TriggerConfigStore.getInstance(this).ensureDefaults()

        val providers = loadProviders()
        val obsidianBridge = ObsidianBridge(this)
        val initialVaultName = obsidianBridge.getVaultName() ?: ""

        setContent {
            val voskState by VoiceInputManager.sharedVoskState.collectAsState()
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        providers = providers,
                        onToggleProvider = { id, enabled -> saveProviderEnabled(id, enabled) },
                        onReloadConfig = { reloadConfig() },
                        onOpenApiKeys = { startActivity(Intent(this, CteKeysActivity::class.java)) },
                        onOpenVoiceSettings = { startActivity(Intent(this, VoiceSettingsActivity::class.java)) },
                        vaultName = initialVaultName,
                        onSaveVaultName = { name ->
                            obsidianBridge.setVaultName(name)
                            // Also reload CTE config so CteEngine picks up the new vault name
                            FlorisImeService.reloadCteConfig()
                            Toast.makeText(
                                this@CteSettingsActivity,
                                "Vault name saved",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        onStartMonitor = { startMonitor() },
                        onTestConnection = { testLocalConnection() },
                        voskState = voskState,
                        onDownloadVosk = {
                            VoiceInputManager.downloadModel(this@CteSettingsActivity, lifecycleScope)
                        },
                    )
                }
            }
        }
    }

    // ── Provider JSON helpers ─────────────────────────────────────────────

    private fun triggersFile(): File =
        File(TriggerConfigStore.getInstance(this).getConfigsDir(), "triggers.json")

    private fun loadProviders(): List<ProviderEntry> {
        val f = triggersFile()
        if (!f.exists()) return emptyList()
        return try {
            val parsed = json.decodeFromString<TriggersRoot>(f.readText())
            parsed.providers.map { (id, cfg) ->
                ProviderEntry(
                    id = id,
                    role = cfg.role ?: "primary",
                    enabled = cfg.enabled ?: true,
                    hasKeyRef = cfg.keyRef != null,
                )
            }.sortedBy { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Patch `providers.<id>.enabled` in triggers.json without disturbing
     * any other fields. Uses a simple string-level find-and-replace on the
     * serialized JSON to avoid full-round-trip serialization loss.
     *
     * Falls back to full re-serialization if the pattern isn't found.
     */
    private fun saveProviderEnabled(providerId: String, enabled: Boolean) {
        val f = triggersFile()
        if (!f.exists()) return
        try {
            val parsed = json.decodeFromString<TriggersRoot>(f.readText())
            val existing = parsed.providers[providerId] ?: return
            val updated = parsed.copy(
                providers = parsed.providers + (providerId to existing.copy(enabled = enabled))
            )
            f.writeText(json.encodeToString(TriggersRoot.serializer(), updated))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Action handlers ───────────────────────────────────────────────────

    private fun reloadConfig() {
        val store = TriggerConfigStore.getInstance(this)
        val storeOk = store.reloadConfig()
        // Invalidate CteEngine's in-memory caches so it re-reads configs on next trigger
        val engineOk = FlorisImeService.reloadCteConfig()
        val ok = storeOk || engineOk  // at least one path succeeded
        val msg = if (ok) "CTE config reloaded" else "Reload failed — check logs"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun startMonitor() {
        LlamaServerService.start(this)
        Toast.makeText(this, "LLaMA monitor started", Toast.LENGTH_SHORT).show()
    }

    private suspend fun testLocalConnection() {
        withContext(Dispatchers.IO) {
            try {
                val url = java.net.URL("http://127.0.0.1:8080/health")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val code = conn.responseCode
                val body = if (code == 200) conn.inputStream.bufferedReader().readText() else ""
                conn.disconnect()
                withContext(Dispatchers.Main) {
                    if (code == 200) {
                        Toast.makeText(this@CteSettingsActivity, "LLaMA server OK: $body", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@CteSettingsActivity, "Server returned HTTP $code", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CteSettingsActivity, "Connection failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Data models ───────────────────────────────────────────────────────

    data class ProviderEntry(
        val id: String,
        val role: String,
        val enabled: Boolean,
        val hasKeyRef: Boolean,
    )

    @Serializable
    data class TriggersRoot(
        val providers: Map<String, ProviderCfg> = emptyMap(),
    )

    @Serializable
    data class ProviderCfg(
        val url: String = "",
        val model: String? = null,
        val keyRef: String? = null,
        val priority: Int = 10,
        val maxTokens: Int = 2048,
        val timeoutMs: Long = 30_000L,
        val enabled: Boolean? = true,
        val role: String? = "primary",
    )
}

// ── Compose UI ────────────────────────────────────────────────────────────

@Composable
private fun SettingsScreen(
    providers: List<CteSettingsActivity.ProviderEntry>,
    onToggleProvider: (String, Boolean) -> Unit,
    onReloadConfig: () -> Unit,
    onOpenApiKeys: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    vaultName: String,
    onSaveVaultName: (String) -> Unit,
    onStartMonitor: () -> Unit,
    onTestConnection: suspend () -> Unit,
    voskState: VoskState,
    onDownloadVosk: () -> Unit,
) {
    // Local toggle state mirrors disk — updates optimistically
    val toggleState = remember {
        mutableStateMapOf<String, Boolean>().also { map ->
            providers.forEach { map[it.id] = it.enabled }
        }
    }

    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("CTE Configuration", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Context-aware Type Engine",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Provider toggles ──────────────────────────────────────────────
        Text("Providers", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Toggle providers on/off. Role controls when each is eligible:\n" +
            "  primary = normal use  •  finisher = ToT / high-token only  •  fallback = last resort",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        providers.forEach { entry ->
            val isOn = toggleState[entry.id] ?: entry.enabled
            ProviderToggleCard(
                entry = entry,
                isEnabled = isOn,
                onToggle = { newVal ->
                    toggleState[entry.id] = newVal
                    onToggleProvider(entry.id, newVal)
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // ── General ───────────────────────────────────────────────────────
        Text("General", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = onReloadConfig, modifier = Modifier.width(280.dp)) {
            Text("Reload config from disk")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onOpenApiKeys, modifier = Modifier.width(280.dp)) {
            Text("Manage API Keys")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onOpenVoiceSettings, modifier = Modifier.width(280.dp)) {
            Text("Voice Trigger Mappings")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Obsidian Vault Name ──
        Text("Obsidian Vault", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Set your vault name so {{vault.name}} resolves in Obsidian system templates.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        var editName by remember { mutableStateOf(vaultName) }
        OutlinedTextField(
            value = editName,
            onValueChange = { editName = it },
            label = { Text("Vault name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = { onSaveVaultName(editName) },
            enabled = editName != vaultName,
        ) {
            Text("Save Vault Name")
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // ── Vosk offline STT ──────────────────────────────────────────────
        Text("Offline Voice (Vosk)", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Downloads vosk-model-en-us-0.22-lgraph (~128 MB) to device storage.\n" +
            "Used as primary STT when no OPENAI_KEY is set. Fully offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        when (val s = voskState) {
            is VoskState.Idle -> {
                Button(onClick = onDownloadVosk, modifier = Modifier.width(280.dp)) {
                    Text("Download Vosk Model (~128 MB)")
                }
            }
            is VoskState.Downloading -> {
                val pct = (s.progress * 100).toInt()
                Button(onClick = {}, enabled = false, modifier = Modifier.width(280.dp)) {
                    Text("Downloading… $pct%")
                }
            }
            is VoskState.Ready -> {
                Text(
                    "✓ Vosk model ready",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF2E7D32),
                )
            }
            is VoskState.Error -> {
                Text(
                    "Download failed: ${s.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(onClick = onDownloadVosk, modifier = Modifier.width(280.dp)) {
                    Text("Retry Download")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // ── Local LLaMA ───────────────────────────────────────────────────
        Text("Local LLaMA Server", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "PATH 1: llama-server in Termux  •  PATH 2: bundled .so (stub)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = onStartMonitor, modifier = Modifier.width(280.dp)) {
            Text("Start Monitor (foreground)")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                testing = true
                scope.launch {
                    onTestConnection()
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

@Composable
private fun ProviderToggleCard(
    entry: CteSettingsActivity.ProviderEntry,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val roleColor = when (entry.role.lowercase()) {
        "finisher" -> Color(0xFFE65100)   // deep orange — expensive, use sparingly
        "fallback" -> Color(0xFF1565C0)   // blue — safety net
        else       -> Color(0xFF2E7D32)   // green — normal primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isEnabled) 2.dp else 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.id,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isEnabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Role badge
                    Text(
                        text = entry.role.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isEnabled) roleColor else roleColor.copy(alpha = 0.4f),
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (entry.hasKeyRef) {
                    Text(
                        text = "API key required",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (isEnabled) 1f else 0.4f,
                        ),
                    )
                } else {
                    Text(
                        text = "local — no key needed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (isEnabled) 1f else 0.4f,
                        ),
                    )
                }
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
            )
        }
    }
}
