package com.encrypz.privacy.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.encrypz.privacy.theme.*

@Composable
internal fun PhotosTimeline(state: VaultUiState, viewModel: VaultViewModel,
    onFileClick: (VaultFileEntry) -> Unit, onAddClick: () -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    val files = remember(state.files, query, filter) {
        state.files.filter { !it.isDeleted &&
            (it.category == VaultCategoryFilter.PHOTOS || it.category == VaultCategoryFilter.VIDEOS) &&
            (filter != "Photos" || it.category == VaultCategoryFilter.PHOTOS) &&
            (filter != "Videos" || it.category == VaultCategoryFilter.VIDEOS) &&
            it.matchesVaultSearch(query)
        }.sortedByDescending { it.sortDate }
    }
    val groups = remember(files) { files.groupBy { it.creationDateLabel } }
    // Keep actions scoped to the current search/filter and available media.
    val visibleSelectedIds = selectedIds.filter { id -> files.any { it.id == id } }
    LaunchedEffect(files) { selectedIds = visibleSelectedIds }
    fun finishSelection() {
        selecting = false
        selectedIds = emptyList()
        showDeleteConfirmation = false
    }
    fun toggleSelection(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    BackHandler(enabled = selecting) { finishSelection() }
    LaunchedEffect(state.isVaultUnlocked) {
        if (!state.isVaultUnlocked) finishSelection()
    }
    if (showDeleteConfirmation && visibleSelectedIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Move ${visibleSelectedIds.size} items to Recycle Bin?") },
            text = { Text("You can restore these items from the Recycle Bin.") },
            confirmButton = {
                TextButton(onClick = {
                    visibleSelectedIds.forEach { viewModel.moveFileToRecycleBin(it, context) }
                    finishSelection()
                }) { Text("Move to Recycle Bin") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") }
            }
        )
    }
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 104.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (selecting) "${visibleSelectedIds.size} selected" else "Photos",
                        style = MaterialTheme.typography.titleMedium)
                    if (selecting) {
                        TextButton(onClick = { finishSelection() }) { Text("Cancel") }
                    } else {
                        TextButton(onClick = { selecting = true }, enabled = files.isNotEmpty()) { Text("Select") }
                    }
                }
                if (selecting) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val allSelected = files.isNotEmpty() && visibleSelectedIds.size == files.size
                        TextButton(onClick = {
                            selectedIds = if (allSelected) emptyList() else files.map { it.id }
                        }, enabled = files.isNotEmpty()) { Text(if (allSelected) "Deselect all" else "Select all") }
                        TextButton(onClick = { showDeleteConfirmation = true },
                            enabled = visibleSelectedIds.isNotEmpty()) { Text("Move to Recycle Bin") }
                    }
                }
                OutlinedTextField(value = query, onValueChange = { query = it },
                    placeholder = { Text("Search name or date") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = {
                                val calendar = java.util.Calendar.getInstance()
                                parseVaultDate(query, "yyyy-MM-dd")?.let { calendar.timeInMillis = it }
                                android.app.DatePickerDialog(context, { _, year, month, day ->
                                    query = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day)
                                }, calendar.get(java.util.Calendar.YEAR),
                                    calendar.get(java.util.Calendar.MONTH),
                                    calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
                            }) {
                                Icon(Icons.Default.DateRange, contentDescription = "Search photos by creation date")
                            }
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    }, singleLine = true, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Photos", "Videos").forEach { label ->
                        FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
                    }
                }
            }
        }
        if (files.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.PhotoLibrary, null, Modifier.size(48.dp), tint = EncrypzMuted)
                Text(if (query.isBlank()) "Your memories belong here" else "No matching photos",
                    style = MaterialTheme.typography.titleMedium)
                Text(if (query.isBlank()) "Add photos and videos to your private vault." else "Try another filename, date, or filter.",
                    style = MaterialTheme.typography.bodyMedium, color = EncrypzMuted)
                if (query.isBlank()) FilledTonalButton(onClick = onAddClick) { Text("Add photos") }
            }
        }
        groups.forEach { (date, entries) ->
            item(key = "date:$date", contentType = "date", span = { GridItemSpan(maxLineSpan) }) {
                Text(date, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp), color = EncrypzText)
            }
            items(entries, key = { it.id }, contentType = { "media" }) { file ->
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(EncrypzInput)
                    .semantics {
                        contentDescription = if (selecting) "Select ${file.cleanFileName}" else "Open ${file.cleanFileName}"
                        if (selecting) selected = file.id in visibleSelectedIds
                    }
                    .combinedClickable(
                        onClick = { if (selecting) toggleSelection(file.id) else onFileClick(file) },
                        onLongClickLabel = "Select item",
                        onLongClick = { selecting = true; toggleSelection(file.id) }
                    )) {
                    VaultFileCardPreviewBox(file = file, viewModel = viewModel,
                        isCompactThumbnail = true, modifier = Modifier.fillMaxSize())
                    if (selecting) {
                        Box(Modifier.fillMaxSize().background(
                            if (file.id in visibleSelectedIds) EncrypzBlue.copy(alpha = 0.28f)
                            else Color.Transparent))
                        Checkbox(checked = file.id in visibleSelectedIds,
                            onCheckedChange = { toggleSelection(file.id) },
                            modifier = Modifier.align(Alignment.TopEnd))
                    }
                }
            }
        }
    }
}
