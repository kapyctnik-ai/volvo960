package com.volvo960.obdctl.transport

import android.content.Context
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
    private val lock = Any()

    fun logSent(command: String) = append("TX", command)

    fun logReceived(response: String) = append("RX", response.replace("\n", "\\n").replace("\r", "\\r"))

    fun logError(message: String) = append("ERR", message)

    private fun append(tag: String, text: String) {
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
        synchronized(lock) {
            try { logFile.writeText("") } catch (_: Exception) { }
        }
    }
}
