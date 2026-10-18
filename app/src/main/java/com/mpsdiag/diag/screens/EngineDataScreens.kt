package com.mpsdiag.diag.screens

import com.mpsdiag.diag.Diag
import com.mpsdiag.diag.Dpf
import com.mpsdiag.diag.Live.bit
import com.mpsdiag.diag.Live.f0
import com.mpsdiag.diag.Live.f1
import com.mpsdiag.diag.Live.f2
import com.mpsdiag.diag.Live.f3
import com.mpsdiag.diag.Live.s8
import com.mpsdiag.diag.LiveParam
import com.mpsdiag.diag.LiveScreen
import com.mpsdiag.diag.Modules
import com.mpsdiag.diag.Screens
import com.mpsdiag.diag.Variant
import com.mpsdiag.diag.u16
import com.mpsdiag.diag.u24
import com.mpsdiag.diag.u8
import java.io.IOException
import java.util.Locale

/** Engine live-data screens: diesel (4D56/4M41/4N14/4N15), petrol 6B31, Eclipse Cross, Xpander. */
object EngineDataScreens {

    private fun p(label: String, localId: Int, decoder: (ByteArray) -> String) = LiveParam(label, localId, decoder)

    /** A `21 <localId>` value read from the transmission ECU (7E1/7E9). */
    private fun tcm(label: String, localId: Int, decoder: (ByteArray) -> String) =
        LiveParam(label, localId, Modules.TRANSMISSION, decoder)

    private fun f4(v: Double) = String.format(Locale.UK, "%.4f", v)

    // ---- Diesel (4D56 / 4M41 / 4N14 / 4N15) ---------------------------------------------

