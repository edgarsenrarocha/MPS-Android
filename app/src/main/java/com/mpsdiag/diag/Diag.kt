package com.mpsdiag.diag

import com.mpsdiag.elm.Elm327
import java.io.IOException

/** A module that answered with a negative response (7F xx NRC). */
class NegativeResponseException(val service: Int, val code: Int) :
    IOException("Module refused request %02X: %s".format(service, Nrc.describe(code)))

/** The module did not answer (NO DATA / timeout / bus error). */
class NoResponseException(message: String) : IOException(message)

/**
 * Diagnostic request/response over ISO 15765-4 CAN (11-bit, 500 kbit/s) through an ELM327.
 *
 * Headers are kept on (ATH1) so every reply line is "<CAN id> <PCI> <data...>", which lets us
 * reassemble multi-frame (ISO-TP) answers ourselves and ignore traffic from other modules.
 */
class Diag(private val elm: Elm327) {

    var target: Module? = null
        private set

    /** Resets and configures the adapter. Returns the adapter's identification string. */
    @Throws(IOException::class)
    fun initAdapter(): String {
        val version = elm.reset()
        if (version.isEmpty()) throw IOException("Adapter did not answer ATZ — is it an ELM327?")
        elm.at("ATE0")        // echo off
        elm.at("ATL0")        // no linefeeds
        elm.at("ATS1")        // spaces between bytes
        elm.at("ATH1")        // show CAN ids
        elm.at("ATD0")        // no DLC in replies
        elm.at("ATSP6")       // ISO 15765-4 CAN 11-bit 500k
        elm.at("ATAT1")       // adaptive timing
        elm.at("ATST80")      // wait up to ~0.5 s for a module to answer
        target = null
        return version
    }

    /** Points the adapter at [module] (request id, reply filter and flow control). */
    @Throws(IOException::class)
    fun select(module: Module) {
        if (target == module) return
        val tx = "%03X".format(module.requestId)
        val rx = "%03X".format(module.responseId)
        elm.at("ATSH$tx")
        elm.at("ATCRA$rx")
        if (module.customFlowControl) {
            elm.at("ATFCSH$tx")
            elm.at("ATFCSD30080A") // flow control: clear to send, block size 8, 10 ms gap
            elm.at("ATFCSM1")      // use the flow control header/data set above
        } else {
            elm.at("ATFCSM0")      // ELM327 default flow control
        }
        target = module
    }

    /**
     * Selects [module] and opens the Mitsubishi extended diagnostic session (10 92).
     * Generic OBD-II needs no session.
     */
    @Throws(IOException::class)
    fun open(module: Module) {
        select(module)
        if (!module.obd) request(0x10, 0x92)
    }

    /**
     * Sends a raw diagnostic request (service id first, e.g. 21 01) to the selected module and
     * returns the positive response payload (starting with service id + 0x40).
     */
    @Throws(IOException::class)
    fun request(vararg bytes: Int, timeoutMs: Long = Elm327.DEFAULT_TIMEOUT_MS): ByteArray =
        request(ByteArray(bytes.size) { bytes[it].toByte() }, timeoutMs)

    @Throws(IOException::class)
    fun request(payload: ByteArray, timeoutMs: Long = Elm327.DEFAULT_TIMEOUT_MS): ByteArray {
        val module = target ?: throw IOException("No module selected")
        val sid = payload[0].toInt() and 0xFF
        val lines = elm.command(payload.toHex(" "), timeoutMs)
        val frames = parse(lines, module.responseId)
        val answer = frames.lastOrNull { !it.isPending() }
        if (answer != null || frames.none { it.isPending() }) return check(sid, answer, lines)

        // The module said "response pending" (7F xx 78): keep listening for the final answer
        // instead of re-sending, so routines are never started twice.
        val respId = "%03X".format(module.responseId)
        val monitored = elm.monitorUntil(PENDING_TIMEOUT_MS) { line ->
            val msg = parse(listOf(line), module.responseId).firstOrNull()
            line.startsWith(respId, true) && msg != null && !msg.isPending()
        }
        val final = parse(monitored, module.responseId).lastOrNull { !it.isPending() }
            ?: throw NoResponseException("Module answered 'busy' but never finished")
        return check(sid, final)
    }

    private fun ByteArray.isPending() = isNrc() && nrcCode() == Nrc.RESPONSE_PENDING

    private fun check(sid: Int, answer: ByteArray?, lines: List<String> = emptyList()): ByteArray {
        if (answer == null) {
            val reason = lines.firstOrNull { l -> Elm327.ERROR_REPLIES.any { l.contains(it, true) } } ?: "no reply"
            throw NoResponseException("Module did not answer ($reason)")
        }
        if (answer.isNrc()) throw NegativeResponseException(sid, answer.nrcCode())
        if ((answer[0].toInt() and 0xFF) != sid + 0x40) {
            throw IOException("Unexpected reply ${answer.toHex()}")
        }
        return answer
    }

    private fun ByteArray.isNrc() = size >= 3 && (this[0].toInt() and 0xFF) == 0x7F
    private fun ByteArray.nrcCode() = this[2].toInt() and 0xFF

    companion object {
        private const val PENDING_TIMEOUT_MS = 30_000L

        /**
         * Reassembles ISO-TP messages from ELM327 lines with headers on.
         * Only frames from [responseId] are used; each complete message is returned.
         */
        fun parse(lines: List<String>, responseId: Int): List<ByteArray> {
            val messages = mutableListOf<ByteArray>()
            var buffer: ByteArray? = null
            var expected = 0
            var filled = 0
            for (line in lines) {
                val tokens = line.trim().split(Regex("\\s+"))
                if (tokens.size < 2) continue
                val id = tokens[0].toIntOrNull(16) ?: continue
                if (id != responseId) continue
                val data = tokens.drop(1).mapNotNull { t -> t.takeIf { it.length == 2 }?.toIntOrNull(16) }
                if (data.isEmpty()) continue
                val pci = data[0]
                when (pci shr 4) {
                    0x0 -> {
                        val len = pci and 0x0F
                        if (len in 1..data.size - 1) {
                            messages += ByteArray(len) { data[1 + it].toByte() }
                        }
                    }
                    0x1 -> {
                        if (data.size < 2) continue
                        expected = ((pci and 0x0F) shl 8) or data[1]
                        buffer = ByteArray(expected)
                        filled = 0
                        for (b in data.drop(2)) if (filled < expected) buffer[filled++] = b.toByte()
                    }
                    0x2 -> {
                        val buf = buffer ?: continue
                        for (b in data.drop(1)) if (filled < expected) buf[filled++] = b.toByte()
                        if (filled >= expected) {
                            messages += buf
                            buffer = null
                        }
                    }
                }
            }
            return messages
        }
    }
}

fun ByteArray.toHex(separator: String = ""): String = joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }

/** Unsigned byte at [i]. */
fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

/** Unsigned big-endian 16-bit value starting at [i]. */
fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

/** Unsigned big-endian 24-bit value starting at [i]. */
fun ByteArray.u24(i: Int): Int = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)
