package com.mpsdiag.elm

import android.util.Log
import com.mpsdiag.transport.Transport
import java.io.IOException

/**
 * Talks to an ELM327 (or compatible) interpreter: sends a text command terminated by CR
 * and collects the reply up to the '>' prompt.
 */
class Elm327(private val transport: Transport) {

    /** Optional listener for a raw traffic log (shown on the Log screen). */
    var trafficListener: ((String) -> Unit)? = null

    var version: String = ""
        private set

    /**
     * Sends [cmd] and returns the reply lines (echo, blanks and the prompt removed).
     * Returns an empty list if nothing arrived before [timeoutMs].
     */
    @Throws(IOException::class)
    @Synchronized
    fun command(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<String> {
        transport.drain()
        log("> $cmd")
        transport.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) {
                log("< (timeout) ${sb.toString().trim()}")
                break
            }
            val b = transport.readByte(left)
            if (b < 0) continue
            if (b == '>'.code) break
            if (b != 0) sb.append(b.toChar())
        }
        val lines = sb.toString()
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(cmd, ignoreCase = true) }
        if (lines.isNotEmpty()) log("< " + lines.joinToString(" | "))
        return lines
    }

    /**
     * Listens to the bus (ATMA, filtered by the current ATCRA) until [done] accepts a line
     * or [timeoutMs] passes, then stops monitoring. Returns the lines seen.
     * Used to catch a module's final answer after it replied "response pending".
     */
    @Throws(IOException::class)
    @Synchronized
    fun monitorUntil(timeoutMs: Long, done: (String) -> Boolean): List<String> {
        transport.drain()
        log("> ATMA")
        transport.write("ATMA\r".toByteArray(Charsets.US_ASCII))
        val seen = mutableListOf<String>()
        val line = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        var finished = false
        while (!finished && System.currentTimeMillis() < deadline) {
            val b = transport.readByte(deadline - System.currentTimeMillis())
            if (b < 0) continue
            if (b == '\r'.code || b == '\n'.code || b == '>'.code) {
                val l = line.toString().trim()
                line.setLength(0)
                if (l.isNotEmpty() && !l.equals("ATMA", true)) {
                    seen += l
                    log("< $l")
                    if (done(l)) finished = true
                }
                if (b == '>'.code) return seen // adapter left monitor mode by itself
            } else if (b != 0) {
                line.append(b.toChar())
            }
        }
        // Any character stops monitoring; wait for the prompt.
        transport.write("\r".toByteArray(Charsets.US_ASCII))
        val stopDeadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS
        while (System.currentTimeMillis() < stopDeadline) {
            val b = transport.readByte(stopDeadline - System.currentTimeMillis())
            if (b == '>'.code) break
        }
        return seen
    }

    /** Resets the interpreter and returns its identification string (e.g. "ELM327 v1.5"). */
    @Throws(IOException::class)
    fun reset(): String {
        val lines = command("ATZ", RESET_TIMEOUT_MS)
        version = lines.lastOrNull { it.contains("ELM", ignoreCase = true) } ?: lines.lastOrNull().orEmpty()
        return version
    }

    /** Sends an AT command that should answer "OK". Returns true if it did. */
    @Throws(IOException::class)
    fun at(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean =
        command(cmd, timeoutMs).any { it.equals("OK", ignoreCase = true) || it.startsWith("ELM", ignoreCase = true) }

    private fun log(s: String) {
        Log.d(TAG, s)
        trafficListener?.invoke(s)
    }

    companion object {
        private const val TAG = "Elm327"
        const val DEFAULT_TIMEOUT_MS = 2_000L
        const val RESET_TIMEOUT_MS = 4_000L

        /** ELM status messages that mean "no usable answer". */
        val ERROR_REPLIES = listOf(
            "NO DATA", "?", "CAN ERROR", "BUS ERROR", "BUS BUSY", "UNABLE TO CONNECT",
            "STOPPED", "ERROR", "FB ERROR", "DATA ERROR", "BUFFER FULL", "<RX ERROR", "LV RESET",
        )
    }
}
