package com.mpsdiag

import android.os.Handler
import android.os.Looper
import com.mpsdiag.diag.Diag
import com.mpsdiag.elm.Elm327
import com.mpsdiag.transport.Transport
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * App-wide connection state. All adapter I/O runs on one background thread, so
 * requests from different screens never interleave on the wire.
 */
object Session {

    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "adapter-io") }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var transport: Transport? = null
        private set

    @Volatile
    var elm: Elm327? = null
        private set

    @Volatile
    var diag: Diag? = null
        private set

    val isConnected: Boolean get() = transport?.isOpen == true && diag != null

    /** Name of the adapter being connected to, or null when idle. */
    @Volatile
    var connectingTo: String? = null
        private set

    /** Recent adapter traffic, newest last. */
    val log = ArrayDeque<String>()
    private const val LOG_LIMIT = 2_000
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun notifyChanged() = main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    /** Increments on every log change, so screens can tell when to redraw. */
    @Volatile
    var logVersion = 0L
        private set

    fun appendLog(line: String) {
        synchronized(log) {
            log.addLast(line)
            while (log.size > LOG_LIMIT) log.removeFirst()
            logVersion++
        }
    }

    fun clearLog() {
        synchronized(log) {
            log.clear()
            logVersion++
        }
    }

    /**
     * Opens [t], initialises the ELM327 and reports the adapter version (or the error)
     * to [done] on the main thread.
     */
    fun connect(t: Transport, done: (Result<String>) -> Unit) {
        connectingTo = t.displayName
        notifyChanged()
        io.execute {
            val result = runCatching {
                disconnectNow()
                t.open()
                transport = t
                val e = Elm327(t).also { it.trafficListener = ::appendLog }
                elm = e
                val d = Diag(e)
                val version = d.initAdapter()
                diag = d
                version
            }
            if (result.isFailure) {
                t.close() // release a half-open socket/GATT connection
                disconnectNow()
            }
            connectingTo = null
            notifyChanged()
            main.post { done(result) }
        }
    }

    fun disconnect() {
        io.execute {
            disconnectNow()
            notifyChanged()
        }
    }

    private fun disconnectNow() {
        transport?.close()
        transport = null
        elm = null
        diag = null
    }

    /**
     * Runs [block] on the adapter thread with the connected [Diag]. The result (or the error)
     * is delivered to [done] on the main thread.
     */
    fun <T> run(block: (Diag) -> T, done: (Result<T>) -> Unit): Future<*> = io.submit {
        val d = diag
        val result: Result<T> = if (d == null) {
            Result.failure(IOException("Not connected to an adapter"))
        } else {
            runCatching { block(d) }
        }
        if (result.exceptionOrNull() is IOException && transport?.isOpen != true) {
            disconnectNow()
            notifyChanged()
        }
        main.post { done(result) }
    }

    fun postMain(r: () -> Unit) {
        main.post(r)
    }
}
