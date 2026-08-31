package com.volvo960.obdctl.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Bluetooth Classic serial port profile — how ELM327 v1.4/v2.x dongles talk. */
class SppLink(
    private val device: BluetoothDevice,
    private val logger: CommandLogger,
) : ObdLink {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val READ_BUFFER = 256
    }

    override val label = "SPP"

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /**
     * Bytes arrive on their own thread and wait here.
     *
     * The obvious implementation — poll `available()` and sleep a few
     * milliseconds — burns the CPU for the whole of every timeout, which with a
     * dongle that has gone away is most of the time, forever. A blocking read on
     * its own thread parks instead: no wakeups at all while the adapter is
     * silent, and closing the socket unblocks it.
     */
    private val incoming = LinkedBlockingQueue<ByteArray>()
    private var reader: Thread? = null
    @Volatile private var closed = false
    @Volatile private var readFailure: String? = null

    override fun open() {
        closed = false
        readFailure = null
        incoming.clear()
        val sock = openSocket()
        socket = sock
        input = sock.inputStream
        output = sock.outputStream
        startReader(sock.inputStream)
    }

    private fun startReader(stream: InputStream) {
        val thread = Thread({
            val buffer = ByteArray(READ_BUFFER)
            try {
                while (!closed) {
                    val read = stream.read(buffer)
                    if (read < 0) {
                        readFailure = "адаптер закрыл соединение"
                        break
                    }
                    if (read > 0) incoming.offer(buffer.copyOf(read))
                }
            } catch (e: IOException) {
                if (!closed) readFailure = e.message ?: "обрыв чтения"
            }
        }, "spp-reader")
        thread.isDaemon = true
        reader = thread
        thread.start()
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
        readFailure?.let { throw IOException(it) }
        out.write(bytes)
        out.flush()
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val sb = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            readFailure?.let { throw IOException(it) }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw IOException("нет ответа адаптера за $timeoutMs мс")
            val chunk = incoming.poll(remaining, TimeUnit.MILLISECONDS)
                ?: throw IOException("нет ответа адаптера за $timeoutMs мс")
            for (b in chunk) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c == '>') return sb.toString()
                sb.append(c)
            }
        }
    }

    override fun close() {
        closed = true
        try { input?.close() } catch (_: Exception) { }
        try { output?.close() } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        reader?.interrupt()
        reader = null
        input = null
        output = null
        socket = null
        incoming.clear()
    }
}
