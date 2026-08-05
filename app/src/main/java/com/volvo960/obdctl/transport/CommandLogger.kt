package com.volvo960.obdctl.transport

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Appends every request/response pair sent over the transport to a plain text
 * file under external files dir, so raw-console sessions used to hunt for new
 * actuator commands can be reviewed later outside the app.
 */
class CommandLogger(context: Context) {

    private val logFile: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "obd_command_log.txt"
    )
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    private val _recent = MutableStateFlow<List<String>>(emptyList())

    /**
     * The tail of the traffic, kept in memory so it can be put on screen. When
     * a reading doesn't appear there is no way to tell a request that was never
     * sent from one the car ignored without seeing the actual exchange.
     */
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    fun logSent(command: String) = append("TX", command)

    fun logReceived(response: String) = append("RX", response.replace("\n", "\\n").replace("\r", "\\r"))

    fun logError(message: String) = append("ERR", message)

    private fun append(tag: String, text: String) {
        _recent.update { (it + "${clockFormat.format(Date())} $tag $text").takeLast(RECENT_LINES) }
        synchronized(lock) {
            try {
                FileWriter(logFile, true).use { writer ->
                    writer.appendLine("${dateFormat.format(Date())} [$tag] $text")
                }
            } catch (_: Exception) {
                // Logging must never crash the transport; drop on failure.
            }
        }
    }

    fun logFilePath(): String = logFile.absolutePath

    fun file(): File = logFile

    fun clear() {
        _recent.value = emptyList()
        synchronized(lock) {
            try { logFile.writeText("") } catch (_: Exception) { }
        }
    }

    private companion object {
        const val RECENT_LINES = 40
    }
}
