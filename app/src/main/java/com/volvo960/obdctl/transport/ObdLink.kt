package com.volvo960.obdctl.transport

import java.io.IOException

/**
 * One byte pipe to an ELM327, whatever radio it happens to speak.
 *
 * The adapter is half-duplex and prompt-driven either way: you write a line,
 * you read until `>`. That is the whole contract, and it is why Bluetooth
 * Classic (SPP) and Bluetooth LE (GATT) can sit behind the same interface —
 * [Elm327Transport] never learns which one it has.
 *
 * Every method except [close] is blocking and must be called on an IO
 * dispatcher.
 */
interface ObdLink {
    /** Short human name for logs, e.g. "SPP" or "BLE FFE1". */
    val label: String

    /**
     * True once the radio has told us the peer is gone. BLE knows this within
     * its supervision timeout and reports it as an event; Classic usually finds
     * out only when a write fails. Where it is known, it beats waiting out a
     * silence timer.
     */
    val isBroken: Boolean get() = false

    /**
     * Asks for a cheaper connection while nothing is watching. Only BLE can
     * honour it — the connection interval is a parameter of the link there,
     * while Classic leaves that to the stack.
     */
    fun setLowPower(lowPower: Boolean) = Unit

    @Throws(IOException::class)
    fun open()

    @Throws(IOException::class)
    fun write(bytes: ByteArray)

    /** Reads until the adapter's `>` prompt; throws on timeout. */
    @Throws(IOException::class)
    fun readUntilPrompt(timeoutMs: Long): String

    fun close()
}
