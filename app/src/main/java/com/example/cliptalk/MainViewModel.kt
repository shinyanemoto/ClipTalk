package com.example.cliptalk

import android.app.Application
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cliptalk.data.Clip
import com.example.cliptalk.data.SessionClipStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

private const val MIN_CLIP_DURATION_MS = 300L

data class MainUiState(
    val sessionTitle: String = "Sessionを準備中...",
    val isRecording: Boolean = false,
    val clips: List<Clip> = emptyList(),
    val playingClipId: String? = null,
    val message: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val store = SessionClipStore(appContext)
    private val clipsDir = File(appContext.filesDir, "clips")
    private val recordingMutex = Mutex()

    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var recordingStartElapsedMs: Long = 0L

    private var mediaPlayer: MediaPlayer? = null

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        clipsDir.mkdirs()

        viewModelScope.launch(Dispatchers.IO) {
            store.initialize()
        }

        viewModelScope.launch {
            store.currentSession.collect { session ->
                _uiState.update {
                    it.copy(sessionTitle = session?.title ?: "Sessionを準備中...")
                }
            }
        }

        viewModelScope.launch {
            store.clips.collect { clips ->
                _uiState.update { it.copy(clips = clips) }
            }
        }
    }

    suspend fun startRecording(): Boolean {
        return recordingMutex.withLock {
            if (mediaRecorder != null) {
                return@withLock true
            }

            withContext(Dispatchers.IO) {
                startRecordingLocked()
            }
        }
    }

    suspend fun stopRecording() {
        val stopResult = recordingMutex.withLock {
            val recorder = mediaRecorder ?: return@withLock null
            val file = currentRecordingFile

            val durationMs = (SystemClock.elapsedRealtime() - recordingStartElapsedMs)
                .coerceAtLeast(0L)

            var shouldKeep = durationMs >= MIN_CLIP_DURATION_MS
            try {
                recorder.stop()
            } catch (_: RuntimeException) {
                shouldKeep = false
            } finally {
                recorder.release()
                mediaRecorder = null
                currentRecordingFile = null
                recordingStartElapsedMs = 0L
                _uiState.update { it.copy(isRecording = false) }
            }

            StopResult(file = file, durationMs = durationMs, shouldKeep = shouldKeep)
        } ?: return

        val outputFile = stopResult.file ?: return
        if (!stopResult.shouldKeep) {
            withContext(Dispatchers.IO) {
                outputFile.delete()
            }
            _uiState.update {
                it.copy(message = "${MIN_CLIP_DURATION_MS}ms未満の録音は破棄しました")
            }
            return
        }

        withContext(Dispatchers.IO) {
            store.appendClip(
                filePath = outputFile.absolutePath,
                durationMs = stopResult.durationMs
            )
        }

        _uiState.update { it.copy(message = "クリップを保存しました") }
    }

    fun stopRecordingSafely() {
        viewModelScope.launch {
            stopRecording()
        }
    }

    fun onAppBackgrounded() {
        stopPlayback()
        stopRecordingSafely()
    }

    fun togglePlayback(clip: Clip) {
        if (_uiState.value.playingClipId == clip.id) {
            stopPlayback()
            return
        }

        stopPlayback()

        val player = MediaPlayer()
        try {
            player.setDataSource(clip.filePath)
            player.setOnCompletionListener {
                stopPlayback()
            }
            player.prepare()
            player.start()
            mediaPlayer = player
            _uiState.update { it.copy(playingClipId = clip.id, message = null) }
        } catch (_: IOException) {
            player.release()
            _uiState.update { it.copy(message = "再生に失敗しました") }
        } catch (_: IllegalStateException) {
            player.release()
            _uiState.update { it.copy(message = "再生に失敗しました") }
        }
    }

    fun stopPlayback() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (_: IllegalStateException) {
                // no-op
            }
            player.release()
        }
        mediaPlayer = null
        _uiState.update { it.copy(playingClipId = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
        mediaRecorder?.let { recorder ->
            try {
                recorder.stop()
            } catch (_: RuntimeException) {
                // no-op
            }
            recorder.release()
        }
        mediaRecorder = null
        currentRecordingFile?.delete()
        currentRecordingFile = null
    }

    private suspend fun startRecordingLocked(): Boolean {
        store.ensureCurrentSession()

        val outputFile = createOutputFile()
        var recorder: MediaRecorder? = null
        return try {
            recorder = createMediaRecorder(outputFile)
            recorder.prepare()
            recorder.start()

            mediaRecorder = recorder
            currentRecordingFile = outputFile
            recordingStartElapsedMs = SystemClock.elapsedRealtime()
            _uiState.update { it.copy(isRecording = true, message = "録音中") }
            true
        } catch (_: IOException) {
            recorder?.release()
            outputFile.delete()
            _uiState.update { it.copy(message = "録音の開始に失敗しました") }
            false
        } catch (_: IllegalStateException) {
            recorder?.release()
            outputFile.delete()
            _uiState.update { it.copy(message = "録音の開始に失敗しました") }
            false
        } catch (_: SecurityException) {
            recorder?.release()
            outputFile.delete()
            _uiState.update { it.copy(message = "マイク権限が必要です") }
            false
        } catch (_: RuntimeException) {
            recorder?.release()
            outputFile.delete()
            _uiState.update { it.copy(message = "録音の開始に失敗しました") }
            false
        }
    }

    private fun createOutputFile(): File {
        val fileName = "clip_${System.currentTimeMillis()}_${UUID.randomUUID()}.m4a"
        return File(clipsDir, fileName)
    }

    private fun createMediaRecorder(outputFile: File): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioEncodingBitRate(128_000)
        recorder.setAudioSamplingRate(44_100)
        recorder.setOutputFile(outputFile.absolutePath)

        return recorder
    }

    private data class StopResult(
        val file: File?,
        val durationMs: Long,
        val shouldKeep: Boolean
    )
}