    /** Diesel values; only "AT temperature" differs between engine families. */
    private fun dieselParams(atTemperature: (ByteArray) -> String): List<LiveParam> = listOf(
        p("Engine speed", 0x44) { "${it.u16(4)} rpm" },
        p("Target idle speed", 0x01) { "${f0(it.u8(5) * 1594.0 / 255)} rpm" },
        p("Vehicle speed", 0x01) { "${it.u8(3)} km/h" },
        p("Boost pressure", 0x02) { "${f1(it.u8(4) * 340.0 / 255)} kPa" },
        p("Battery voltage", 0x01) { "${f1(it.u8(2) * 18.7 / 255)} V" },
        p("Coolant temperature", 0x02) { "${it.u8(3) - 40} °C" },
        tcm("AT temperature", 0x02, atTemperature),
        p("Intake air temperature", 0x02) { "${it.u8(5) - 40} °C" },
        p("Fuel quantity final", 0x4A) { "${f1(it.u8(2) * 0.5)} mm³/st" },
        p("Fuel quantity pre", 0x4A) { "${f1(it.u8(5) * 0.1)} mm³/st" },
        p("Fuel quantity smoke limit", 0x4A) { "${f1(it.u8(4) * 0.5)} mm³/st" },
        p("Fuel quantity start", 0x4A) { "${f1(it.u8(3) * 0.5)} mm³/st" },
        p("Pilot injection interval", 0x4B) { "${it.u8(3) * 10} µs" },
        p("Main injection timing", 0x4B) { "${s8(it, 2)} °" },
        p("Fuel pump learning state", 0x4C) { "${it.u8(6)}" },
        p("Fuel pump learned value", 0x4C) { "${it.u8(5) * 10 - 1280} mA" },
        p("Fuel pump preset value", 0x4C) { "${it.u8(4) * 25} mA" },
        p("Fuel pressure preset", 0x4C) { "${it.u8(2)} MPa" },
        p("Fuel pressure", 0x4C) { "${it.u8(3)} MPa" },
        p("Air flow sensor", 0x04) { "${it.u8(6) * 10} mg" },
        p("Swirl control valve", 0x4E) { bit(it, 4, 2) },
        p("Fuel filter switch", 0x4E) { bit(it, 5, 1) },
        p("Engine oil level (calc.)", 0x17) { "${it.u8(2) * 10 - 1000} cc" },
        p("Engine oil consumption (calc.)", 0x17) { "${it.u8(3) * 10 - 1000} cc" },
        p("Fuel dilution (ECU calc.)", 0x17) { "${it.u8(4) * 10 - 1000} cc" },
        p("Main fuel injection", 0x51) { "${f1(it.u8(2) * 0.5)} mm³/st" },
        p("After injection", 0x51) { "${f1(it.u8(3) * 0.1)} mm³/st" },
        p("Post injection 1", 0x51) { "${f1(it.u8(4) * 0.1)} mm³/st" },
        p("Post injection 2", 0x51) { "${f1(it.u8(5) * 0.1)} mm³/st" },
        p("Post injection 3", 0x51) { "${f1(it.u8(6) * 0.1)} mm³/st" },
        // spec: the original shows no unit; the 0–5 scale looks like a voltage.
        p("Injection output compensation", 0x54) { "${f2(it.u8(2) * 5.0 / 255)} V" },
        p("Pilot injection 1", 0x43) { "${f1(it.u8(2) * 0.1)} mm³/st" },
        p("Pilot injection 2", 0x43) { "${f1(it.u8(3) * 0.1)} mm³/st" },
        p("Pilot injection 3", 0x43) { "${f1(it.u8(4) * 0.1)} mm³/st" },
        p("Target EGR valve position", 0x47) { "${f2(it.u8(2) * 99.61 / 255)} %" },
        p("EGR valve position", 0x47) { "${f2(it.u8(3) * 99.61 / 255)} %" },
        p("Odometer since last SQL", 0xC0) { "${it.u16(2)} km" },
        p("Odometer at last SQL or injector change", 0xC0) { "${it.u24(4)} km" },
        p("Odometer last to 2nd-last SQL", 0xC0) { "${it.u16(7)} km" },
        p("Odometer 2nd-last to 3rd-last SQL", 0xC0) { "${it.u16(9)} km" },
        p("Odometer 3rd-last to 4th-last SQL", 0xC0) { "${it.u16(11)} km" },
        p("SQL counter (automatic)", 0xC0) { "${it.u8(13)}" },
        p("SQL counter (manual)", 0xC0) { "${it.u8(14)}" },
        p("Working cylinder sequence number", 0x42) { "${it.u8(6)} cyl" },
        p("SQL condition", 0xC0) { bit(it, 15, 7) },
        p("SQL coolant temperature condition", 0xC0) { bit(it, 15, 6) },
        p("SQL fuel temperature condition", 0xC0) { bit(it, 15, 5) },
        p("SQL AT oil temperature condition", 0xC0) { bit(it, 15, 4) },
        p("VGT target position", 0x4F) { "${it.u8(2)} %" },
        p("VGT solenoid duty before correction", 0x4F) { "${it.u8(3)} %" },
        p("VGT solenoid duty after correction", 0x4F) { "${it.u8(4)} %" },
        p("VGT feedback target boost", 0x4F) { "${f2(it.u8(5) * 340.0 / 255)} kPa" },
        p("Glow real duty", 0xA8) { "${it.u8(2)} %" },
        p("ISC torque", 0xBE) { "${f2(it.u16(2) * 0.1)} Nm" },
        p("Final torque target", 0x1E) { "${f2(it.u16(5) * 0.01 - 150)} Nm" },
        p("Brake override operated odometer", 0x1E) { "${it.u24(2)} km" },
        p("Cruise cancel code", 0x14) { "%02X".format(it.u8(3)) },
        p("Cruise switch", 0x14) { "${f2(it.u8(2) * 4.98 / 255)} V" },
        p("Alternator FR duty", 0x46) { "${it.u8(2)} %" },
        p("Alternator G duty", 0x46) { "${it.u8(3)} %" },
        p("Battery temperature sensor", 0x46) { "${it.u8(4) - 40} °C" },
        p("Battery current sensor (learned)", 0x24) { "${f4(it.u8(2) * 12.444 / 255 - 6.1976)} A" },
        p("DPF regeneration operating", 0x59) { bit(it, 2, 7) },
        p("DPF regeneration request", 0x59) { bit(it, 2, 0) },
        p("4L mode", 0x59) { bit(it, 2, 2) },
        p("Water level switch", 0x59) { bit(it, 3, 7) },
        p("Oil pressure switch", 0x59) { bit(it, 3, 6) },
        p("ASLF main switch", 0x59) { bit(it, 4, 5) },
        p("Battery current sensor calibration", 0x59) { if (it.u8(3) shr 5 and 1 == 1) "Completed" else "Not completed" },
        p("Battery degradation flag", 0x59) { bit(it, 3, 3) },
        p("Battery full-charge mode", 0x59) { bit(it, 3, 2) },
        LiveParam("Outside temperature", intArrayOf(0x01, 0x46)) { "${it.u8(2) - 40} °C" },
        p("Fuel temperature", 0x49) { "${it.u8(6) - 40} °C" },
        p("AT/MT switch", 0x4E) { if (it.u8(2) shr 1 and 1 == 1) "AT" else "MT" },
        p("Condenser fan relay", 0x4E) { bit(it, 5, 4) },
        p("A/C switch 1", 0x4E) { bit(it, 5, 6) },
        p("A/C relay", 0x4E) { bit(it, 5, 7) },
        p("Control relay", 0x4E) { bit(it, 2, 4) },
        p("Engine check light", 0x4E) { bit(it, 2, 3) },
        p("Glow light", 0x4E) { bit(it, 4, 6) },
        p("Glow plug relay", 0x4E) { bit(it, 4, 7) },
        p("Ignition switch", 0x4E) { bit(it, 2, 7) },
        p("Mileage", 0x03) { "${it.u24(3)} km" },
        // spec: raw 24-bit counter; the original labels it "L" but the real unit is unknown.
        p("Total fuel consumed (raw)", 0x5F) { "${it.u24(2)}" },
        p("Injector code changes (cyl 1/2/3/4)", 0xFA) { "${it.u8(3)} / ${it.u8(4)} / ${it.u8(5)} / ${it.u8(6)}" },
    )

