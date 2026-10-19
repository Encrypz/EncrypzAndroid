package com.example.encrypz.ui.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.encrypz.theme.EncrypzBlack
import com.example.encrypz.theme.EncrypzBlue
import com.example.encrypz.theme.EncrypzBlueBright
import com.example.encrypz.theme.EncrypzBlueSoft
import com.example.encrypz.theme.EncrypzBorder
import com.example.encrypz.theme.EncrypzBorderGlow
import com.example.encrypz.theme.EncrypzDeepBlack
import com.example.encrypz.theme.EncrypzEmeraldBright
import com.example.encrypz.theme.EncrypzGlassBorder
import com.example.encrypz.theme.EncrypzGlowCyan
import com.example.encrypz.theme.EncrypzInput
import com.example.encrypz.theme.EncrypzMuted
import com.example.encrypz.theme.EncrypzPanel
import com.example.encrypz.theme.EncrypzPurple
import com.example.encrypz.theme.EncrypzSecondaryBg
import com.example.encrypz.theme.EncrypzSubtleText
import com.example.encrypz.theme.EncrypzSurface
import com.example.encrypz.theme.EncrypzText
import java.io.ByteArrayOutputStream
import java.util.Locale

data class PickedDeviceFileData(
    val fileName: String,
    val rawBytes: ByteArray,
    val thumbnailBytes: ByteArray?,
    val originalSizeBytes: Long
)

/**
 * Reads a picked device file Uri completely (up to 15 MB) and extracts an in-memory preview
 * thumbnail for images, videos (via MediaMetadataRetriever), and PDFs (via PdfRenderer).
 */
fun readPickedUriForVault(context: Context, uri: Uri): PickedDeviceFileData {
    var resolvedName = "document_${System.currentTimeMillis()}.bin"
    var originalSize = 0L

    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) {
                    resolvedName = cursor.getString(nameIdx) ?: resolvedName
                }
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    originalSize = cursor.getLong(sizeIdx)
                }
            }
        }
    } catch (_: Exception) {}

    if (originalSize > MAX_IMPORT_BYTES) {
        throw java.io.IOException("$resolvedName exceeds the 15 MiB file limit.")
    }
    val rawBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
        readImportBytes(stream)
    } ?: throw java.io.IOException("Unable to open $resolvedName.")
    originalSize = rawBytes.size.toLong()

    val ext = resolvedName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val mimeType = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT) ?: ""

    var thumbnailBytes: ByteArray? = null

    try {
        when {
            mimeType.startsWith("image/") || ext in listOf("jpg", "jpeg", "png", "webp", "bmp", "gif") -> {
                val decoded = decodeSampledBitmap(rawBytes, maxDimension = 960)
                if (decoded != null) {
                    thumbnailBytes = bitmapToJpegBytes(decoded, quality = 85)
                }
            }
            mimeType.startsWith("video/") || ext in listOf("mp4", "mov", "webm", "mkv", "avi", "3gp") -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                    if (frame != null) {
                        thumbnailBytes = bitmapToJpegBytes(frame, quality = 82)
                    }
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {}
                }
            }
            mimeType == "application/pdf" || ext == "pdf" -> {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (renderer.pageCount > 0) {
                            renderer.openPage(0).use { page ->
                                val width = (page.width * 1.5f).toInt().coerceIn(300, 1080)
                                val height = (page.height * 1.5f).toInt().coerceIn(400, 1440)
                                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                thumbnailBytes = bitmapToJpegBytes(bmp, quality = 88)
                            }
                        }
                    }
                }
            }
        }
    } catch (_: Exception) {}

    return PickedDeviceFileData(
        fileName = resolvedName,
        rawBytes = rawBytes,
        thumbnailBytes = thumbnailBytes,
        originalSizeBytes = originalSize
    )
}

private fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? {
    if (bytes.isEmpty()) return null
    return try {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

        var sampleSize = 1
        while (boundsOptions.outWidth / sampleSize > maxDimension ||
            boundsOptions.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    } catch (_: Exception) {
        null
    }
}

private fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

/**
 * Generates an in-memory JPEG preview thumbnail from already-decrypted file bytes
 * (used when importing or syncing an encrypted .enc file from Google Drive).
 */
fun extractThumbnailFromDecryptedBytes(
    context: Context,
    cleanFileName: String,
    decryptedBytes: ByteArray
): ByteArray? {
    val ext = cleanFileName.removeSuffix(".enc").substringAfterLast('.', "").lowercase(Locale.ROOT)
    return try {
        when (ext) {
            "jpg", "jpeg", "png", "webp", "bmp", "gif" -> {
                decodeSampledBitmap(decryptedBytes, maxDimension = 960)?.let {
                    bitmapToJpegBytes(it, quality = 85)
                }
            }
            "pdf" -> {
                val tempPdf = java.io.File(context.cacheDir, "preview_${System.currentTimeMillis()}.pdf")
                try {
                    tempPdf.writeBytes(decryptedBytes)
                    android.os.ParcelFileDescriptor.open(
                        tempPdf,
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY
                    ).use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            if (renderer.pageCount > 0) {
                                renderer.openPage(0).use { page ->
                                    val width = (page.width * 1.5f).toInt().coerceIn(300, 1080)
                                    val height = (page.height * 1.5f).toInt().coerceIn(400, 1440)
                                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    bmp.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    bitmapToJpegBytes(bmp, quality = 88)
                                }
                            } else null
                        }
                    }
                } finally {
                    try {
                        tempPdf.delete()
                    } catch (_: Exception) {}
                }
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}


@Composable
fun rememberDecryptedImageBitmap(
    file: VaultFileEntry,
    viewModel: VaultViewModel,
    maxDimension: Int = 960
): ImageBitmap? {
    return remember(file.id, maxDimension) {
        val thumbBytes = viewModel.decryptThumbnailBytes(file)
        val candidateBytes = thumbBytes ?: viewModel.decryptFileBytes(file)
        if (candidateBytes != null) {
            decodeSampledBitmap(candidateBytes, maxDimension)?.asImageBitmap()
        } else {
            null
        }
    }
}

/**
 * Compact visual preview rendered inside Grid and List cards so every file in the vault
 * shows an immediate visual preview (photo, video frame, PDF page, or document snippet).
 */
@Composable
fun VaultFileCardPreviewBox(
    file: VaultFileEntry,
    viewModel: VaultViewModel,
    modifier: Modifier = Modifier,
    isCompactThumbnail: Boolean = false
) {
    val imageBitmap = rememberDecryptedImageBitmap(
        file = file,
        viewModel = viewModel,
        maxDimension = if (isCompactThumbnail) 256 else 640
    )
    val previewText = remember(file.id) {
        viewModel.decryptFilePreview(file)
    }

    Box(
        modifier = modifier
            .background(EncrypzInput),
        contentAlignment = Alignment.Center
    ) {
        when {
            imageBitmap != null -> {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = file.cleanFileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (file.category == VaultCategoryFilter.VIDEOS) {
                    Box(
                        modifier = Modifier
                            .size(if (isCompactThumbnail) 24.dp else 36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Video",
                            tint = Color.White,
                            modifier = Modifier.size(if (isCompactThumbnail) 14.dp else 20.dp)
                        )
                    }
                }
            }

            file.category == VaultCategoryFilter.PHOTOS -> {
                // Visual Photo / ID Scan Preview
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF0B2545),
                                    Color(0xFF134074),
                                    Color(0xFF0A192F)
                                )
                            )
                        )
                        .padding(if (isCompactThumbnail) 6.dp else 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCompactThumbnail) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = EncrypzGlowCyan,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (file.cleanFileName.contains("passport", ignoreCase = true)) {
                                    Icons.Default.Badge
                                } else {
                                    Icons.Default.Image
                                },
                                contentDescription = null,
                                tint = EncrypzGlowCyan,
                                modifier = Modifier.size(34.dp)
                            )
                            Text(
                                text = file.extension.uppercase(Locale.ROOT).ifBlank { "IMG" } + " PREVIEW",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = EncrypzBlueSoft
                            )
                        }
                    }
                }
            }

            file.category == VaultCategoryFilter.VIDEOS -> {
                // Visual Video Frame Preview with Play Button & Timecode
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF1E1035),
                                    Color(0xFF0F172A),
                                    Color(0xFF1E1B4B)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (isCompactThumbnail) 24.dp else 38.dp)
                            .clip(CircleShape)
                            .background(EncrypzPurple.copy(alpha = 0.28f))
                            .border(1.dp, EncrypzPurple, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(if (isCompactThumbnail) 15.dp else 22.dp)
                        )
                    }
                    if (!isCompactThumbnail) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp),
                            shape = RoundedCornerShape(4.dp),
                            color = Color.Black.copy(alpha = 0.65f)
                        ) {
                            Text(
                                text = "4K · 01:42",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp
                                ),
                                color = Color.White
                            )
                        }
                    }
                }
            }

            else -> {
                // Document / PDF / Text Miniature Page Preview
                if (isCompactThumbnail) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(EncrypzSecondaryBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (file.extension == "pdf") Icons.Default.PictureAsPdf else Icons.Default.Description,
                            contentDescription = null,
                            tint = if (file.extension == "pdf") Color(0xFFF87171) else EncrypzBlueBright,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0D131F))
                            .padding(10.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF141B29))
                                .border(1.dp, EncrypzBorder, RoundedCornerShape(6.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (file.extension == "pdf") Icons.Default.PictureAsPdf else Icons.Default.Description,
                                    contentDescription = null,
                                    tint = if (file.extension == "pdf") Color(0xFFF87171) else EncrypzBlueBright,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = file.extension.uppercase(Locale.ROOT).ifBlank { "DOC" },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = EncrypzBlueBright
                                )
                            }
                            Text(
                                text = previewText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 8.5.sp,
                                    lineHeight = 11.sp
                                ),
                                color = EncrypzMuted,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Rich, automatic in-memory File Preview Panel displayed immediately when a user taps a file.
 * Supports real decoded Bitmaps (Photos, PDF first page, Video frames), simulated visual ID/Video
 * previews for default vault items, and formatted text/document previews.
 */
