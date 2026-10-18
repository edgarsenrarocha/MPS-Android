package com.mpsdiag

import com.mpsdiag.transport.Transport

/**
 * Simulated ELM327 + vehicle, for testing the protocol code without a car.
 *
 * [ecu] receives (request CAN id, request bytes) and returns the reply messages the module
 * would send (unframed payloads), or null for silence. If the first reply is a
 * "response pending" (7F xx 78), only the pending replies are printed for the command and
 * the rest are printed once the app starts monitoring (ATMA), like a real adapter.
 */
class FakeElm(private val ecu: (Int, ByteArray) -> List<ByteArray>?) : Transport() {

    override val displayName = "Fake ELM327"

    /** Diagnostic requests seen, as "TX: HEX" (e.g. "7E0: 3050 07FF"). */
    val requests = mutableListOf<String>()

    private var echo = true
    private var headers = false
    private var tx = 0x7DF
    private var rx = 0x7E8
    private var monitoring = false
    private var heldBack: List<ByteArray> = emptyList()
    private val line = StringBuilder()

    override fun open() {
        isOpen = true
    }

    override fun close() {
        signalClosed()
    }

    override fun write(data: ByteArray) {
        for (b in data) {
            val c = b.toInt().toChar()
            if (monitoring) {
                monitoring = false
                send("\r>")
                continue
            }
            if (c == '\r') {
                handle(line.toString())
                line.setLength(0)
            } else {
                line.append(c)
            }
        }
    }

    private fun send(s: String) {
        incoming.offer(s.toByteArray(Charsets.US_ASCII))
    }

    private fun handle(raw: String) {
        val cmd = raw.replace(" ", "").uppercase()
        val out = StringBuilder()
        if (echo) out.append(raw).append('\r')
        when {
            cmd == "ATZ" -> {
                echo = true
                headers = false
                out.append("\r\rELM327 v1.5\r")
            }
            cmd == "ATE0" -> { echo = false; out.append("OK\r") }
            cmd == "ATI" -> out.append("ELM327 v1.5\r")
            cmd == "ATRV" -> out.append("12.6V\r")
            cmd == "ATH1" -> { headers = true; out.append("OK\r") }
            cmd.startsWith("ATSH") -> { tx = cmd.substring(4).toInt(16); out.append("OK\r") }
            cmd.startsWith("ATCRA") -> { rx = cmd.substring(5).toInt(16); out.append("OK\r") }
            cmd == "ATMA" -> {
                monitoring = true
                send(out.toString())
                heldBack.forEach { send(frames(it)) }
                heldBack = emptyList()
                return
            }
            cmd.startsWith("AT") -> out.append("OK\r")
            else -> {
                val req = ByteArray(cmd.length / 2) { cmd.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                requests += "%03X: %s".format(tx, cmd)
                val replies = ecu(tx, req)
                if (replies == null || replies.isEmpty()) {
                    out.append("NO DATA\r")
                } else {
                    val pending = replies.takeWhile { it.size == 3 && it[0] == 0x7F.toByte() && it[2] == 0x78.toByte() }
                    val shown = if (pending.isNotEmpty()) pending else replies
                    heldBack = if (pending.isNotEmpty()) replies.drop(pending.size) else emptyList()
                    shown.forEach { out.append(frames(it)) }
                }
            }
        }
        out.append("\r>")
        send(out.toString())
    }

    /** Formats one message as ISO-TP frames the way an ELM327 prints them with headers on. */
    private fun frames(msg: ByteArray): String {
        val hex = { bytes: List<Byte> -> bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } }
        val id = "%03X".format(rx)
        if (msg.size <= 7) {
            val padded = msg.toList() + List(7 - msg.size) { 0xAA.toByte() }
            return "$id %02X %s\r".format(msg.size, hex(padded))
        }
        val sb = StringBuilder()
        sb.append("$id 1%X %02X %s\r".format(msg.size shr 8, msg.size and 0xFF, hex(msg.take(6))))
        var seq = 1
        var i = 6
        while (i < msg.size) {
            val chunk = msg.drop(i).take(7)
            val padded = chunk + List(7 - chunk.size) { 0xAA.toByte() }
            sb.append("$id 2%X %s\r".format(seq and 0x0F, hex(padded)))
            seq++
            i += 7
        }
        return sb.toString()
    }
}

fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
