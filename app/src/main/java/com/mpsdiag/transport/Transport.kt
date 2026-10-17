package com.mpsdiag.transport

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A byte pipe to an ELM327-compatible adapter.
 *
 * Incoming bytes are pushed into [incoming] by the implementation (reader thread or
 * BLE notification callback) so that the protocol layer can read them with a timeout,
 * regardless of the physical link.
 */
abstract class Transport {
    abstract val displayName: String

    protected val incoming = LinkedBlockingQueue<ByteArray>()
    private var pending: ByteArray? = null
    private var pendingPos = 0

    @Volatile
    var isOpen = false
        protected set

    /** Blocking. Throws [IOException] on failure. */
    @Throws(IOException::class)
    abstract fun open()

    @Throws(IOException::class)
    abstract fun write(data: ByteArray)

    abstract fun close()

    /** Discards anything received but not yet read. */
    fun drain() {
        incoming.clear()
        pending = null
    }

    /**
     * Reads a single byte, waiting at most [timeoutMs].
     * Returns -1 on timeout. Throws [IOException] if the link dropped.
     */
    @Throws(IOException::class)
    fun readByte(timeoutMs: Long): Int {
        var chunk = pending
        if (chunk == null || pendingPos >= chunk.size) {
            if (!isOpen && incoming.isEmpty()) throw IOException("Adapter disconnected")
            chunk = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return -1
            if (chunk === CLOSED_MARKER) throw IOException("Adapter disconnected")
            pending = chunk
            pendingPos = 0
            if (chunk.isEmpty()) return -1
        }
        return chunk[pendingPos++].toInt() and 0xFF
    }

    protected fun signalClosed() {
        isOpen = false
        incoming.offer(CLOSED_MARKER)
    }

    companion object {
        private val CLOSED_MARKER = ByteArray(0)
    }
}