    private val DIESEL_VARIANTS = listOf(
        Variant("4D56 / 4M41 / L200", dieselParams { "${it.u8(7) - 40} °C" }),
        Variant("4N15", dieselParams { "${it.u8(7) - 50} °C" }),
        Variant("4N14", dieselParams { "${it.u8(8) - 50} °C" }),
    )

    /** Diesel variant from the VIN (characters 6–9), or null if the VIN is unknown. */
    fun dieselVariantFromVin(vin: String?): Int? {
        if (vin == null || vin.length < 9) return null
        val c = vin.substring(5, 9)
        return when {
            c.startsWith("KH4") || c.startsWith("KH8") || c.startsWith("KB4") -> 0
            c[0] == 'V' && (c[1] == '9' || c[1] == '8') && c[2] == '8' -> 0
            c[0] == 'K' && c[1] in "SRL" && c[2] in "15" -> 1
            c == "CV1W" || c == "CW1W" -> 2
            else -> null
        }
    }

    private val detectDiesel: (Diag) -> Int? = { d ->
        val vin = try {
            Dpf.readVin(d)
        } catch (_: IOException) {
            null
        }
        dieselVariantFromVin(vin)
    }

    val DIESEL = LiveScreen(
        id = "diesel",
        title = "Diesel engine data (4D56/4M41/4N14/4N15)",
        subtitle = "Injection, boost, EGR, VGT, SQL counters, switches",
        group = Screens.GROUP_ENGINE,
        module = Modules.ENGINE,
        variants = DIESEL_VARIANTS,
        detectVariant = detectDiesel,
        useSession = false,
        requestDelayMs = 10,
        note = "The engine type is detected from the VIN; change it with \"Choose type\" in the menu. " +
            "Use \"Choose values\" to show fewer values and refresh faster.",
    )

    // ---- Petrol 6B31 --------------------------------------------------------------------

