package com.example.encrypz.ui.vault

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun PhotoViewer(photos: List<VaultFileEntry>, initialId: String, viewModel: VaultViewModel,
    onDismiss: () -> Unit, onDownload: (VaultFileEntry) -> Unit, onMove: (VaultFileEntry) -> Unit,
    onDelete: (VaultFileEntry) -> Unit, onDetails: (VaultFileEntry) -> Unit) {
    val pager = rememberPagerState(initialPage = photos.indexOfFirst { it.id == initialId }.coerceAtLeast(0),
        pageCount = { photos.size })
    var menu by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    val current = photos[pager.currentPage.coerceIn(photos.indices)]
    LaunchedEffect(pager.currentPage) { zoomed = false; menu = false }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current.creationDateLabel, color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text("${pager.currentPage + 1} of ${photos.size}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Photo actions", tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; onDownload(current) })
                        DropdownMenuItem(text = { Text("Move to folder") }, onClick = { menu = false; onMove(current) })
                        DropdownMenuItem(text = { Text("File details") }, onClick = { menu = false; onDetails(current) })
                        DropdownMenuItem(text = { Text("Move to Recycle Bin") }, onClick = { menu = false; onDelete(current) })
                    }
                }
            }
            HorizontalPager(state = pager, key = { photos[it].id }, userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxWidth().weight(1f)) { index ->
                val file = photos[index]
                val bitmap = rememberDecryptedImageBitmap(file, viewModel, maxDimension = 2048)
                var scale by remember(file.id) { mutableFloatStateOf(1f) }
                var offset by remember(file.id) { mutableStateOf(Offset.Zero) }
                LaunchedEffect(pager.currentPage) { scale = 1f; offset = Offset.Zero }
                BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                    val width = constraints.maxWidth.toFloat()
                    val height = constraints.maxHeight.toFloat()
                    val transform = rememberTransformableState { zoom, pan, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        val maxX = width * (scale - 1) / 2
                        val maxY = height * (scale - 1) / 2
                        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                        if (index == pager.currentPage) zoomed = scale > 1f
                    }
                    if (bitmap != null) Image(bitmap, file.cleanFileName, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(file.id) { detectTapGestures(onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2.5f
                                offset = Offset.Zero
                                zoomed = scale > 1f
                            }) }
                            .transformable(transform, canPan = { scale > 1f })
                            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
                    else Text("Preview unavailable", color = Color.LightGray)
                }
            }
            Text("Pinch to zoom ? Swipe to browse", color = Color.LightGray,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
        }
    }
}