@Composable
fun VaultFilePreviewSection(
    file: VaultFileEntry,
    viewModel: VaultViewModel,
    onCopyText: (String) -> Unit,
    onOpenFullScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val imageBitmap = rememberDecryptedImageBitmap(
        file = file,
        viewModel = viewModel,
        maxDimension = 1280
    )
    val decryptedText = remember(file.id) {
        viewModel.decryptFilePreview(file)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = EncrypzDeepBlack,
        border = BorderStroke(1.dp, EncrypzBorderGlow)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Preview Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = EncrypzEmeraldBright,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Live Decrypted Preview (In-Memory)",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = EncrypzEmeraldBright
                    )
                }

            }

            Spacer(Modifier.height(10.dp))

            // Main Visual Preview Body
            when {
                file.isPlayableMedia -> {
                    Button(onClick = onOpenFullScreen) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (file.category == VaultCategoryFilter.VIDEOS) "Play video" else "Play audio")
                    }
                }
                imageBitmap != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 280.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF090D16))
                            .clickable(onClick = onOpenFullScreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = file.cleanFileName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        if (file.category == VaultCategoryFilter.VIDEOS) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.65f))
                                    .border(1.5.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Video Preview",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }
                }

                file.category == VaultCategoryFilter.PHOTOS -> {
                    // Rich visual Passport / Photo ID preview card for sample photos
                    SamplePhotoVisualPreview(
                        file = file,
                        decryptedText = decryptedText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenFullScreen)
                    )
                }

                file.category == VaultCategoryFilter.VIDEOS -> {
                    // Rich visual Video Player preview for sample videos
                    SampleVideoVisualPreview(
                        file = file,
                        decryptedText = decryptedText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenFullScreen)
                    )
                }

                else -> {
                    // Formatted Document / PDF / Secret Note Reader
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 240.dp)
                            .clickable(onClick = onOpenFullScreen),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0B111E),
                        border = BorderStroke(1.dp, EncrypzBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(14.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (file.extension == "pdf") Icons.Default.PictureAsPdf else Icons.Default.Description,
                                    contentDescription = null,
                                    tint = if (file.extension == "pdf") Color(0xFFF87171) else EncrypzBlueBright,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = file.cleanFileName,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = EncrypzText
                                )
                            }
                            HorizontalDivider(color = EncrypzBorder)
                            Text(
                                text = decryptedText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 18.sp
                                ),
                                color = EncrypzBlueSoft
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-screen dialog viewer so the user can inspect any photo, PDF page, video preview, or document
 * in detail.
 */
