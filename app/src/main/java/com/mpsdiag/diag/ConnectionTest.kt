package com.mpsdiag.diag

import java.io.IOException

/**
 * Step-by-step check of the whole chain: phone → adapter → car bus → engine ECU.
 * Each step reports as it finishes, so the screen can show progress.
 */
object ConnectionTest {

    private fun <T> optional(block: () -> T): T? = try {
        block()
    } catch (_: IOException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    /** Firmware id: bytes 11–20 of the `5A 9C` reply, as text when printable. */
    fun firmwareId(r: ByteArray): String? {
        if (r.size < 21) return null
        val raw = r.copyOfRange(11, 21)
        val printable = raw.all { it in 0x20..0x7E }
        return if (printable) String(raw, Charsets.US_ASCII).trim() else raw.toHex(" ")
    }

    enum class Outcome { OK, WARN, FAIL }

    data class Step(val title: String, val outcome: Outcome, val detail: String)

    fun run(diag: Diag, report: (Step) -> Unit) {
        val elm = diag.elm

        // 1. Adapter answers and identifies itself.
        val id = try {
            elm.command("ATI").lastOrNull().orEmpty()
        } catch (e: IOException) {
            report(Step("Adapter", Outcome.FAIL, e.message ?: "no answer"))
            return
        }
        if (id.isEmpty()) {
            report(Step("Adapter", Outcome.FAIL, "No answer to ATI — is this an ELM327 adapter?"))
            return
        }
        report(Step("Adapter", Outcome.OK, id))

        // 2. Supply voltage seen by the adapter (pin 16 of the OBD socket).
        val volts = try {
            elm.command("ATRV").lastOrNull()?.trim().orEmpty()
        } catch (_: IOException) {
            ""
        }
        val v = volts.removeSuffix("V").removeSuffix("v").toDoubleOrNull()
        report(
            when {
                v == null -> Step("Battery voltage", Outcome.WARN, "Adapter did not report voltage")
                v < 11.8 -> Step("Battery voltage", Outcome.WARN, "%.1f V — low; charge the battery or start the engine".format(v))
                v > 13.2 -> Step("Battery voltage", Outcome.OK, "%.1f V (engine running)".format(v))
                else -> Step("Battery voltage", Outcome.OK, "%.1f V".format(v))
            },
        )

        // 3. The car answers a standard OBD-II request on CAN.
        try {
            diag.select(Modules.OBD2)
            diag.request(0x01, 0x00)
            report(Step("Car (CAN bus)", Outcome.OK, "Answering on ISO 15765-4 CAN 500 kbit/s"))
        } catch (e: IOException) {
            report(Step("Car (CAN bus)", Outcome.FAIL, "${e.message}. Is the ignition ON and the adapter fully plugged in?"))
            return
        }

        // 4. Engine ECU accepts the Mitsubishi diagnostic session; VIN and DPF support.
        try {
            val dpfSupported = Dpf.openEngine(diag)
            val vin = try {
                Dpf.readVin(diag)
            } catch (_: IOException) {
                null
            }
            val engine = Dpf.engineFromVin(vin)
            val firmware = optional { firmwareId(diag.request(0x1A, 0x9C)) }
            val odometer = optional { diag.request(0x21, 0x03).u24(3) }
            val detail = buildString {
                append("Diagnostic session open")
                if (vin != null) append("\nVIN ").append(vin)
                if (engine != null) append("\nEngine ").append(engine.label)
                if (firmware != null) append("\nECU firmware ").append(firmware)
                if (odometer != null) append("\nOdometer (ECU) ").append(odometer).append(" km")
                append(if (dpfSupported) "\nDPF functions available" else "\nDPF functions not supported by this ECU")
            }
            report(Step("Engine ECU", if (dpfSupported) Outcome.OK else Outcome.WARN, detail))
        } catch (e: IOException) {
            report(Step("Engine ECU", Outcome.FAIL, e.message ?: "no answer"))
        }
    }
}
