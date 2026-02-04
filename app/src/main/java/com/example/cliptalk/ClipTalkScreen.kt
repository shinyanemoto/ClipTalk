package com.example.cliptalk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cliptalk.data.Clip
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ClipTalkScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasRecordPermission by remember {
        mutableStateOf(context.hasRecordAudioPermission())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordPermission = granted
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasRecordPermission = context.hasRecordAudioPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "ClipTalk MVP") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = uiState.sessionTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "クリップ数: ${uiState.clips.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            PressHoldRecordButton(
                isRecording = uiState.isRecording,
                hasRecordPermission = hasRecordPermission,
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStartRecording = {
                    viewModel.startRecording()
                },
                onStopRecording = {
                    viewModel.stopRecording()
                }
            )

            if (!hasRecordPermission) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "録音するにはマイク権限が必要です",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            uiState.message?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            if (uiState.clips.isEmpty()) {
                Text(
                    text = "まだクリップがありません。ボタンを長押しして録音してください。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(
                        items = uiState.clips,
                        key = { clip -> clip.id }
                    ) { clip ->
                        val isPlaying = uiState.playingClipId == clip.id
                        ClipRow(
                            clip = clip,
                            isPlaying = isPlaying,
                            onClick = {
                                viewModel.togglePlayback(clip)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PressHoldRecordButton(
    isRecording: Boolean,
    hasRecordPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStartRecording: suspend () -> Boolean,
    onStopRecording: suspend () -> Unit
) {
    val buttonColor = if (isRecording) Color(0xFFD32F2F) else Color(0xFF1976D2)
    val text = if (isRecording) "録音中\n離すと保存" else "押して\n録音"

    Box(
        modifier = Modifier
            .size(140.dp)
            .background(buttonColor, CircleShape)
            .pointerInput(isRecording, hasRecordPermission) {
                detectTapGestures(
                    onPress = {
                        if (!hasRecordPermission) {
                            onRequestPermission()
                            return@detectTapGestures
                        }

                        val started = onStartRecording()
                        if (!started) {
                            return@detectTapGestures
                        }

                        try {
                            tryAwaitRelease()
                        } finally {
                            onStopRecording()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ClipRow(
    clip: Clip,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "停止" else "再生"
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatClipDate(clip.createdAt),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${formatDuration(clip.durationMs)} ・ tapで${if (isPlaying) "停止" else "再生"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatClipDate(timestampMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    return formatter.format(
        Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault())
    )
}

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    val millis = (durationMs % 1000) / 100
    val minutes = seconds / 60
    val remSeconds = seconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d.%01d", minutes, remSeconds, millis)
}

private fun Context.hasRecordAudioPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}