    val PETROL = LiveScreen(
        id = "petrol",
        title = "Petrol engine data (6B31)",
        subtitle = "3.0 V6: sensors, fuel trims, knock, ISC, relays",
        group = Screens.GROUP_ENGINE,
        module = Modules.ENGINE,
        useSession = false,
        params = listOf(
            p("Engine speed", 0x01) { "${f0(it.u16(4) * 8000.0 / 65535)} rpm" },
            p("Target idle speed", 0x01) { "${f0(it.u8(6) * 1992.2 / 255)} rpm" },
            p("Vehicle speed", 0x01) { "${it.u8(3) * 2} km/h" },
            p("Battery voltage", 0x01) { "${f1(it.u8(2) * 18.7 / 255)} V" },
            p("Coolant temperature", 0x02) { "${it.u8(3) - 40} °C" },
            p("Intake air temperature", 0x02) { "${it.u8(2) - 40} °C" },
            tcm("AT temperature", 0x02) { "${it.u8(7) - 40} °C" },
            p("MAP sensor", 0x03) { "${f1(it.u16(2) * 126.7 / 65535)} kPa" },
            // spec: the original labels this "Short term fuel trim 1", but it is a sensor voltage.
            p("Sensor voltage (21 03, byte 4)", 0x03) { "${f1(it.u8(4) * 4980.5 / 255)} mV" },
            p("APS (main)", 0x04) { "${f1(it.u8(5) * 4980.5 / 255)} mV" },
            p("APS (sub)", 0x04) { "${f1(it.u8(6) * 4980.5 / 255)} mV" },
            p("TPS", 0x04) { "${f1(it.u8(2) * 4980.5 / 255)} mV" },
            p("TPS (main) learned value", 0x04) { "${f1(it.u8(4) * 4980.5 / 255)} mV" },
            p("TPS (sub)", 0x04) { "${f1(it.u8(3) * 4980.5 / 255)} mV" },
            p("Ignition advance", 0x05) { "${it.u8(2) - 20} °CA" },
            p("Injector pulse", 0x05) { "${f1(it.u8(3) * 65.3 / 255)} ms" },
            p("Long-term fuel trim 1", 0x07) { "${f1(it.u8(4) * 99.4 / 255 - 49.9)} %" },
            p("Long-term fuel trim 2", 0x07) { "${f1(it.u8(6) * 99.4 / 255 - 49.9)} %" },
            p("Short-term fuel trim 1", 0x07) { "${f1(it.u8(3) * 99.4 / 255 - 49.9)} %" },
            p("Short-term fuel trim 2", 0x07) { "${f1(it.u8(5) * 99.4 / 255 - 49.9)} %" },
            p("Knock retard", 0x08) { "${f1(it.u8(3) * 89.6 / 255)} °CA" },
            p("Learned knock retard", 0x08) { "${f1(it.u8(4) * 100.0 / 255)} %" },
            p("Purge control solenoid duty", 0x0F) { "${f1(it.u8(2) * 100.0 / 255)} %" },
            p("Throttle actuator", 0x12) { "${f1(it.u8(4) * 100.0 / 255)} %" },
            p("Target ETV value", 0x12) { "${f1(it.u16(2) * 80.0 / 65535)} V" },
            p("ISC learned value (A/C off)", 0x18) { "${f1(it.u16(2) * 256.0 / 65535 - 128)} L/s" },
            p("ISC learned value (A/C on)", 0x18) { "${f1(it.u16(4) * 256.0 / 65535 - 128)} L/s" },
            p("Absolute load value", 0x1A) { "${f1(it.u8(3) * 100.0 / 255)} %" },
            p("Calculated load value", 0x1A) { "${f1(it.u8(2) * 100.0 / 255)} %" },
            p("Ignition advance 2", 0x09) { "${it.u8(4) - 20} °CA" },
            p("Knock retard 2", 0x09) { "${f1(it.u8(5) * 89.6 / 255)} °CA" },
            p("Learned knock retard 2", 0x09) { "${f1(it.u8(6) * 100.0 / 255)} %" },
            p("Engine control relay", 0x1E) { bit(it, 2, 5) },
            p("ETV relay", 0x1E) { bit(it, 2, 2) },
            p("Fuel pump relay", 0x1E) { bit(it, 2, 1) },
            p("Oil control valve 1", 0x1E) { bit(it, 3, 2) },
            p("Variable intake solenoid", 0x1E) { bit(it, 3, 1) },
        ),
        note = "Use \"Choose values\" in the menu to show fewer values and refresh faster.",
    )

    // ---- Eclipse Cross 4B40 -------------------------------------------------------------

