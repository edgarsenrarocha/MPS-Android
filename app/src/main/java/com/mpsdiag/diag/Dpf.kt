package com.mpsdiag.diag

import java.io.IOException
import java.util.Locale

/** One value on a live-data screen, decoded from the reply to `21 <localId>`. */
class LiveParam(
    val label: String,
    val localId: Int,
    private val decoder: (ByteArray) -> String,
) {
    /** Decodes [r] (a `61 <localId> ...` reply); "—" if the reply is too short. */
    fun decode(r: ByteArray): String = try {
        decoder(r)
    } catch (_: IndexOutOfBoundsException) {
        "—"
    }
}

/** Diesel engines with different forced-regeneration commands. */
enum class DieselEngine(val label: String, val regenerationId: Int) {
    E4N15("4N15 (2.4 L diesel)", 0x50),
    E4N14("4N14 (2.2 L diesel)", 0x60),
}

/** DPF service resets available in the engine ECU. */
enum class DpfService(val label: String, val localId: Int) {
    DPF_REPLACED("DPF replaced", 0x51),
    PRESSURE_SENSOR_REPLACED("DPF differential pressure sensor replaced", 0x52),
    MALFUNCTION_SERVICE("DPF system malfunction service", 0x53),
}

object Dpf {

    private fun f2(v: Double) = String.format(Locale.UK, "%.2f", v)
    private fun onOff(b: Boolean) = if (b) "On" else "Off"
    private fun regenMode(v: Int) = when (v and 0x07) {
        0 -> "Not requested"
        1 -> "Distance 1"
        2 -> "Distance 2"
        3 -> "Summation"
        4 -> "Pressure difference"
        5 -> "HC purge"
        else -> "Unknown (${v and 0x07})"
    }

    /** Live values from the engine ECU, in display order. */
    val PARAMS: List<LiveParam> = listOf(
        LiveParam("Regeneration request mode", 0x12) { regenMode(it.u8(6)) },
        LiveParam("DPF regeneration operating", 0x59) { onOff(it.u8(2) and 0x80 != 0) },
        LiveParam("DPF regeneration request", 0x59) { onOff(it.u8(2) and 0x01 != 0) },
        LiveParam("Regeneration time remaining", 0x58) { "${it.u16(2)} s" },
        LiveParam("DPF temperature sensor", 0x10) { "${it.u8(4) * 5 - 200} °C" },
        LiveParam("DPF differential pressure", 0x10) { "${f2(it.u8(5) * 0.5 - 10)} kPa" },
        LiveParam("DPF temperature", 0x16) { "${it.u8(6) * 10 - 400} °C" },
        LiveParam("DPF temperature max", 0x12) { "${it.u8(3) * 5 - 200} °C" },
        LiveParam("Regeneration count", 0x12) { "${it.u16(4)}" },
        LiveParam("Soot loading by distance 1", 0x11) { "${it.u8(3) * 10} km" },
        LiveParam("Soot loading by distance 2", 0x11) { "${it.u8(6) * 10} km" },
        LiveParam("Soot loading by summation", 0x11) { "${f2(it.u8(4) * 0.05)} g/L" },
        LiveParam("Soot loading by pressure diff.", 0x11) { "${f2(it.u8(5) * 0.05)} g/L" },
        LiveParam("Soot loading by summation 2", 0x19) { "${f2(it.u8(5) * 0.1)} g/L" },
        LiveParam("Soot loading by pressure diff. 2", 0x19) { "${f2(it.u8(6) * 0.1)} g/L" },
        LiveParam("Last regeneration mode", 0x13) { regenMode(it.u8(6)) },
        LiveParam("Odometer at DPF (ECU)", 0x15) { "${it.u24(2)} km" },
        LiveParam("Non-uniform soot accumulation", 0x15) { "${it.u8(5)} g" },
        LiveParam("Max DPF temperature", 0x15) { "${it.u8(6) * 10 - 400} °C" },
        LiveParam("Odometer at DPF temperature max", 0x5D) { "${it.u24(2)} km" },
        LiveParam("Regeneration start distance 1", 0x16) { "${it.u8(2) * 10} km" },
        LiveParam("Regeneration start distance 2", 0x16) { "${it.u8(5) * 10} km" },
        LiveParam("Regeneration start summation", 0x16) { "${f2(it.u8(3) * 0.05)} g/L" },
        LiveParam("Regeneration start pressure diff.", 0x16) { "${f2(it.u8(4) * 0.05)} g/L" },
        LiveParam("Pre-regeneration remaining soot", 0x17) { "${f2(it.u8(5) * 0.05)} g" },
        LiveParam("Pre-regeneration soot", 0x17) { "${f2(it.u8(6) * 0.05)} g" },
        LiveParam("Short-interval regeneration rate", 0x19) { "${it.u8(4)}" },
        LiveParam("Re-regeneration request flag", 0x15) { "${it.u8(7)}" },
        LiveParam("Re-regeneration request count", 0x16) { "${it.u8(7)}" },
        LiveParam("Last regeneration time", 0x1F) { "${it.u8(2)} min" },
        LiveParam("Regeneration time 2 times before", 0x1F) { "${it.u8(3)} min" },
        LiveParam("DPF warning light", 0x4E) { onOff(it.u8(6) and 0x04 != 0) },
        LiveParam("DPF warning light (yellow)", 0x4E) { onOff(it.u8(6) and 0x01 != 0) },
    )

