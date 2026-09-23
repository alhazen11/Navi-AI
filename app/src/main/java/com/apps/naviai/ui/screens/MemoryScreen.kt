package com.apps.naviai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apps.naviai.memory.ConversationMemoryEntity
import com.apps.naviai.memory.MemoryCategory
import com.apps.naviai.memory.MemoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(onBack: () -> Unit, viewModel: MemoryViewModel = hiltViewModel()) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ConversationMemoryEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (memories.isNotEmpty()) {
                        TextButton(onClick = { showClearAllConfirm = true }) { Text("Clear all") }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }, modifier = Modifier.semantics { contentDescription = "Add memory" }) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        }
    ) { padding ->
        if (memories.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No memories yet. Say \"NAVI, ingat bahwa ...\" or tap + to add one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(memories, key = { it.id }) { memory ->
                    MemoryRow(
                        memory = memory,
                        onEdit = { editing = memory },
                        onDelete = { viewModel.deleteMemory(memory.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAddDialog) {
        MemoryEditDialog(
            initialContent = "",
            onDismiss = { showAddDialog = false },
            onSave = { content -> viewModel.addMemory(content); showAddDialog = false }
        )
    }

    editing?.let { memory ->
        MemoryEditDialog(
            initialContent = memory.content,
            onDismiss = { editing = null },
            onSave = { content ->
                viewModel.updateMemory(memory.id, content, memory.category, memory.isImportant)
                editing = null
            }
        )
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("Clear all memories?") },
            text = { Text("This deletes everything NAVI remembers about you. This can't be undone.") },
            confirmButton = {
                Button(onClick = { viewModel.clearAll(); showClearAllConfirm = false }) { Text("Delete all") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearAllConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MemoryRow(memory: ConversationMemoryEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (memory.isImportant) {
                    Icon(
                        Icons.Filled.Star, contentDescription = "Important",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                AssistChip(onClick = {}, label = { Text(categoryLabel(memory.category)) })
            }
            Text(memory.content, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
        IconButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = "Edit memory" }) {
            Icon(Icons.Filled.Edit, contentDescription = null)
        }
        IconButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = "Delete memory" }) {
            Icon(Icons.Filled.Delete, contentDescription = null)
        }
    }
}

@Composable
private fun MemoryEditDialog(initialContent: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initialContent) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialContent.isBlank()) "Add memory" else "Edit memory") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onSave(text) }) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun categoryLabel(category: MemoryCategory): String = when (category) {
    MemoryCategory.PERSONAL_PREFERENCE -> "Preference"
    MemoryCategory.ROUTE_INFORMATION -> "Route info"
    MemoryCategory.NAVIGATION_PREFERENCE -> "Navigation"
    MemoryCategory.GENERAL_MEMORY -> "General"
}
