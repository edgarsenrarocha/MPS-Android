package com.mpsdiag.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Classic Bluetooth (RFCOMM / Serial Port Profile) ELM327 adapter. */
@SuppressLint("MissingPermission") // Permissions are requested before a transport is created.
class BluetoothSppTransport(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
) : Transport() {

    override val displayName: String = device.name ?: device.address

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null

    override fun open() {
        adapter.cancelDiscovery()
        val s = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (first: IOException) {
            // Many cheap adapters only answer on RFCOMM channel 1 without SDP.
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
            } catch (e: Exception) {
                throw IOException("Could not connect to $displayName: ${first.message}", first)
            }
        }
        socket = s
        output = s.outputStream
        isOpen = true
        reader = Thread({ readLoop(s.inputStream) }, "bt-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(input: InputStream) {
        val buffer = ByteArray(512)
        try {
            while (isOpen) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) incoming.offer(buffer.copyOf(n))
            }
        } catch (_: IOException) {
        } finally {
            signalClosed()
        }
    }

    override fun write(data: ByteArray) {
        val out = output ?: throw IOException("Not connected")
        out.write(data)
        out.flush()
    }

    override fun close() {
        isOpen = false
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        output = null
        signalClosed()
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
