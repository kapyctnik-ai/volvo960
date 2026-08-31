package com.volvo960.obdctl.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Bluetooth LE (GATT) link, which is what ELM327 v1.5 clones and every
 * iOS-compatible dongle use instead of the classic serial profile.
 *
 * There is no standard for this: each vendor picks its own service and
 * characteristics. Rather than hard-code one clone's UUIDs, the known ones are
 * tried first and anything else falls back to a structural match — a service
 * carrying a notify characteristic and a writable one. That is precisely what
 * a serial-over-GATT bridge looks like, whoever built it.
 *
 * Writes are chunked to the negotiated MTU because a GATT write is a single
 * packet, not a stream; a long command silently truncates otherwise.
 */
@SuppressLint("MissingPermission")
class BleLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val logger: CommandLogger,
) : ObdLink {

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Serial-bridge chips seen in the wild, best guess first. */
        private val KNOWN_SERVICES = listOf(
            "0000ffe0-0000-1000-8000-00805f9b34fb", // HM-10 and most ELM327 v1.5 BLE
            "0000fff0-0000-1000-8000-00805f9b34fb", // Vgate iCar and friends
            "000018f0-0000-1000-8000-00805f9b34fb", // "OBDII" branded LE dongles
            "e7810a71-73ae-499d-8c15-faa9aef0c3f2", // LELink
        ).map { UUID.fromString(it) }

        /** Services that are never the data pipe. */
        private val GENERIC_SERVICES = setOf(
            "00001800-0000-1000-8000-00805f9b34fb",
            "00001801-0000-1000-8000-00805f9b34fb",
            "0000180a-0000-1000-8000-00805f9b34fb",
            "0000180f-0000-1000-8000-00805f9b34fb",
        ).map { UUID.fromString(it) }.toSet()

        private const val CONNECT_TIMEOUT_MS = 20_000L
        /** Android's own generic failure; on many phones a second attempt just works. */
        private const val GATT_INTERNAL_ERROR = 133
        private const val RETRY_DELAY_MS = 600L
        /** Bridges need a moment between subscribing and the first command. */
        private const val NOTIFY_SETTLE_MS = 250L
        private const val OP_TIMEOUT_MS = 5_000L
        private const val WANTED_MTU = 185
        private const val POLL_INTERVAL_MS = 10L
    }

    override var label: String = "BLE"
        private set

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    @Volatile private var payloadSize = 20

    private val incoming = LinkedBlockingQueue<ByteArray>()
    private var connectedLatch = CountDownLatch(1)
    private var servicesLatch = CountDownLatch(1)
    private var descriptorLatch = CountDownLatch(1)
    private var writeLatch = CountDownLatch(0)
    @Volatile private var lastFailure: String? = null
    @Volatile private var dropped = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedLatch.countDown()
                    // MTU first: 20-byte writes make every command a multi-packet
                    // affair and some bridges lose the tail.
                    if (!g.requestMtu(WANTED_MTU)) g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) lastFailure = "GATT status $status"
                    dropped = true
                    connectedLatch.countDown()
                    servicesLatch.countDown()
                    descriptorLatch.countDown()
                    writeLatch.countDown()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // 3 bytes of ATT header come off the top of every packet.
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) payloadSize = mtu - 3
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) lastFailure = "поиск сервисов: status $status"
            // A serial bridge is polled several times a second; the default
            // connection interval adds tens of milliseconds to every request.
            try {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } catch (_: Exception) {
            }
            servicesLatch.countDown()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorLatch.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) lastFailure = "запись: status $status"
            writeLatch.countDown()
        }

        // Deprecated in API 33, and still the only notification callback below it.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                c.value?.let { incoming.offer(it.copyOf()) }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            incoming.offer(value.copyOf())
        }
    }

    override fun open() {
        try {
            openOnce()
        } catch (e: IOException) {
            // GATT 133 is Android's catch-all and is famously transient on
            // first connect; one retry turns most of them into a link.
            if (lastFailure?.contains(GATT_INTERNAL_ERROR.toString()) != true) throw e
            logger.logError("BLE: status $GATT_INTERNAL_ERROR, вторая попытка")
            close()
            Thread.sleep(RETRY_DELAY_MS)
            openOnce()
        }
    }

    private fun openOnce() {
        dropped = false
        lastFailure = null
        incoming.clear()
        connectedLatch = CountDownLatch(1)
        servicesLatch = CountDownLatch(1)

        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("BLE: connectGatt вернул null")
        gatt = g

        if (!connectedLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) || dropped) {
            close()
            throw IOException("BLE: не подключился (${lastFailure ?: "таймаут"})")
        }
        if (!servicesLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) || dropped) {
            close()
            throw IOException("BLE: сервисы не найдены (${lastFailure ?: "таймаут"})")
        }

        val pair = pickCharacteristics(g) ?: run {
            close()
            throw IOException("BLE: не нашёл пару notify+write, это не последовательный мост")
        }
        notifyChar = pair.first
        writeChar = pair.second
        label = "BLE ${pair.first.uuid.toString().substring(4, 8).uppercase()}"
        logger.logError("BLE: сервис ${pair.first.service.uuid}, notify ${pair.first.uuid}, write ${pair.second.uuid}, payload $payloadSize")

        enableNotifications(g, pair.first)
        // Anything written before the subscription has settled is answered into
        // the void: the reply is generated, and nobody is listening yet.
        Thread.sleep(NOTIFY_SETTLE_MS)
        incoming.clear()
    }

    /**
     * Prefers a known serial-bridge service, then falls back to any service
     * that has both halves of a serial pipe. Notify and write can be the same
     * characteristic (HM-10 does exactly that).
     */
    private fun pickCharacteristics(
        g: BluetoothGatt,
    ): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val services = g.services.orEmpty().filter { it.uuid !in GENERIC_SERVICES }
        val ordered = services.sortedBy { s ->
            val idx = KNOWN_SERVICES.indexOf(s.uuid)
            if (idx == -1) KNOWN_SERVICES.size else idx
        }
        for (service in ordered) {
            val pair = pairIn(service)
            if (pair != null) return pair
        }
        return null
    }

    private fun pairIn(
        service: BluetoothGattService,
    ): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val chars = service.characteristics.orEmpty()
        val notify = chars.firstOrNull {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        } ?: return null
        // Write-without-response is what these bridges expect; it is also the
        // only write type some of them implement.
        val write = chars.firstOrNull {
            it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        } ?: chars.firstOrNull {
            it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        } ?: return null
        return notify to write
    }

    private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(ch, true)) {
            throw IOException("BLE: не включились уведомления")
        }
        val cccd = ch.getDescriptor(CCCD) ?: return
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        descriptorLatch = CountDownLatch(1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }
        descriptorLatch.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    override fun write(bytes: ByteArray) {
        val g = gatt ?: throw IOException("BLE: нет соединения")
        val ch = writeChar ?: throw IOException("BLE: нет характеристики записи")
        if (dropped) throw IOException("BLE: соединение потеряно")
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + payloadSize, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            writeChunk(g, ch, chunk)
            offset = end
        }
    }

    private fun writeChunk(g: BluetoothGatt, ch: BluetoothGattCharacteristic, chunk: ByteArray) {
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        writeLatch = CountDownLatch(1)
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, chunk, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = type
                ch.value = chunk
                g.writeCharacteristic(ch)
            }
        }
        if (!ok) throw IOException("BLE: запись отклонена стеком")
        // Waiting keeps one GATT operation in flight at a time; firing them
        // back to back drops packets on most bridges.
        writeLatch.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val sb = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (dropped) throw IOException("BLE: соединение потеряно")
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw IOException("нет ответа адаптера за $timeoutMs мс")
            val chunk = incoming.poll(minOf(remaining, POLL_INTERVAL_MS * 10), TimeUnit.MILLISECONDS)
                ?: continue
            for (b in chunk) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c == '>') return sb.toString()
                sb.append(c)
            }
        }
    }

    override fun close() {
        val g = gatt
        gatt = null
        writeChar = null
        notifyChar = null
        incoming.clear()
        try { g?.disconnect() } catch (_: Exception) { }
        try { g?.close() } catch (_: Exception) { }
    }
}
