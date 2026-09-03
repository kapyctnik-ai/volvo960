package com.volvo960.obdctl.transport

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
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

        /**
         * A socket that is open but answers nothing is not a connection. Past
         * this, the link is torn down and the reconnect policy takes over —
         * without it a dongle driven away in the car left the app "connected"
         * for as long as the phone had battery to give.
         */
        private const val DEAD_LINK_MS = 20_000L

        /**
         * ...and once nothing has been read for this long, however many
         * sockets opened in the meantime, there is nothing to talk to: the car
         * is parked and asleep. Stop, and take the process with us.
         */
        private const val ABANDON_AFTER_SILENT_MS = 5 * 60_000L
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

    /**
     * The link currently being opened, if any.
     *
     * Opening blocks — a socket connect, or a GATT handshake with its own
     * timeouts — and cancelling the coroutine around it does not interrupt it.
     * Closing the link does. Without this handle a forced reconnect left the
     * previous attempt running, holding the radio, while a new one started
     * beside it.
     */
    @Volatile private var pendingLink: ObdLink? = null
    private var connectionJob: Job? = null
    @Volatile private var targetDevice: BluetoothDevice? = null
    @Volatile private var autoReconnect = false
    /** When the adapter last actually answered something. */
    @Volatile private var lastReplyAtMs = 0L
    @Volatile private var lowPowerRequested = false

    /**
     * Bumped every time a link is established. Anything caching per-connection
     * state watches this: a drop and a reconnect can both happen inside one
     * slow request, so watching [connectionState] for a gap is not reliable.
     */
    @Volatile var connectionGeneration = 0
        private set

    val isConnected: Boolean
        get() = connectionState.value is ConnectionState.Connected

    fun connect(device: BluetoothDevice) {
        targetDevice = device
        autoReconnect = true
        connectionJob?.cancel()
        connectionJob = scope.launch { connectLoop(device) }
    }

    /**
     * Throws everything away and starts again, now.
     *
     * This is what the connect button does. Whatever the transport was in the
     * middle of — a stalled handshake, a three-minute wait between attempts,
     * having given up for good — none of it survives: the attempt in flight is
     * closed out from under itself, the retry count returns to zero, and a
     * fresh connection starts immediately. Nothing from before is consulted,
     * because the reason the button was pressed is that what came before was
     * wrong.
     */
    fun forceReconnect(device: BluetoothDevice) {
        targetDevice = device
        autoReconnect = true
        connectionJob?.cancel()
        closeQuietly()
        _connectionState.value = ConnectionState.Connecting
        lastReplyAtMs = SystemClock.elapsedRealtime()
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

    /**
     * Asks for a cheaper link while the dashboard is not being watched. Only
     * BLE can act on it; on Classic it is a no-op.
     */
    fun setLowPower(lowPower: Boolean) {
        lowPowerRequested = lowPower
        link?.setLowPower(lowPower)
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
        if (result is CommandResult.Error) {
            if (result.fatal && dropOnFailure) {
                onTransportFailure(result.message)
            } else if (link?.isBroken == true) {
                // The radio already told us the peer is gone; no point sitting
                // out the silence timer for something already known.
                onTransportFailure("соединение разорвано")
            } else {
                // Callers that must not drop the link on one refused request
                // (polling does exactly that) still need the link to die when
                // it stops answering altogether.
                checkDeadLink()
            }
        }
        return result
    }

    /**
     * Drops a link that has gone quiet, and gives up entirely once the silence
     * has outlasted any plausible reconnection. Both timers are here rather
     * than in the caller so every path through the transport is covered.
     */
    private suspend fun checkDeadLink() {
        if (connectionState.value !is ConnectionState.Connected) return
        val silentFor = SystemClock.elapsedRealtime() - lastReplyAtMs
        if (silentFor < DEAD_LINK_MS) return
        if (silentFor >= ABANDON_AFTER_SILENT_MS) {
            abandon("нет данных ${silentFor / 60_000} мин")
        } else {
            onTransportFailure("адаптер молчит ${silentFor / 1000} с")
        }
    }

    /**
     * Tears the link down and reconnects, which puts the adapter through ATZ
     * and the bus through its initialisation again.
     *
     * The way back when the adapter is answering but the car is not: an ELM327
     * can hold a session the car dropped long ago, and only a reset clears it.
     * Until now that took restarting the app by hand.
     */
    suspend fun resetLink(reason: String) {
        if (connectionState.value !is ConnectionState.Connected) return
        logger.logError("перезапуск адаптера: $reason")
        onTransportFailure(reason)
    }

    /**
     * Stops for good: no more attempts, and the service takes the app down.
     * Public because the adapter answering "NO DATA" forever — a powered
     * dongle in a parked car — is a link the transport itself cannot tell from
     * a working one. Only the poller knows no reading has arrived.
     */
    fun abandon(reason: String) {
        autoReconnect = false
        connectionJob?.cancel()
        closeQuietly()
        _connectionState.value = ConnectionState.GaveUp(reason)
        logger.logError("прекращаю: $reason")
        onGaveUp?.invoke()
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
            pendingLink = candidate
            try {
                withContext(Dispatchers.IO) { candidate.open() }
                pendingLink = null
                link = candidate
                candidate.setLowPower(lowPowerRequested)
                lastReplyAtMs = SystemClock.elapsedRealtime()
                initAdapter()
                connectionGeneration++
                val name = try { device.name } catch (e: SecurityException) { null } ?: device.address
                _connectionState.value = ConnectionState.Connected("$name · ${candidate.label}", device.address)
                return null
            } catch (e: SecurityException) {
                pendingLink = null
                candidate.close()
                autoReconnect = false
                _connectionState.value = ConnectionState.Failed("нет разрешения Bluetooth")
                return "нет разрешения Bluetooth"
            } catch (e: IOException) {
                pendingLink = null
                candidate.close()
                lastError = e.message ?: "не удалось подключиться"
                logger.logError("${candidate.label}: $lastError")
            }
        }
        link = null
        return lastError ?: "не удалось подключиться"
    }

    /**
     * Which radio to try. A dual-mode dongle advertises both and the type does
     * not say which one carries the ELM327, so both are tried — LE first.
     *
     * LE first because it is the better link for this job: the connection
     * interval can be dropped when nobody is watching, a lost peer is reported
     * as an event instead of having to be inferred from silence, and packets
     * can go out on the 2 Mbit PHY. Classic remains the fallback for dongles
     * whose LE side is advertised but not wired to the ELM327.
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
            else -> listOf(BleLink(context, device, logger), SppLink(device, logger))
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
            // Anything that came back at all proves the adapter is there; a
            // refusal from the car still means the link works.
            lastReplyAtMs = SystemClock.elapsedRealtime()
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
        if (SystemClock.elapsedRealtime() - lastReplyAtMs >= ABANDON_AFTER_SILENT_MS) {
            abandon(reason)
            return
        }
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
        // Closing an attempt that is still opening is what unblocks it: both a
        // socket connect and a GATT handshake abort when their object closes,
        // and nothing else will interrupt them.
        try { pendingLink?.close() } catch (_: Exception) { }
        pendingLink = null
    }
}
