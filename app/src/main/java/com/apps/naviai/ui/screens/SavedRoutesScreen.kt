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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.apps.naviai.database.RouteEntity
import com.apps.naviai.ui.viewmodel.SavedRoutesViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedRoutesScreen(
    onBack: () -> Unit,
    onNavigateRoute: (String) -> Unit,
    viewModel: SavedRoutesViewModel = hiltViewModel()
) {
    val routes by viewModel.routes.collectAsStateWithLifecycle()
    var routeBeingRenamed by remember { mutableStateOf<RouteEntity?>(null) }

    routeBeingRenamed?.let { route ->
        RenameRouteDialog(
            currentName = route.name,
            onConfirm = { newName ->
                viewModel.renameRoute(route.id, newName)
                routeBeingRenamed = null
            },
            onDismiss = { routeBeingRenamed = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Routes") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        if (routes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No routes saved yet. Record one from Home.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(routes, key = { it.id }) { route ->
                RouteRow(
                    route = route,
                    onNavigate = { onNavigateRoute(route.name) },
                    onRename = { routeBeingRenamed = route },
                    onDelete = { viewModel.deleteRoute(route.id) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RouteRow(route: RouteEntity, onNavigate: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(route.name, style = MaterialTheme.typography.titleMedium)
        Text(
            "Created: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(route.createdAt))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Distance: %.0fm  ·  Points: %d".format(route.totalDistanceMeters, route.totalPoints),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onNavigate, modifier = Modifier.semantics { contentDescription = "Navigate ${route.name}" }) {
                Text("Navigate")
            }
            OutlinedButton(onClick = onRename, modifier = Modifier.semantics { contentDescription = "Rename ${route.name}" }) {
                Text("Rename")
            }
            OutlinedButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = "Delete ${route.name}" }) {
                Text("Delete")
            }
        }
    }
}

/**
 * Rename confirmation dialog for the "Rename" button -- the button's own
 * voice-command equivalent is [com.apps.naviai.recording.RouteRenameMatcher]
 * ("ganti nama rute X menjadi Y"/"rename route X to Y"), handled entirely
 * server-side in [com.apps.naviai.ui.viewmodel.DetectionViewModel] without
 * ever opening this screen; this dialog is the manual/typed path for when
 * voice isn't convenient or the name was misheard.
 */
@Composable
private fun RenameRouteDialog(currentName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(currentName) { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename route") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Route name") },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "New route name" }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