    /**
     * Reads every local id used by [params] once. Values whose request failed are "—".
     * Throws [NoResponseException] only if the ECU answered nothing at all.
     */
    @Throws(IOException::class)
    fun poll(diag: Diag, params: List<LiveParam>): List<String> {
        val replies = mutableMapOf<Int, ByteArray?>()
        var lastError: IOException? = null
        for (id in params.map { it.localId }.distinct()) {
            replies[id] = try {
                diag.request(0x21, id)
            } catch (e: NoResponseException) {
                lastError = e
                null
            } catch (e: NegativeResponseException) {
                null
            }
        }
        if (replies.values.all { it == null } && lastError != null) throw lastError!!
        return params.map { p -> replies[p.localId]?.let { p.decode(it) } ?: "—" }
    }

    /**
     * Opens the engine ECU and checks whether it supports the DPF routines.
     * ECUs that identify as 04 25 00 07 do not.
     */
    @Throws(IOException::class)
    fun openEngine(diag: Diag): Boolean {
        diag.open(Modules.ENGINE)
        val id = try {
            diag.request(0x1A, 0x9C)
        } catch (_: NegativeResponseException) {
            return true
        }
        val unsupported = id.size >= 7 && id.u8(3) == 0x04 && id.u8(4) == 0x25 && id.u8(5) == 0x00 && id.u8(6) == 0x07
        return !unsupported
    }

    /** VIN from the engine ECU, or null if it does not report one. */
    @Throws(IOException::class)
    fun readVin(diag: Diag): String? {
        diag.open(Modules.ENGINE)
        val r = try {
            diag.request(0x1A, 0x90)
        } catch (_: NegativeResponseException) {
            return null
        }
        val vin = String(r.copyOfRange(2, r.size), Charsets.US_ASCII).filter { it.isLetterOrDigit() }
        return vin.takeIf { it.length == 17 }
    }

    /** Guesses the engine from the VIN (characters 6–9), or null if unknown. */
    fun engineFromVin(vin: String?): DieselEngine? {
        if (vin == null || vin.length < 9) return null
        val model = vin.substring(5, 9)
        return when {
            listOf("KS1", "KS5", "KR1", "KR5", "KL1", "KL5").any { model.startsWith(it) } -> DieselEngine.E4N15
            model == "CV1W" || model == "CW1W" -> DieselEngine.E4N14
            else -> null
        }
    }

    /** Asks the engine ECU to start a forced (service) regeneration. Sent once. */
    @Throws(IOException::class)
    fun startRegeneration(diag: Diag, engine: DieselEngine) {
        diag.open(Modules.ENGINE)
        diag.request(0x30, engine.regenerationId, 0x07, 0xFF)
    }

    /** Runs a DPF service reset (after DPF / sensor replacement or repair). Sent once. */
    @Throws(IOException::class)
    fun serviceReset(diag: Diag, service: DpfService) {
        diag.open(Modules.ENGINE)
        diag.request(0x30, service.localId, 0x07, 0x00)
    }
}

object Oil {

    val PARAMS: List<LiveParam> = listOf(
        LiveParam("Estimated engine oil level", 0x17) { "${it.u8(2) * 10 - 1000} cc" },
        LiveParam("Estimated oil consumption", 0x17) { "${it.u8(3) * 10 - 1000} cc" },
        LiveParam("Fuel dilution (ECU calculated)", 0x17) { "${it.u8(4) * 10 - 1000} cc" },
    )

    /** Resets the ECU's oil dilution / oil change learning (clears P252F after an oil change). */
    @Throws(IOException::class)
    fun reset(diag: Diag) {
        diag.open(Modules.ENGINE)
        diag.request(0x30, 0x54, 0x07, 0x00)
    }
}
