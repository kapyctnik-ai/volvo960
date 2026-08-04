package com.volvo960.obdctl.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Half-duplex transport to an ELM327 dongle over Bluetooth Classic SPP.
 *
 * Every command that ever reaches the adapter — the init sequence, raw
 * console input, actuator hold-repeat ticks — funnels through [sendRaw],
 * which is serialized by [mutex]. This is the single queue the rest of the
 * app shares; nothing above this layer is allowed to touch the socket
 * directly. Do not change this file when adding new actuators — actuator
 * behavior lives entirely above this layer.
 */
class Elm327Transport(private val logger: CommandLogger) {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val DEFAULT_COMMAND_TIMEOUT_MS = 4_000L
        private val RECONNECT_DELAYS_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 20_000)
        private const val POLL_INTERVAL_MS = 12L
    }

    sealed class CommandResult {
        data class Success(val response: String) : CommandResult()
        data class Error(val message: String) : CommandResult()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private var connectionJob: Job? = null
    @Volatile private var targetDevice: BluetoothDevice? = null
    @Volatile private var autoReconnect = false
    @Volatile private var reconnectAttempt = 0

    val isConnected: Boolean
        get() = connectionState.value is ConnectionState.Connected

    /** Starts connecting to [device], retrying with backoff until [disconnect] is called. */
    fun connect(device: BluetoothDevice) {
        targetDevice = device
        autoReconnect = true
        reconnectAttempt = 0
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

    /**
     * Sends one command and waits for the adapter's `>` prompt. Rejected
     * immediately if not currently connected — callers (hold-repeat loops in
     * particular) must treat that as a failed tick, not something to queue
     * for later.
     */
    suspend fun sendRaw(command: String, timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS): CommandResult {
        if (connectionState.value !is ConnectionState.Connected) {
            return CommandResult.Error("нет соединения")
        }
        val result = mutex.withLock { rawExchange(command, timeoutMs) }
        if (result is CommandResult.Error) {
            onTransportFailure(result.message)
        }
        return result
    }

    /**
     * Sends several commands back-to-back while holding the queue lock for the
     * whole run, so nothing else can interleave in the middle.
     *
     * Manufacturer-specific actuator work needs this: selecting an ECU and
     * opening a diagnostic session are only meaningful if the control command
     * that follows lands in that same session. A per-command lock would let
     * another actuator's traffic (or a coolant-temp poll) slip in between and
     * silently retarget the session.
     *
     * Stops at the first failure and returns the responses collected so far.
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
                when (last) {
                    is CommandResult.Success -> {
                        if (responses.isNotEmpty()) responses.append('\n')
                        responses.append((last as CommandResult.Success).response)
                    }
                    is CommandResult.Error -> return@withLock last
                }
                if (index != commands.lastIndex) delay(interCommandDelayMs)
            }
            CommandResult.Success(responses.toString())
        }
        if (result is CommandResult.Error) {
            onTransportFailure(result.message)
        }
        return result
    }

    private suspend fun connectLoop(device: BluetoothDevice) {
        while (autoReconnect) {
            _connectionState.value = ConnectionState.Connecting
            val ok = tryConnectOnce(device)
            if (ok) {
                reconnectAttempt = 0
                return
            }
            if (!autoReconnect) return
            val delayMs = RECONNECT_DELAYS_MS[minOf(reconnectAttempt, RECONNECT_DELAYS_MS.size - 1)]
            reconnectAttempt++
            delay(delayMs)
        }
    }

    private suspend fun tryConnectOnce(device: BluetoothDevice): Boolean {
        return try {
            closeQuietly()
            val sock = withContext(Dispatchers.IO) { openSocket(device) }
            socket = sock
            input = sock.inputStream
            output = sock.outputStream
            initAdapter()
            _connectionState.value = ConnectionState.Connected(device.name ?: device.address, device.address)
            true
        } catch (e: SecurityException) {
            _connectionState.value = ConnectionState.Failed("нет разрешения Bluetooth")
            logger.logError("connect denied: ${e.message}")
            autoReconnect = false
            false
        } catch (e: IOException) {
            _connectionState.value = ConnectionState.Failed(e.message ?: "не удалось подключиться")
            logger.logError("connect failed: ${e.message}")
            false
        }
    }

    /**
     * Cheap ELM327 clones frequently don't implement SDP correctly, so the
     * standard [BluetoothDevice.createRfcommSocketToServiceRecord] path
     * throws "read failed, socket might closed or timeout, read ret: -1"
     * even though the device is right there and paired. Fall back to the
     * hidden-but-stable `createRfcommSocket(channel)` call (RFCOMM channel 1,
     * which is what SPP dongles use) via reflection — this bypasses SDP
     * entirely and is the standard workaround most OBD-II Android apps use
     * for the ELM327 clone ecosystem.
     */
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        val standard = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: IOException) {
            null
        }
        if (standard != null) {
            try {
                standard.connect()
                return standard
            } catch (e: IOException) {
                logger.logError("standard SPP connect failed, trying channel 1 fallback: ${e.message}")
                try { standard.close() } catch (_: Exception) { }
            }
        }
        return try {
            val fallback = device.javaClass
                .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                .invoke(device, 1) as BluetoothSocket
            fallback.connect()
            fallback
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("не удалось открыть сокет (fallback): ${e.message}", e)
        }
    }

    /**
     * Best-effort adapter reset. Individual AT command failures here are
     * logged but never fail the connection or touch [_connectionState] —
     * cheap ELM327 clones are inconsistent about ATL0/ATH0 and the link is
     * still usable for real traffic even if one of these hiccups.
     */
    private suspend fun initAdapter() {
        val initSequence = listOf(
            "ATZ" to 6_000L,
            "ATE0" to 3_000L,
            "ATL0" to 3_000L,
            "ATH0" to 3_000L,
            "ATSP3" to 3_000L, // ISO 9141-2, matches the Volvo 960's OBD wiring
        )
        for ((cmd, timeout) in initSequence) {
            mutex.withLock { rawExchange(cmd, timeout) }
            delay(150)
        }
    }

    /** Caller must hold [mutex]. Never mutates [_connectionState]. */
    private suspend fun rawExchange(command: String, timeoutMs: Long): CommandResult {
        val out = output
        val inp = input
        if (out == null || inp == null) return CommandResult.Error("нет соединения")
        logger.logSent(command)
        return try {
            withContext(Dispatchers.IO) {
                out.write((command.trim() + "\r").toByteArray(Charsets.US_ASCII))
                out.flush()
            }
            val raw = withContext(Dispatchers.IO) { readUntilPrompt(inp, timeoutMs) }
            val cleaned = cleanResponse(raw)
            logger.logReceived(cleaned)
            CommandResult.Success(cleaned)
        } catch (e: IOException) {
            logger.logError(e.message ?: "io error")
            CommandResult.Error(e.message ?: "ошибка ввода-вывода")
        }
    }

    private fun readUntilPrompt(stream: InputStream, timeoutMs: Long): String {
        val sb = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (stream.available() > 0) {
                val b = stream.read()
                if (b == -1) throw IOException("адаптер закрыл соединение")
                val c = b.toChar()
                if (c == '>') break
                sb.append(c)
            } else {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    throw IOException("нет ответа адаптера за ${timeoutMs} мс")
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        return sb.toString()
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
        try { input?.close() } catch (_: Exception) { }
        try { output?.close() } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        input = null
        output = null
        socket = null
    }
}
