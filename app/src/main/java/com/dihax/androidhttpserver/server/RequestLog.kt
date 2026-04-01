package com.dihax.androidhttpserver.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val method: String,
    val path: String,
    val status: Int,
    val userAgent: String = "",
)

object RequestLog {
    private const val MAX_ENTRIES = 100

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun add(entry: LogEntry) {
        val current = _entries.value.toMutableList()
        current.add(0, entry)
        if (current.size > MAX_ENTRIES) current.removeLast()
        _entries.value = current
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
