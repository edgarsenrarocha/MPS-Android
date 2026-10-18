package com.mpsdiag.diag

import java.io.IOException

/** A diagnostic trouble code such as P252F. */
data class Dtc(val code: String, val status: Status) {
    enum class Status(val label: String) {
        CURRENT("current"),
        PENDING("pending"),
        STORED("stored"),
    }
}

object DtcService {

    /** Reads stored trouble codes from [module]. */
    @Throws(IOException::class)
    fun read(diag: Diag, module: Module): List<Dtc> {
        diag.open(module)
        if (module.obd) {
            val current = parseObd(diag.request(0x03), Dtc.Status.CURRENT)
            val pending = parseObd(diag.request(0x07), Dtc.Status.PENDING)
            return (current + pending).distinctBy { it.code }
        }
        // ReadDiagnosticTroubleCodesByStatus: status 00, group FF00 (all groups)
        return parseKwp(diag.request(0x18, 0x00, 0xFF, 0x00))
    }

    /** Clears trouble codes in [module]. Throws if the module refuses. */
    @Throws(IOException::class)
    fun clear(diag: Diag, module: Module) {
        diag.open(module)
        if (module.obd) {
            diag.request(0x04)
        } else {
            // ClearDiagnosticInformation, group FF00 (all)
            diag.request(0x14, 0xFF, 0x00, timeoutMs = 5_000)
        }
    }

    /** `58 N [hi lo status] * N` */
    fun parseKwp(r: ByteArray): List<Dtc> {
        val count = r.u8(1)
        return (0 until count).mapNotNull { i ->
            val at = 2 + i * 3
            if (at + 2 >= r.size) return@mapNotNull null
            val status = when (r.u8(at + 2)) {
                0xE0 -> Dtc.Status.CURRENT
                0x20 -> Dtc.Status.PENDING
                else -> Dtc.Status.STORED
            }
            Dtc(format(r.u16(at)), status)
        }
    }

    /** `43|47 N [hi lo] * N` (CAN OBD-II includes the count byte). */
    fun parseObd(r: ByteArray, status: Dtc.Status): List<Dtc> {
        val count = r.u8(1)
        return (0 until count).mapNotNull { i ->
            val at = 2 + i * 2
            if (at + 1 >= r.size) return@mapNotNull null
            val raw = r.u16(at)
            if (raw == 0) null else Dtc(format(raw), status)
        }
    }

    /** SAE J2012 two-byte format: top two bits select P/C/B/U. */
    fun format(raw: Int): String {
        val letter = "PCBU"[(raw shr 14) and 0x03]
        val digit = (raw shr 12) and 0x03
        return "%c%d%03X".format(letter, digit, raw and 0x0FFF)
    }

    /** Short explanations for the codes this app's services are about. */
    val KNOWN = mapOf(
        "P252F" to "Engine oil level too high (oil dilution) — run the oil service reset after an oil change",
        "P2002" to "DPF efficiency below threshold",
        "P2463" to "DPF soot accumulation",
        "P242F" to "DPF ash accumulation",
        "P2452" to "DPF differential pressure sensor circuit",
        "P2453" to "DPF differential pressure sensor range/performance",
    )
}
