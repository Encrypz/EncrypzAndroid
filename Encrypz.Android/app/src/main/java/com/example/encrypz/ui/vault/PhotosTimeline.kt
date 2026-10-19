package com.example.encrypz.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import com.example.encrypz.theme.*

@Composable
internal fun PhotosTimeline(state: VaultUiState, viewModel: VaultViewModel,
    onFileClick: (VaultFileEntry) -> Unit, onAddClick: () -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val files = remember(state.files, query, filter) {
        state.files.filter { !it.isDeleted &&
            (it.category == VaultCategoryFilter.PHOTOS || it.category == VaultCategoryFilter.VIDEOS) &&
            (filter != "Photos" || it.category == VaultCategoryFilter.PHOTOS) &&
            (filter != "Videos" || it.category == VaultCategoryFilter.VIDEOS) &&
            it.matchesVaultSearch(query)
        }.sortedByDescending { it.sortDate }
    }
    val groups = remember(files) { files.groupBy { it.creationDateLabel } }
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 104.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
            item(key = "date:$date", span = { GridItemSpan(maxLineSpan) }) {
                Text(date, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp), color = EncrypzText)
            }
            items(entries, key = { it.id }) { file ->
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(EncrypzInput)
                    .semantics { contentDescription = "Open ${file.cleanFileName}" }
                    .clickable { onFileClick(file) }) {
                    VaultFileCardPreviewBox(file = file, viewModel = viewModel,
                        isCompactThumbnail = true, modifier = Modifier.fillMaxSize())

                }
            }
        }
    }
}
