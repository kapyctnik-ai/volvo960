package com.volvo960.obdctl.transport

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Half-duplex transport to an ELM327 dongle, over Bluetooth Classic or LE.
 *
 * Every command that ever reaches the adapter — the init sequence, console
 * input, dashboard polling — funnels through [sendRaw], which is serialized by
 * [mutex]. This is the single queue the rest of the app shares; nothing above
 * this layer touches the radio directly.
 *
 * Reconnection is deliberately stingy. A dongle left plugged into a parked car
 * is unreachable for hours, and retrying at a few seconds' interval for that
 * long is what flattens the phone battery. So: a handful of attempts, minutes
 * apart, then [ConnectionState.GaveUp] and the app stops running.
 */
class Elm327Transport(
    private val context: Context,
    private val logger: CommandLogger,
) {

    companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MS = 4_000L

        /**
         * One quick retry (the adapter often just needs the socket to settle),
         * then three at three-minute spacing. Five attempts, ~9 minutes, done.
         */
        private val RECONNECT_DELAYS_MS = longArrayOf(5_000, 180_000, 180_000, 180_000)
        private const val MAX_ATTEMPTS = 5
    }

    sealed class CommandResult {
        data class Success(val response: String) : CommandResult()

        /**
         * [fatal] separates "the link is broken" from "the car declined this
         * one request". Polling a PID the ECU doesn't implement answers
         * NO DATA, and tearing down a healthy connection over that would make
         * probing for supported readings impossible.
         */
        data class Error(val message: String, val fatal: Boolean = true) : CommandResult()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Called once every retry is spent, so the service can shut the app down. */
    @Volatile var onGaveUp: (() -> Unit)? = null

    /**
     * "auto", "spp" or "ble" — see [com.volvo960.obdctl.prefs.AppPrefs.transportPreference].
     * Set before [connect]; changing it takes effect on the next attempt.
     */
    @Volatile var transportPreference: String = "auto"

    /**
     * ELM327 protocol number sent as `ATSP` during init. Default 3 (ISO 9141-2)
     * — the 960's wiring — but the self-test can find another one and set it.
     */
    @Volatile var protocol: String = "3"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile private var link: ObdLink? = null
    private var connectionJob: Job? = null
    @Volatile private var targetDevice: BluetoothDevice? = null
    @Volatile private var autoReconnect = false

    val isConnected: Boolean
        get() = connectionState.value is ConnectionState.Connected

    fun connect(device: BluetoothDevice) {
        targetDevice = device
        autoReconnect = true
        connectionJob?.cancel()
        connectionJob = scope.launch { connectLoop(device) }
    }

    fun disconnect() {
        autoReconnect = false
        connectionJob?.cancel()
        scope.launch {
            closeQuietly()
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /** Fully tears down the transport; call from service onDestroy. */
    fun release() {
        autoReconnect = false
        closeQuietly()
        scope.cancel()
    }

    suspend fun sendRaw(
        command: String,
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        dropOnFailure: Boolean = true,
    ): CommandResult {
        if (connectionState.value !is ConnectionState.Connected) {
            return CommandResult.Error("нет соединения")
        }
        val result = mutex.withLock { rawExchange(command, timeoutMs) }
        if (result is CommandResult.Error && result.fatal && dropOnFailure) {
            onTransportFailure(result.message)
        }
        return result
    }

    /**
     * Sends several commands back to back while holding the queue lock for the
     * whole run, so nothing can interleave in the middle. Stops at the first
     * failure and returns the responses collected so far.
     */
    suspend fun sendSequence(
        commands: List<String>,
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        interCommandDelayMs: Long = 80L,
    ): CommandResult {
        if (commands.isEmpty()) return CommandResult.Success("")
        if (connectionState.value !is ConnectionState.Connected) {
            return CommandResult.Error("нет соединения")
        }
        val responses = StringBuilder()
        val result = mutex.withLock {
            var last: CommandResult = CommandResult.Success("")
            for ((index, command) in commands.withIndex()) {
                last = rawExchange(command, timeoutMs)
                when (val current = last) {
                    is CommandResult.Success -> {
                        if (responses.isNotEmpty()) responses.append('\n')
                        responses.append(current.response)
                    }
                    is CommandResult.Error -> return@withLock current
                }
                if (index != commands.lastIndex) delay(interCommandDelayMs)
            }
            CommandResult.Success(responses.toString())
        }
        if (result is CommandResult.Error && result.fatal) {
            onTransportFailure(result.message)
        }
        return result
    }

    /**
     * Runs a whole script under one lock and reports each exchange separately,
     * so a hand-driven probe can be read command by command. Never drops the
     * link: probing is expected to include commands the car refuses.
     */
    suspend fun sendScriptVerbose(
        commands: List<String>,
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        interCommandDelayMs: Long = 90L,
    ): List<Pair<String, String>> {
        if (commands.isEmpty()) return emptyList()
        if (connectionState.value !is ConnectionState.Connected) {
            return listOf("" to "нет соединения")
        }
        return mutex.withLock {
            val transcript = mutableListOf<Pair<String, String>>()
            for ((index, command) in commands.withIndex()) {
                val reply = when (val r = rawExchange(command, timeoutMs)) {
                    is CommandResult.Success -> r.response.ifBlank { "(пусто)" }
                    is CommandResult.Error -> "ОШИБКА: ${r.message}"
                }
                transcript += command to reply
                if (index != commands.lastIndex) delay(interCommandDelayMs)
            }
            transcript
        }
    }

    private suspend fun connectLoop(device: BluetoothDevice) {
        var attempt = 0
        var lastReason = "нет связи с адаптером"
        while (autoReconnect && attempt < MAX_ATTEMPTS) {
            _connectionState.value = ConnectionState.Connecting
            val failure = tryConnectOnce(device)
            if (failure == null) return
            lastReason = failure
            attempt++
            if (attempt >= MAX_ATTEMPTS) break
            if (!autoReconnect) return
            val delayMs = RECONNECT_DELAYS_MS[minOf(attempt - 1, RECONNECT_DELAYS_MS.size - 1)]
            _connectionState.value = ConnectionState.Failed(
                "$failure — попытка ${attempt + 1}/$MAX_ATTEMPTS через ${delayMs / 1000} с"
            )
            delay(delayMs)
        }
        if (!autoReconnect) return
        autoReconnect = false
        _connectionState.value = ConnectionState.GaveUp(lastReason)
        logger.logError("сдался после $MAX_ATTEMPTS попыток: $lastReason")
        onGaveUp?.invoke()
    }

    /** Returns null on success, or the reason it failed. */
    private suspend fun tryConnectOnce(device: BluetoothDevice): String? {
        var lastError: String? = null
        for (candidate in candidateLinks(device)) {
            closeQuietly()
            try {
                withContext(Dispatchers.IO) { candidate.open() }
                link = candidate
                initAdapter()
                val name = try { device.name } catch (e: SecurityException) { null } ?: device.address
                _connectionState.value = ConnectionState.Connected("$name · ${candidate.label}", device.address)
                return null
            } catch (e: SecurityException) {
                candidate.close()
                autoReconnect = false
                _connectionState.value = ConnectionState.Failed("нет разрешения Bluetooth")
                return "нет разрешения Bluetooth"
            } catch (e: IOException) {
                candidate.close()
                lastError = e.message ?: "не удалось подключиться"
                logger.logError("${candidate.label}: $lastError")
            }
        }
        link = null
        return lastError ?: "не удалось подключиться"
    }

    /**
     * Which radio to try. A dual-mode dongle advertises both, and which one
     * actually carries the ELM327 is not something the type tells you — so
     * both get tried, classic first because it is cheaper when it works.
     */
    private fun candidateLinks(device: BluetoothDevice): List<ObdLink> {
        when (transportPreference) {
            "spp" -> return listOf(SppLink(device, logger))
            "ble" -> return listOf(BleLink(context, device, logger))
        }
        val type = try { device.type } catch (e: SecurityException) { BluetoothDevice.DEVICE_TYPE_UNKNOWN }
        return when (type) {
            BluetoothDevice.DEVICE_TYPE_LE -> listOf(BleLink(context, device, logger))
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> listOf(SppLink(device, logger))
            else -> listOf(SppLink(device, logger), BleLink(context, device, logger))
        }
    }

    /**
     * Best-effort adapter reset. Individual AT command failures are logged but
     * never fail the connection: dongles are inconsistent about ATL0/ATH0 and
     * the link is still usable for real traffic even if one of these hiccups.
     */
    private suspend fun initAdapter() {
        val initSequence = listOf(
            "ATZ" to 6_000L,
            "ATE0" to 3_000L,
            "ATL0" to 3_000L,
            "ATH0" to 3_000L,
            "ATSP$protocol" to 3_000L, // 3 = ISO 9141-2, the 960's OBD wiring
        )
        for ((cmd, timeout) in initSequence) {
            mutex.withLock { rawExchange(cmd, timeout) }
            delay(150)
        }
    }

    /** Caller must hold [mutex]. Never mutates [_connectionState]. */
    private suspend fun rawExchange(command: String, timeoutMs: Long): CommandResult {
        val current = link ?: return CommandResult.Error("нет соединения")
        logger.logSent(command)
        return try {
            withContext(Dispatchers.IO) {
                current.write((command.trim() + "\r").toByteArray(Charsets.US_ASCII))
            }
            val raw = withContext(Dispatchers.IO) { current.readUntilPrompt(timeoutMs) }
            val cleaned = cleanResponse(raw)
            logger.logReceived(cleaned)
            val failure = adapterErrorIn(cleaned)
            // The raw reply travels with the complaint: naming only the marker
            // that matched hides what the adapter actually said, which is the
            // one thing needed to work out why.
            failure?.copy(message = "$command -> ${failure.message} | ответ: ${cleaned.replace("\n", " / ")}")
                ?: CommandResult.Success(cleaned)
        } catch (e: IOException) {
            logger.logError(e.message ?: "io error")
            CommandResult.Error(e.message ?: "ошибка ввода-вывода")
        }
    }

    /**
     * Getting bytes back before the prompt is not the same as the command
     * having worked: the adapter answers its own failures in-band.
     */
    private fun adapterErrorIn(response: String): CommandResult.Error? {
        val upper = response.uppercase()
        val fatal = listOf(
            "UNABLE TO CONNECT", "BUS INIT: ERROR", "BUS ERROR", "BUS BUSY",
            "CAN ERROR", "FB ERROR", "LP ALERT", "LV RESET", "BUFFER FULL", "STOPPED",
        )
        fatal.firstOrNull { upper.contains(it) }?.let { return CommandResult.Error(it, fatal = true) }
        // The car simply had nothing to say for this request; the link is fine.
        val declined = listOf("NO DATA", "DATA ERROR")
        declined.firstOrNull { upper.contains(it) }?.let { return CommandResult.Error(it, fatal = false) }
        // A bare "?" is how the adapter reports a command it didn't understand.
        if (upper.split("\n").any { it.trim() == "?" }) {
            return CommandResult.Error("не понял команду (?)", fatal = false)
        }
        return null
    }

    private fun cleanResponse(raw: String): String =
        raw.replace("\r", "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "SEARCHING..." }
            .joinToString("\n")

    private suspend fun onTransportFailure(reason: String) {
        if (_connectionState.value !is ConnectionState.Connected) return
        _connectionState.value = ConnectionState.Failed(reason)
        closeQuietly()
        if (autoReconnect) {
            val device = targetDevice
            if (device != null) {
                connectionJob = scope.launch { connectLoop(device) }
            }
        }
    }

    private fun closeQuietly() {
        try { link?.close() } catch (_: Exception) { }
        link = null
    }
}
