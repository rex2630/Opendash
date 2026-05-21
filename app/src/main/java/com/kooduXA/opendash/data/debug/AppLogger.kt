package com.kooduXA.opendash.data.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    enum class Level {
        DEBUG, INFO, WARN, ERROR
    }

    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: String? = null
    ) {
        fun formattedTime(): String {
            return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
        }

        fun asLine(): String {
            val base = "${formattedTime()} ${level.name.padEnd(5)} [$tag] $message"
            return if (throwable.isNullOrBlank()) base else "$base\n$throwable"
        }
    }

    private const val MAX_LOGS = 500

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append(Level.DEBUG, tag, message, null)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append(Level.INFO, tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        append(Level.WARN, tag, message, throwable?.stackTraceToString())
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        append(Level.ERROR, tag, message, throwable?.stackTraceToString())
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun exportText(): String {
        return _logs.value.joinToString("\n\n") { it.asLine() }
    }

    private fun append(
        level: Level,
        tag: String,
        message: String,
        throwable: String?
    ) {
        val next = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable
        )

        val updated = (_logs.value + next).takeLast(MAX_LOGS)
        _logs.value = updated
    }
}