@Composable
fun VaultFileFullScreenDialog(
    file: VaultFileEntry,
    viewModel: VaultViewModel,
    onDismiss: () -> Unit,
    onCopyText: (String) -> Unit
) {
    if (file.isPlayableMedia) {
        Dialog(onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                Box(Modifier.fillMaxSize()) {
                    VaultMediaPlayer(file, viewModel)
                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart)) {
                        Icon(Icons.Default.Close, contentDescription = "Close player", tint = Color.White)
                    }
                    Text(file.cleanFileName, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        return
    }
    val imageBitmap = rememberDecryptedImageBitmap(
        file = file,
        viewModel = viewModel,
        maxDimension = 2048
    )
    val decryptedText = remember(file.id) {
        viewModel.decryptFilePreview(file)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        imageBitmap != null -> {
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = file.cleanFileName,
                                modifier = Modifier
                                    .fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                        file.category == VaultCategoryFilter.PHOTOS -> {
                            SamplePhotoVisualPreview(
                                file = file,
                                decryptedText = decryptedText,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        file.category == VaultCategoryFilter.VIDEOS -> {
                            SampleVideoVisualPreview(
                                file = file,
                                decryptedText = decryptedText,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        else -> {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = RoundedCornerShape(12.dp),
                                color = EncrypzPanel,
                                border = BorderStroke(1.dp, EncrypzGlassBorder)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(18.dp)
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = decryptedText,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 22.sp
                                        ),
                                        color = EncrypzText
                                    )
                                }
                            }
                        }
                    }
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 20.dp, start = 16.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.58f)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Preview",
                            tint = Color.White
                        )
                    }
                }
                Text(
                    text = file.cleanFileName,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SamplePhotoVisualPreview(
    file: VaultFileEntry,
    decryptedText: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0B2545),
                        Color(0xFF134074),
                        Color(0xFF071324)
                    )
                )
            )
            .border(1.dp, EncrypzGlowCyan.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0A192F))
                        .border(1.dp, EncrypzGlowCyan, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Badge,
                        contentDescription = null,
                        tint = EncrypzGlowCyan,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Column {
                    Text(
                        text = file.cleanFileName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = EncrypzText
                    )
                    Text(
                        text = "High-Resolution Image · EXIF Stripped",
                        style = MaterialTheme.typography.bodySmall,
                        color = EncrypzGlowCyan
                    )
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            Text(
                text = decryptedText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp
                ),
                color = EncrypzBlueSoft
            )
        }
    }
}

@Composable
private fun SampleVideoVisualPreview(
    file: VaultFileEntry,
    decryptedText: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF1E1035),
                        Color(0xFF0F172A),
                        Color(0xFF090D16)
                    )
                )
            )
            .border(1.dp, EncrypzPurple.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(18.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                // Simulated waveform / video timeline canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = Color(0xFF3B82F6).copy(alpha = 0.18f),
                        topLeft = Offset(24f, size.height - 18f),
                        size = Size(size.width - 48f, 6f),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                    drawRoundRect(
                        color = Color(0xFF60A5FA),
                        topLeft = Offset(24f, size.height - 18f),
                        size = Size((size.width - 48f) * 0.42f, 6f),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(EncrypzPurple.copy(alpha = 0.85f))
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Video Stream",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Text(
                text = decryptedText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp
                ),
                color = EncrypzBlueSoft,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
