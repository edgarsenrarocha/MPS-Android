package com.mpsdiag.diag

import com.mpsdiag.diag.Live.f1
import com.mpsdiag.diag.Live.f2

/** Generic OBD-II (SAE J1979 mode 01) values, supported by every car since ~2006. */
object ObdScreen {

    /** Mode 01 PID; the reply is `41 <pid> A B ...`, so A is byte 2. */
    private fun pid(label: String, pid: Int, decoder: (ByteArray) -> String) =
        LiveParam(label, intArrayOf(0x01, pid), decoder)

    private fun pct(a: Int) = "${f1(a * 100.0 / 255)} %"

    val SCREEN = LiveScreen(
        id = "obd",
        title = "OBD-II live data",
        subtitle = "Standard engine values (any make)",
        group = Screens.GROUP_ENGINE,
        module = Modules.OBD2,
        params = listOf(
            pid("Engine speed", 0x0C) { "${it.u16(2) / 4} rpm" },
            pid("Vehicle speed", 0x0D) { "${it.u8(2)} km/h" },
            pid("Coolant temperature", 0x05) { "${it.u8(2) - 40} °C" },
            pid("Intake air temperature", 0x0F) { "${it.u8(2) - 40} °C" },
            pid("Engine load", 0x04) { pct(it.u8(2)) },
            pid("Throttle position", 0x11) { pct(it.u8(2)) },
            pid("Accelerator pedal D", 0x49) { pct(it.u8(2)) },
            pid("Intake manifold pressure", 0x0B) { "${it.u8(2)} kPa" },
            pid("Barometric pressure", 0x33) { "${it.u8(2)} kPa" },
            pid("Mass air flow", 0x10) { "${f2(it.u16(2) / 100.0)} g/s" },
            pid("Fuel rail pressure", 0x23) { "${it.u16(2) * 10} kPa" },
            pid("Commanded EGR", 0x2C) { pct(it.u8(2)) },
            pid("Fuel level", 0x2F) { pct(it.u8(2)) },
            pid("Engine fuel rate", 0x5E) { "${f2(it.u16(2) / 20.0)} L/h" },
            pid("Engine oil temperature", 0x5C) { "${it.u8(2) - 40} °C" },
            pid("Ambient air temperature", 0x46) { "${it.u8(2) - 40} °C" },
            pid("Control module voltage", 0x42) { "${f2(it.u16(2) / 1000.0)} V" },
            pid("Timing advance", 0x0E) { "${f1(it.u8(2) / 2.0 - 64)} °" },
            pid("Short-term fuel trim", 0x06) { "${f1((it.u8(2) - 128) * 100.0 / 128)} %" },
            pid("Long-term fuel trim", 0x07) { "${f1((it.u8(2) - 128) * 100.0 / 128)} %" },
            pid("Run time since start", 0x1F) { "${it.u16(2)} s" },
            pid("Distance with MIL on", 0x21) { "${it.u16(2)} km" },
            pid("Distance since codes cleared", 0x31) { "${it.u16(2)} km" },
        ),
        note = "Values the car does not support show \"—\". Use \"Choose values\" in the menu to show fewer values and refresh faster.",
    )
}
