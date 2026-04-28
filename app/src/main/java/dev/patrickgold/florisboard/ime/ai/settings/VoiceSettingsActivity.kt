package dev.patrickgold.florisboard.ime.ai.settings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.ai.voice.SpokenTriggerNormalizer

/**
 * Voice settings activity.
 *
 * Provides a CRUD editor for the spoken-trigger mapping table used by
 * [SpokenTriggerNormalizer]. Users can:
 *   - View all 20 spoken-form → canonical-trigger mappings
 *   - Add a new mapping
 *   - Edit an existing mapping
 *   - Delete a mapping
 *   - Restore the default 20-entry table
 */
class VoiceSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VoiceSettingsScreen()
                }
            }
        }
    }
}

// ── Data model ──────────────────────────────────────────────────────────────

/**
 * A single spoken-form → canonical-trigger mapping.
 */
data class TriggerMapping(
    val id: Long,
    val spokenForm: String,
    val canonicalTrigger: String,
)

// ── ViewModel-like state holder ─────────────────────────────────────────────

/**
 * In-memory CRUD store for trigger mappings.
 *
 * In a production app this would be backed by DataStore or Room, but for
 * this version the mappings are held in a mutable list and changes are
 * applied to the in-memory [SpokenTriggerNormalizer] instance.
 */
class TriggerMappingStore {
    private var nextId: Long = 0L
    private val _mappings = mutableStateListOf<TriggerMapping>()

    /** All mappings currently in the store. */
    val mappings: List<TriggerMapping> get() = _mappings.toList()

    init { resetToDefaults() }

    /** Populate the store from the canonical [SpokenTriggerNormalizer] defaults. */
    fun resetToDefaults() {
        _mappings.clear()
        nextId = 0L
        val normalizer = SpokenTriggerNormalizer()
        normalizer.allMappings().forEach { (spoken, canonical) ->
            _mappings.add(TriggerMapping(nextId++, spoken, canonical))
        }
    }

    /** Add a new mapping. */
    fun add(spokenForm: String, canonicalTrigger: String) {
        _mappings.add(TriggerMapping(nextId++, spokenForm, canonicalTrigger))
    }

    /** Update an existing mapping by ID. */
    fun update(id: Long, spokenForm: String, canonicalTrigger: String) {
        val index = _mappings.indexOfFirst { it.id == id }
        if (index >= 0) {
            _mappings[index] = TriggerMapping(id, spokenForm, canonicalTrigger)
        }
    }

    /** Remove a mapping by ID. */
    fun remove(id: Long) {
        _mappings.removeAll { it.id == id }
    }

    /** Convert to the normalizer-ready map format. */
    fun toNormalizerMap(): Map<String, String> = _mappings.associate {
        it.spokenForm to it.canonicalTrigger
    }
}

// ── Compose UI ──────────────────────────────────────────────────────────────

@Composable
private fun VoiceSettingsScreen() {
    val store = remember { TriggerMappingStore() }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingMapping by remember { mutableStateOf<TriggerMapping?>(null) }
    var deletingMapping by remember { mutableStateOf<TriggerMapping?>(null) }
    var confirmationText by remember { mutableStateOf<String?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add mapping")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(
                text = "Spoken Trigger Mappings",
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Manage the ${store.mappings.size} spoken-form → trigger mappings. " +
                        "Changes apply in-memory until saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Reset button
            TextButton(onClick = {
                store.resetToDefaults()
                confirmationText = "Defaults restored (${store.mappings.size} mappings)"
            }) {
                Text("Restore Defaults (20 entries)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Confirmation toast
            confirmationText?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Mapping list
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(store.mappings, key = { it.id }) { mapping ->
                    MappingCard(
                        mapping = mapping,
                        onEdit = { editingMapping = it },
                        onDelete = { deletingMapping = it },
                    )
                }
            }
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────

    if (showAddDialog) {
        MappingEditDialog(
            title = "Add Mapping",
            initialSpoken = "",
            initialCanonical = "",
            onDismiss = { showAddDialog = false },
            onSave = { spoken, canonical ->
                store.add(spoken, canonical)
                showAddDialog = false
            },
        )
    }

    editingMapping?.let { mapping ->
        MappingEditDialog(
            title = "Edit Mapping",
            initialSpoken = mapping.spokenForm,
            initialCanonical = mapping.canonicalTrigger,
            onDismiss = { editingMapping = null },
            onSave = { spoken, canonical ->
                store.update(mapping.id, spoken, canonical)
                editingMapping = null
            },
        )
    }

    deletingMapping?.let { mapping ->
        DeleteConfirmDialog(
            mapping = mapping,
            onDismiss = { deletingMapping = null },
            onConfirm = {
                store.remove(mapping.id)
                deletingMapping = null
            },
        )
    }
}

@Composable
private fun MappingCard(
    mapping: TriggerMapping,
    onEdit: (TriggerMapping) -> Unit,
    onDelete: (TriggerMapping) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mapping.spokenForm,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "→  ${mapping.canonicalTrigger}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            IconButton(onClick = { onEdit(mapping) }) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = { onDelete(mapping) }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun MappingEditDialog(
    title: String,
    initialSpoken: String,
    initialCanonical: String,
    onDismiss: () -> Unit,
    onSave: (spoken: String, canonical: String) -> Unit,
) {
    var spokenForm by remember { mutableStateOf(initialSpoken) }
    var canonicalTrigger by remember { mutableStateOf(initialCanonical) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = spokenForm,
                    onValueChange = { spokenForm = it },
                    label = { Text("Spoken Form") },
                    placeholder = { Text("e.g. slash fix") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = canonicalTrigger,
                    onValueChange = { canonicalTrigger = it },
                    label = { Text("Canonical Trigger") },
                    placeholder = { Text("e.g. /fix") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val spoken = spokenForm.trim().lowercase()
                    val canonical = canonicalTrigger.trim()
                    if (spoken.isNotBlank() && canonical.isNotBlank()) {
                        onSave(spoken, canonical)
                    }
                },
                enabled = spokenForm.isNotBlank() && canonicalTrigger.isNotBlank(),
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

@Composable
private fun DeleteConfirmDialog(
    mapping: TriggerMapping,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Mapping") },
        text = {
            Text("Remove \"${mapping.spokenForm}\" → ${mapping.canonicalTrigger}?")
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.onError)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
