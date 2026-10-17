package com.mpsdiag.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Bluetooth Low Energy ELM327 adapter (Vgate iCar, OBDLink CX, generic "IOS-Vlink" clones...).
 *
 * BLE adapters expose a vendor UART-like service. Rather than hardcoding every vendor,
 * we pick the first non-standard service that has both a notify and a write characteristic.
 */
@SuppressLint("MissingPermission") // Permissions are requested before a transport is created.
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : Transport() {

    override val displayName: String = device.name ?: device.address

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val ready = CountDownLatch(1)
    private val writeDone = Semaphore(1)
    private var failure: String? = null
    private var mtuPayload = 20

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(185)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                failure = failure ?: "Disconnected (status $status)"
                ready.countDown()
                signalClosed()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtuPayload = (mtu - 3).coerceAtLeast(20)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failure = "Service discovery failed ($status)"
                ready.countDown()
                return
            }
            val (notify, write) = findUartPair(g) ?: run {
                failure = "No serial service found on $displayName"
                ready.countDown()
                return
            }
            writeChar = write
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (cccd == null) {
                ready.countDown()
                return
            }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ready.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.release()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.offer(value.copyOf())
        }

        @Deprecated("Used on Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                c.value?.let { incoming.offer(it.copyOf()) }
            }
        }
    }

    override fun open() {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (!ready.await(15, TimeUnit.SECONDS)) {
            close()
            throw IOException("Timed out connecting to $displayName")
        }
        failure?.let {
            close()
            throw IOException(it)
        }
        isOpen = true
    }

    override fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("Not connected")
        val c = writeChar ?: throw IOException("Not connected")
        var offset = 0
        while (offset < data.size) {
            val chunk = data.copyOfRange(offset, minOf(data.size, offset + mtuPayload))
            if (!writeDone.tryAcquire(2, TimeUnit.SECONDS)) throw IOException("BLE write timed out")
            val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, chunk, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = chunk
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (!ok) {
                writeDone.release()
                throw IOException("BLE write failed")
            }
            offset += chunk.size
        }
    }

    override fun close() {
        isOpen = false
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        writeDone.release()
        signalClosed()
    }

    private fun findUartPair(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val services = g.services.filterNot { it.uuid.toString().substring(4, 8).lowercase() in STANDARD_SERVICES }
        // Prefer well-known adapter services first.
        val ordered = services.sortedBy { s -> KNOWN_SERVICES.indexOf(s.uuid).let { if (it < 0) Int.MAX_VALUE else it } }
        for (s in ordered) {
            val notify = s.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            } ?: continue
            val write = s.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            } ?: continue
            return notify to write
        }
        return null
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val STANDARD_SERVICES = setOf("1800", "1801", "180a", "180f")
        private val KNOWN_SERVICES = listOf(
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),
        )
    }
}
