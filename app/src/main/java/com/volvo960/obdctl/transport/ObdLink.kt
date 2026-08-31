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

    @Throws(IOException::class)
    fun open()

    @Throws(IOException::class)
    fun write(bytes: ByteArray)

    /** Reads until the adapter's `>` prompt; throws on timeout. */
    @Throws(IOException::class)
    fun readUntilPrompt(timeoutMs: Long): String

    fun close()
}