    val CROSS = LiveScreen(
        id = "cross",
        title = "Eclipse Cross engine data (4B40)",
        subtitle = "1.5 turbo petrol: sensors, trims, ISC, VVT",
        group = Screens.GROUP_ENGINE,
        module = Modules.ENGINE,
        params = listOf(
            p("Engine speed", 0x01) { "${f0(it.u16(4) * 8000.0 / 65535)} rpm" },
            p("Target engine speed", 0x01) { "${f0(it.u8(6) * 1992.19 / 255)} rpm" },
            p("Vehicle speed", 0x01) { "${it.u8(3) * 2} km/h" },
            p("Coolant temperature", 0x02) { "${it.u8(3) - 40} °C" },
            p("Supply voltage", 0x01) { "${f1(it.u8(2) * 18.7 / 255)} V" },
            p("Intake air temperature", 0x02) { "${it.u8(2) - 40} °C" },
            p("Battery temperature", 0x1B) { "${it.u8(2) - 40} °C" },
            p("Absolute load", 0x1A) { "${f1(it.u8(3) * 100.0 / 255)} %" },
            p("Short-term fuel trim", 0x07) { "${f1(it.u8(3) * 99.45 / 255 - 49.92)} %" },
            p("Long-term fuel trim", 0x07) { "${f1(it.u8(4) * 99.45 / 255 - 49.92)} %" },
            p("Throttle valve", 0x12) { "${f1(it.u8(4) * 100.0 / 255)} %" },
            p("ISC (A/C on)", 0x18) { "${f1(it.u16(4) * 256.0 / 65535 - 128)} L/s" },
            p("ISC (A/C off)", 0x18) { "${f1(it.u16(2) * 256.0 / 65535 - 128)} L/s" },
            // spec: the original shows psi for these learned values; unit kept as implemented.
            p("ISC learned value (A/C on)", 0x2D) { "${f1(it.u16(4) * 3768.2 / 65535 - 1888.13)} psi" },
            p("ISC learned value (A/C off)", 0x2D) { "${f1(it.u16(2) * 3768.2 / 65535 - 1888.13)} psi" },
            p("Calculated load value", 0x1A) { "${f1(it.u8(2) * 100.0 / 255)} %" },
            p("Power steering current", 0x06) { "${f1(it.u8(5) / 2.0)} A" },
            p("Knock retard", 0x08) { "${f1(it.u8(3) * 89.65 / 255)} °CA" },
            p("Learned knock retard", 0x08) { "${f1(it.u8(4) * 100.0 / 255)} %" },
            p("Injector pulse", 0x05) { "${f1(it.u8(3) * 65.3 / 255)} ms" },
            p("Target ETV value", 0x12) { "${f1(it.u16(2) * 80.0 / 65535)} V" },
            p("Ignition advance", 0x05) { "${it.u8(2) - 20} °CA" },
            p("Air flow sensor", 0x03) { "${f1(it.u8(4) * 4980.0 / 255)} mV" },
            p("Wastegate motor duty", 0x0E) { "${f1(it.u8(5) * 200.0 / 255 - 100)} %" },
            p("Intake VVT angle 1", 0x0A) { "${f1(it.u8(4) * 159.4 / 255 - 80)} °CA" },
            p("Exhaust VVT angle 1", 0x0B) { "${f1(it.u8(4) * 159.4 / 255 - 80)} °CA" },
            p("Cooling fan duty", 0x0E) { "${f1(it.u8(2) * 100.0 / 255)} %" },
            p("High-pressure pump spill valve", 0x0F) { "${f1(it.u8(4) * 261.0 / 255)} ms" },
            p("Engine start signal", 0x1D) { bit(it, 2, 6) },
        ),
    )

    // ---- Xpander 4A91 -------------------------------------------------------------------

