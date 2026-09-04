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
 * Keeps the traffic log: the last few lines in memory for the screen, and a
 * file for sharing out of the app.
 *
 * The file is written in batches from a single background thread, never from
 * the transport: opening a FileWriter per line, ten times a second, on the
 * thread that talks to the adapter was I/O on the hot path for nothing. And it
 * is capped — half of it is dropped when it reaches the limit — because at a
 * kilobyte a second it was growing by megabytes an hour, for ever.
 */
class CommandLogger(context: Context) {

    private val logFile: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "obd_command_log.txt"
    )
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private val pending = java.util.concurrent.LinkedBlockingQueue<String>()

    private val writer = Thread({
        val batch = ArrayList<String>()
        while (true) {
            try {
                batch.add(pending.take())
                pending.drainTo(batch)
                synchronized(lock) {
                    if (logFile.length() > MAX_FILE_BYTES) trim()
                    FileWriter(logFile, true).use { out -> batch.forEach { out.appendLine(it) } }
                }
            } catch (_: InterruptedException) {
                return@Thread
            } catch (_: Exception) {
                // Logging must never crash anything; drop the batch.
            }
            batch.clear()
        }
    }, "obd-log").apply {
        isDaemon = true
        start()
    }

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
        val now = Date()
        _recent.update { (it + "${clockFormat.format(now)} $tag $text").takeLast(RECENT_LINES) }
        pending.offer("${dateFormat.format(now)} [$tag] $text")
    }

    /** Keeps the newer half of the file. Caller holds [lock]. */
    private fun trim() {
        val lines = logFile.readLines()
        logFile.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
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
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}
