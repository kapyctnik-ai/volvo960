package com.volvo960.obdctl.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Bluetooth Classic serial port profile — how ELM327 v1.4/v2.x dongles talk. */
class SppLink(
    private val device: BluetoothDevice,
    private val logger: CommandLogger,
) : ObdLink {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val POLL_INTERVAL_MS = 12L
    }

    override val label = "SPP"

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun open() {
        val sock = openSocket()
        socket = sock
        input = sock.inputStream
        output = sock.outputStream
    }

    /**
     * Some dongles don't publish a usable SDP record, so the standard
     * [BluetoothDevice.createRfcommSocketToServiceRecord] path fails with
     * "read failed, socket might closed or timeout" on a device that is right
     * there and paired. The hidden `createRfcommSocket(channel)` call skips
     * SDP and connects to RFCOMM channel 1, which is where SPP lives.
     */
    private fun openSocket(): BluetoothSocket {
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
                logger.logError("SPP: обычный сокет не открылся, пробую канал 1: ${e.message}")
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

    override fun write(bytes: ByteArray) {
        val out = output ?: throw IOException("сокет закрыт")
        out.write(bytes)
        out.flush()
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val stream = input ?: throw IOException("сокет закрыт")
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
                    throw IOException("нет ответа адаптера за $timeoutMs мс")
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        return sb.toString()
    }

    override fun close() {
        try { input?.close() } catch (_: Exception) { }
        try { output?.close() } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        input = null
        output = null
        socket = null
    }
}
