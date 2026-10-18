package com.mpsdiag.transport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Wi-Fi ELM327 adapter (TCP). Most use 192.168.0.10:35000 on their own hotspot.
 *
 * @param socketFactory pass the Wi-Fi network's factory so Android does not route the
 *        connection over mobile data (the adapter's hotspot has no internet).
 */
class WifiTransport(
    private val host: String,
    private val port: Int,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
) : Transport() {

    override val displayName: String = "Wi-Fi $host:$port"

    private var socket: Socket? = null
    private var output: OutputStream? = null

    override fun open() {
        val s: Socket = socketFactory.createSocket()
        try {
            s.connect(InetSocketAddress(host, port), 10_000)
            s.tcpNoDelay = true
        } catch (e: IOException) {
            s.close()
            throw IOException("Could not reach $host:$port — is the phone on the adapter's Wi-Fi?", e)
        }
        socket = s
        output = s.getOutputStream()
        isOpen = true
        Thread({ readLoop(s.getInputStream()) }, "wifi-reader").apply {
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
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000
    }
}
