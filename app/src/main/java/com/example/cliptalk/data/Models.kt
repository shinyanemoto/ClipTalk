package com.example.cliptalk.data

data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class Clip(
    val id: String,
    val sessionId: String,
    val filePath: String,
    val durationMs: Long,
    val createdAt: Long
)
