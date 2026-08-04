package com.volvo960.obdctl.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One user-defined car actuator. This is the entire extensibility point of
 * the app: adding a new relay/solenoid means inserting a row here (typically
 * via "Save as actuator" in the raw console), never touching transport or
 * service code.
 */
@Entity(tableName = "actuators")
data class Actuator(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /**
     * Commands sent once when the actuator is activated, before [command],
     * newline-separated. Manufacturer-specific actuator control generally
     * needs a setup handshake here — protocol select, header/ECU addressing,
     * opening a diagnostic session — which must not be repeated on every tick.
     * Empty for plain actuators that are a single self-contained command.
     */
    val initScript: String = "",
    /**
     * Raw command sent to activate/pulse this actuator, exactly as typed in the
     * console. May be several newline-separated commands; they are sent as one
     * uninterruptible batch.
     */
    val command: String,
    /** Optional explicit "turn off" command; sent when a hold is stopped, if present. */
    val offCommand: String? = null,
    val behavior: ActuatorBehavior = ActuatorBehavior.HOLD_REPEAT,
    /** Only meaningful for HOLD_REPEAT: how often [command] is resent while held. */
    val repeatIntervalMs: Long = 2_000,
    /** Only meaningful for HOLD_REPEAT: hold is force-stopped after this long regardless of responses. */
    val autoStopTimeoutMs: Long = 5 * 60_000,
    /** Per-command timeout waiting for the adapter's response. */
    val responseTimeoutMs: Long = 4_000,
    /** "Don't warn again" acknowledgement, remembered per actuator id. */
    val warningAcknowledged: Boolean = false,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** [initScript] split into individual commands, blank lines and `#` comments dropped. */
    fun initCommands(): List<String> = splitScript(initScript)

    /** [command] split into individual commands. */
    fun commands(): List<String> = splitScript(command)

    /** [offCommand] split into individual commands; empty when there is none. */
    fun offCommands(): List<String> = splitScript(offCommand.orEmpty())

    private fun splitScript(script: String): List<String> =
        script.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
}
