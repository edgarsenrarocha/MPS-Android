package com.mpsdiag.diag

import java.io.IOException

/**
 * Step-by-step check of the whole chain: phone → adapter → car bus → engine ECU.
 * Each step reports as it finishes, so the screen can show progress.
 */
object ConnectionTest {

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
            val detail = buildString {
                append("Diagnostic session open")
                if (vin != null) append("\nVIN ").append(vin)
                if (engine != null) append("\nEngine ").append(engine.label)
                append(if (dpfSupported) "\nDPF functions available" else "\nDPF functions not supported by this ECU")
            }
            report(Step("Engine ECU", if (dpfSupported) Outcome.OK else Outcome.WARN, detail))
        } catch (e: IOException) {
            report(Step("Engine ECU", Outcome.FAIL, e.message ?: "no answer"))
        }
    }
}
