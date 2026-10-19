package com.example.encrypz.ui.vault

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

internal val VaultFileEntry.isPlayableMedia: Boolean
    get() = category == VaultCategoryFilter.VIDEOS || extension in setOf(
        "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr", "3ga"
    )

/** The decoder reads decrypted bytes directly; no plaintext media file is written to disk. */
private class VaultMediaSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun getSize() = bytes.size.toLong()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0) return -1
        if (size == 0) return 0
        if (position >= bytes.size) return -1
        val count = minOf(size, bytes.size - position.toInt())
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }
    override fun close() { bytes.fill(0) }
}

@Composable
internal fun VaultMediaPlayer(file: VaultFileEntry, viewModel: VaultViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(file.id) { MediaPlayer() }
    var ready by remember(file.id) { mutableStateOf(false) }
    var playing by remember(file.id) { mutableStateOf(false) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }
    var position by remember(file.id) { mutableStateOf(0) }
    var duration by remember(file.id) { mutableStateOf(0) }
    var aspect by remember(file.id) { mutableStateOf(16f / 9f) }

    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && ready) {
                player.pause()
                playing = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    LaunchedEffect(player) {
        val bytes = withContext(Dispatchers.Default) { viewModel.decryptFileBytes(file) }
        if (bytes == null) {
            error = "Unable to decrypt this media file."
            return@LaunchedEffect
        }
        try {
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            player.setOnPreparedListener {
                duration = it.duration.coerceAtLeast(0)
                ready = true
            }
            player.setOnVideoSizeChangedListener { _, width, height ->
                if (width > 0 && height > 0) aspect = width.toFloat() / height
            }
            player.setOnCompletionListener { playing = false; position = duration }
            player.setOnErrorListener { _, _, _ ->
                playing = false
                ready = false
                error = "This media format cannot be played on this device, or the file is damaged."
                true
            }
            player.setDataSource(VaultMediaSource(bytes))
            player.prepareAsync()
        } catch (_: Exception) {
            bytes.fill(0)
            error = "Unable to open this media file."
        }
    }
    LaunchedEffect(player, playing) {
        while (playing) {
            position = player.currentPosition.coerceAtLeast(0)
            delay(250)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 72.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (file.category == VaultCategoryFilter.VIDEOS) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) { player.setDisplay(holder) }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                            override fun surfaceDestroyed(holder: SurfaceHolder) { player.setDisplay(null) }
                        })
                    }
                }
            )
        } else {
            Text("Audio", color = androidx.compose.ui.graphics.Color.White)
        }
        if (error != null) {
            Text(error!!, color = androidx.compose.ui.graphics.Color.White)
        } else if (!ready) {
            CircularProgressIndicator()
            Text("Preparing media…", color = androidx.compose.ui.graphics.Color.White)
        } else {
            Slider(
                value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                onValueChange = { position = it.toInt(); player.seekTo(position) },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat()
            )
            Text("${mediaTime(position)} / ${mediaTime(duration)}", color = androidx.compose.ui.graphics.Color.White)
            Button(onClick = {
                if (playing) player.pause() else {
                    if (position >= duration) { player.seekTo(0); position = 0 }
                    player.start()
                }
                playing = !playing
            }) { Text(if (playing) "Pause" else "Play") }
        }
    }
}

private fun mediaTime(millis: Int): String =
    String.format(Locale.ROOT, "%d:%02d", millis / 60000, millis / 1000 % 60)
