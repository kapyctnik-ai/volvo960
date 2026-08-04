package com.volvo960.obdctl.service

import com.volvo960.obdctl.data.Actuator
import com.volvo960.obdctl.transport.Elm327Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs hold-repeat loops for any number of actuators concurrently. Each loop
 * only decides *when* to send a command; the actual send is always
 * serialized by [Elm327Transport]'s own queue, so actuators never race each
 * other on the wire.
 *
 * This class applies uniformly to every actuator in the registry — it has
 * no actuator-specific logic. New actuators need nothing added here.
 */
class HoldManager(
    private val transport: Elm327Transport,
    private val scope: CoroutineScope,
    private val onAutoStop: (actuator: Actuator, reason: String) -> Unit,
) {
    companion object {
        /** Global safety rule: any hold with no successful response this long auto-stops. */
        const val WATCHDOG_TIMEOUT_MS = 30_000L
    }

    private val jobs = ConcurrentHashMap<Long, Job>()

    private val _activeHolds = MutableStateFlow<Map<Long, HoldStatus>>(emptyMap())
    val activeHolds: StateFlow<Map<Long, HoldStatus>> = _activeHolds.asStateFlow()

    fun isHeld(actuatorId: Long): Boolean = jobs.containsKey(actuatorId)

    fun start(actuator: Actuator) {
        if (jobs.containsKey(actuator.id)) return
        val job = scope.launch { runHoldLoop(actuator) }
        jobs[actuator.id] = job
    }

    fun stop(actuatorId: Long) {
        jobs[actuatorId]?.cancel()
    }

    fun stopAll() {
        jobs.keys.toList().forEach { stop(it) }
    }

    /** Runs the init handshake (if any) followed by the actuator's command, once. */
    suspend fun sendOnce(actuator: Actuator): Elm327Transport.CommandResult {
        val init = actuator.initCommands()
        if (init.isNotEmpty()) {
            val initResult = transport.sendSequence(init, actuator.responseTimeoutMs)
            if (initResult is Elm327Transport.CommandResult.Error) return initResult
        }
        return transport.sendSequence(actuator.commands(), actuator.responseTimeoutMs)
    }

    private suspend fun runHoldLoop(actuator: Actuator) {
        val startedAt = System.currentTimeMillis()
        var lastSuccessAt = startedAt
        setStatus(HoldStatus(actuator.id, actuator.name, startedAt, actuator.repeatIntervalMs, startedAt, startedAt))
        var stopReason: String? = null
        val tickCommands = actuator.commands()
        try {
            // The handshake and the first control command go out as one
            // uninterruptible batch. Splitting them would leave a gap where
            // another caller's traffic could land between opening the session
            // and using it, which resets the adapter and voids the session.
            val init = actuator.initCommands()
            if (init.isNotEmpty()) {
                val initResult = transport.sendSequence(init + tickCommands, actuator.responseTimeoutMs)
                if (initResult is Elm327Transport.CommandResult.Error) {
                    stopReason = "инициализация не прошла: ${initResult.message}"
                    return
                }
                delay(actuator.repeatIntervalMs)
            }
            while (true) {
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed >= actuator.autoStopTimeoutMs) {
                    stopReason = "истёк таймаут удержания"
                    break
                }
                val result = transport.sendSequence(tickCommands, actuator.responseTimeoutMs)
                val now = System.currentTimeMillis()
                when (result) {
                    is Elm327Transport.CommandResult.Success -> {
                        lastSuccessAt = now
                        updateStatus(actuator.id) { it.copy(lastAttemptAt = now, lastSuccessAt = now, lastError = null) }
                    }
                    is Elm327Transport.CommandResult.Error -> {
                        updateStatus(actuator.id) { it.copy(lastAttemptAt = now, lastError = result.message) }
                        if (now - lastSuccessAt >= WATCHDOG_TIMEOUT_MS) {
                            stopReason = "нет ответа адаптера более 30 с"
                            break
                        }
                    }
                }
                delay(actuator.repeatIntervalMs)
            }
        } finally {
            withContext(NonCancellable) {
                val off = actuator.offCommands()
                if (off.isNotEmpty()) {
                    transport.sendSequence(off, actuator.responseTimeoutMs)
                }
            }
            jobs.remove(actuator.id)
            clearStatus(actuator.id)
            if (stopReason != null) {
                onAutoStop(actuator, stopReason)
            }
        }
    }

    private fun setStatus(status: HoldStatus) {
        _activeHolds.update { it + (status.actuatorId to status) }
    }

    private fun updateStatus(id: Long, transform: (HoldStatus) -> HoldStatus) {
        _activeHolds.update { current -> current[id]?.let { current + (id to transform(it)) } ?: current }
    }

    private fun clearStatus(id: Long) {
        _activeHolds.update { it - id }
    }
}