    val XPANDER = LiveScreen(
        id = "xpander",
        title = "Xpander engine data (4A91)",
        subtitle = "1.5 petrol: sensors, trims, VVT, switches, battery",
        group = Screens.GROUP_ENGINE,
        module = Modules.ENGINE,
        useSession = false,
        requestDelayMs = 10,
        params = listOf(
            p("Power supply voltage", 0x01) { "${f1(it.u8(2) * 18.7 / 255)} V" },
            p("Vehicle speed", 0x01) { "${it.u8(3) * 2} km/h" },
            p("Engine speed", 0x01) { "${f0(it.u16(4) * 8000.0 / 65535)} rpm" },
            p("Target idle speed", 0x01) { "${f0(it.u8(6) * 1992.0 / 255)} rpm" },
            p("Intake air temperature", 0x02) { "${it.u8(2) - 40} °C" },
            p("Coolant temperature", 0x02) { "${it.u8(3) - 40} °C" },
            p("Air flow sensor", 0x03) { "${f1(it.u8(4) * 4980.0 / 255)} mV" },
            p("TPS (main)", 0x04) { "${f1(it.u8(2) * 4980.0 / 255)} mV" },
            p("TPS (sub)", 0x04) { "${f1(it.u8(3) * 4980.0 / 255)} mV" },
            p("TPS (main) learned value", 0x04) { "${f1(it.u8(4) * 4980.0 / 255)} mV" },
            p("APS (main)", 0x04) { "${f1(it.u8(5) * 4980.0 / 255)} mV" },
            p("APS (sub)", 0x04) { "${f1(it.u8(6) * 4980.0 / 255)} mV" },
            p("Ignition advance", 0x05) { "${it.u8(2) - 20} °CA" },
            p("Injector pulse", 0x05) { "${f1(it.u8(3) * 63.5 / 255)} ms" },
            p("Electric power steering current", 0x06) { "${f1(it.u8(5) / 2.0)} A" },
            p("Short-term fuel trim 1", 0x07) { "${f1(it.u8(3) * 99.4 / 255 - 49.9)} %" },
            p("Long-term fuel trim 1", 0x07) { "${f1(it.u8(4) * 99.4 / 255 - 49.9)} %" },
            p("Knock retard", 0x08) { "${f1(it.u8(3) * 89.6 / 255)} °CA" },
            p("Learned knock retard", 0x08) { "${f1(it.u8(4) * 100.0 / 255)} %" },
            p("Intake VVT phase angle 1", 0x0A) { "${f1(it.u8(4) * 159.4 / 255 - 80)} °CA" },
            p("Exhaust VVT phase angle 1", 0x0B) { "${f1(it.u8(4) * 159.4 / 255 - 80)} °CA" },
            p("Cooling fan duty", 0x0E) { "${f1(it.u8(2) * 100.0 / 255)} %" },
            p("Wastegate duty (motor type)", 0x0E) { "${f1(it.u8(5) * 200.0 / 255 - 100)} %" },
            p("Purge control solenoid duty", 0x0F) { "${f1(it.u8(2) * 100.0 / 255)} %" },
            p("High-pressure pump spill valve", 0x0F) { "${f1(it.u8(5) * 261.0 / 255)} ms" },
            p("Target ETV value", 0x12) { "${f3(it.u16(2) * 80.0 / 65535)} V" },
            p("Throttle actuator", 0x12) { "${f1(it.u8(4) * 100.0 / 255)} %" },
            p("Cancel code", 0x12) { "${it.u8(5)}" },
            p("ISC learned value (A/C off)", 0x18) { "${f3(it.u16(2) * 256.0 / 65535 - 128)} L/s" },
            p("ISC learned value (A/C on)", 0x18) { "${f3(it.u16(4) * 256.0 / 65535 - 128)} L/s" },
            p("Calculated load value", 0x1A) { "${f1(it.u8(2) * 100.0 / 255)} %" },
            p("Absolute load value", 0x1A) { "${f1(it.u8(3) * 100.0 / 255)} %" },
            p("A/C switch", 0x1D) { bit(it, 2, 3) },
            p("Cranking signal", 0x1D) { bit(it, 2, 6) },
            p("Idle position signal", 0x1D) { bit(it, 2, 7) },
            p("Ignition switch", 0x1D) { bit(it, 3, 0) },
            p("Cruise control on/off switch", 0x1D) { bit(it, 4, 0) },
            p("Cruise cancel switch", 0x1D) { bit(it, 4, 3) },
            p("Cruise control operating", 0x1D) { bit(it, 4, 4) },
            p("Neutral position switch (cruise)", 0x1D) { bit(it, 4, 6) },
            p("Speed limiter main switch", 0x1D) { bit(it, 4, 7) },
            p("Stop lamp switch", 0x1D) { bit(it, 5, 0) },
            p("Normally closed brake switch", 0x1D) { bit(it, 5, 1) },
            p("A/C compressor relay", 0x1E) { bit(it, 2, 0) },
            p("Fuel pump relay", 0x1E) { bit(it, 2, 1) },
            p("ETV relay", 0x1E) { bit(it, 2, 2) },
            p("Engine control relay", 0x1E) { bit(it, 2, 5) },
            p("Starter relay", 0x1E) { bit(it, 2, 6) },
            p("Vehicle consumption current", 0x28) { "${f1(it.u16(2) * 16000.0 / 65535 - 8000)} A" },
            p("Battery deterioration odometer (last)", 0x29) { "${it.u16(2) * 4} km" },
            p("Battery deterioration odometer (2nd last)", 0x29) { "${it.u16(4) * 4} km" },
            p("Battery deterioration odometer (3rd last)", 0x29) { "${it.u16(6) * 4} km" },
            p("Starter counter", 0x2A) { "${it.u16(5) * 8}" },
            // spec: the original repeats the ISC labels for 21 2D with a kPa scale (meaning unclear).
            p("ISC learned value 2 (A/C off)", 0x2D) { "${f1(it.u16(2) * 25932.4 / 65535 - 12996.4)} kPa" },
            p("ISC learned value 2 (A/C on)", 0x2D) { "${f1(it.u16(4) * 25932.4 / 65535 - 12996.4)} kPa" },
        ),
    )

    val ALL: List<LiveScreen> = listOf(DIESEL, PETROL, CROSS, XPANDER)
}
