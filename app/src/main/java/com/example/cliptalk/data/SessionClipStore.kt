package com.example.cliptalk.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class SessionClipStore(
    private val appContext: Context
) {
    private val metadataFile = File(appContext.filesDir, "metadata/session_clips.json")
    private val stateMutex = Mutex()

    private val _currentSession = MutableStateFlow<Session?>(null)
    val currentSession: StateFlow<Session?> = _currentSession.asStateFlow()

    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()

    suspend fun initialize() {
        stateMutex.withLock {
            val loaded = loadStateLocked()
            if (loaded == null) {
                val now = System.currentTimeMillis()
                val session = createDefaultSession(now)
                _currentSession.value = session
                _clips.value = emptyList()
                persistStateLocked(session, emptyList())
            } else {
                _currentSession.value = loaded.session
                _clips.value = loaded.clips.sortedByDescending { it.createdAt }
            }
        }
    }

    suspend fun ensureCurrentSession(): Session {
        return stateMutex.withLock {
            resolveCurrentSessionLocked()
        }
    }

    suspend fun appendClip(
        filePath: String,
        durationMs: Long,
        createdAt: Long = System.currentTimeMillis()
    ): Clip {
        return stateMutex.withLock {
            val session = resolveCurrentSessionLocked()
            val clip = Clip(
                id = UUID.randomUUID().toString(),
                sessionId = session.id,
                filePath = filePath,
                durationMs = durationMs,
                createdAt = createdAt
            )
            val updatedSession = session.copy(updatedAt = createdAt)
            val updatedClips = (_clips.value + clip).sortedByDescending { it.createdAt }

            _currentSession.value = updatedSession
            _clips.value = updatedClips
            persistStateLocked(updatedSession, updatedClips)
            clip
        }
    }

    private fun createDefaultSession(now: Long): Session {
        return Session(
            id = UUID.randomUUID().toString(),
            title = "Session ${formatDateTime(now)}",
            createdAt = now,
            updatedAt = now
        )
    }

    private fun formatDateTime(timestampMs: Long): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.getDefault())
        val dateTime = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault())
        return formatter.format(dateTime)
    }

    private fun loadStateLocked(): PersistedState? {
        if (!metadataFile.exists()) {
            return null
        }
        val raw = metadataFile.readText()
        if (raw.isBlank()) {
            return null
        }
        val root = JSONObject(raw)
        val sessionJson = root.optJSONObject("session") ?: return null

        val fallbackNow = System.currentTimeMillis()
        val session = Session(
            id = sessionJson.optString("id", UUID.randomUUID().toString()),
            title = sessionJson.optString("title", "Session ${formatDateTime(fallbackNow)}"),
            createdAt = sessionJson.optLong("createdAt", fallbackNow),
            updatedAt = sessionJson.optLong("updatedAt", fallbackNow)
        )

        val clipsArray = root.optJSONArray("clips") ?: JSONArray()
        val loadedClips = mutableListOf<Clip>()
        for (index in 0 until clipsArray.length()) {
            val clipJson = clipsArray.optJSONObject(index) ?: continue
            val id = clipJson.optString("id")
            val sessionId = clipJson.optString("sessionId")
            val filePath = clipJson.optString("filePath")
            if (id.isBlank() || sessionId.isBlank() || filePath.isBlank()) {
                continue
            }
            if (!File(filePath).exists()) {
                continue
            }
            loadedClips += Clip(
                id = id,
                sessionId = sessionId,
                filePath = filePath,
                durationMs = clipJson.optLong("durationMs", 0L),
                createdAt = clipJson.optLong("createdAt", fallbackNow)
            )
        }

        return PersistedState(
            session = session,
            clips = loadedClips
        )
    }

    private fun persistStateLocked(session: Session, clips: List<Clip>) {
        metadataFile.parentFile?.mkdirs()

        val sessionJson = JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("createdAt", session.createdAt)
            put("updatedAt", session.updatedAt)
        }

        val clipsJson = JSONArray().apply {
            clips.forEach { clip ->
                put(
                    JSONObject().apply {
                        put("id", clip.id)
                        put("sessionId", clip.sessionId)
                        put("filePath", clip.filePath)
                        put("durationMs", clip.durationMs)
                        put("createdAt", clip.createdAt)
                    }
                )
            }
        }

        val root = JSONObject().apply {
            put("session", sessionJson)
            put("clips", clipsJson)
        }

        metadataFile.writeText(root.toString())
    }

    private fun resolveCurrentSessionLocked(): Session {
        _currentSession.value?.let { return it }

        val loaded = loadStateLocked()
        if (loaded != null) {
            _currentSession.value = loaded.session
            _clips.value = loaded.clips.sortedByDescending { it.createdAt }
            return loaded.session
        }

        val now = System.currentTimeMillis()
        val session = createDefaultSession(now)
        _currentSession.value = session
        _clips.value = emptyList()
        persistStateLocked(session, emptyList())
        return session
    }

    private data class PersistedState(
        val session: Session,
        val clips: List<Clip>
    )
}
