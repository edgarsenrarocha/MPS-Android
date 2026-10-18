package com.mpsdiag

import com.mpsdiag.diag.LiveParam
import com.mpsdiag.diag.LiveScreen
import com.mpsdiag.diag.Modules
import com.mpsdiag.diag.screens.EngineDataScreens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineDataScreensTest {

    private fun LiveScreen.param(label: String, variant: Int = 0): LiveParam =
        paramsFor(variant).first { it.label == label }

    private fun decode(screen: LiveScreen, label: String, vararg reply: Int, variant: Int = 0): String =
        screen.param(label, variant).decode(bytes(*reply))

    @Test
    fun idsAndLabelsAreUnique() {
        val screens = EngineDataScreens.ALL
        assertEquals(screens.size, screens.map { it.id }.toSet().size)
        for (s in screens) {
            val lists = if (s.variants.isEmpty()) listOf(s.params) else s.variants.map { it.params }
            for (list in lists) {
                val labels = list.map { it.label }
                assertEquals("duplicate label in ${s.id}", labels.size, labels.toSet().size)
            }
        }
        assertEquals(85, EngineDataScreens.DIESEL.paramsFor(0).size)
        assertEquals(37, EngineDataScreens.PETROL.params.size)
        assertEquals(29, EngineDataScreens.CROSS.params.size)
        assertEquals(55, EngineDataScreens.XPANDER.params.size)
    }

    @Test
    fun diesel() {
        val s = EngineDataScreens.DIESEL
        assertEquals("800 rpm", decode(s, "Engine speed", 0x61, 0x44, 0, 0, 0x03, 0x20))
        val r02 = intArrayOf(0x61, 0x02, 0x00, 0x82, 0xFF)
        assertEquals("340.0 kPa", decode(s, "Boost pressure", *r02))
        assertEquals("90 °C", decode(s, "Coolant temperature", *r02))
        assertEquals("-10 °", decode(s, "Main injection timing", 0x61, 0x4B, 0xF6, 0x00))
        assertEquals("0.0488 A", decode(s, "Battery current sensor (learned)", 0x61, 0x24, 128))
        assertEquals("Completed", decode(s, "Battery current sensor calibration", 0x61, 0x59, 0x00, 0x20, 0x00))
        assertEquals("1 / 0 / 2 / 0", decode(s, "Injector code changes (cyl 1/2/3/4)", 0x61, 0xFA, 0, 1, 0, 2, 0))
    }

    @Test
    fun dieselAtTemperatureDependsOnEngine() {
        val s = EngineDataScreens.DIESEL
        val tcm = intArrayOf(0x61, 0x02, 0, 0, 0, 0, 0, 0x5A, 0x64)
        assertEquals("50 °C", decode(s, "AT temperature", *tcm, variant = 0))
        assertEquals("40 °C", decode(s, "AT temperature", *tcm, variant = 1))
        assertEquals("50 °C", decode(s, "AT temperature", *tcm, variant = 2))
        assertEquals(Modules.TRANSMISSION, s.param("AT temperature").module)
    }

    @Test
    fun dieselVariantFromVin() {
        assertEquals(1, EngineDataScreens.dieselVariantFromVin("MMAGUKS10JH001234"))
        assertEquals(2, EngineDataScreens.dieselVariantFromVin("JMBXTCW1WAZ000001"))
        assertEquals(0, EngineDataScreens.dieselVariantFromVin("MMBJNKB40AD000001"))
        assertEquals(0, EngineDataScreens.dieselVariantFromVin("JMBLYV988AJ000001"))
        assertEquals(null, EngineDataScreens.dieselVariantFromVin("JMBXTGK1WAZ000001"))
        assertEquals(null, EngineDataScreens.dieselVariantFromVin(null))
    }

    @Test
    fun petrol() {
        val s = EngineDataScreens.PETROL
        assertEquals("4000 rpm", decode(s, "Engine speed", 0x61, 0x01, 0, 0, 0x7F, 0xFF))
        assertEquals("100 km/h", decode(s, "Vehicle speed", 0x61, 0x01, 0, 50))
        assertEquals("28.1 %", decode(s, "Long-term fuel trim 1", 0x61, 0x07, 0, 0, 200))
        val relays = intArrayOf(0x61, 0x1E, 0x20, 0x00)
        assertEquals("On", decode(s, "Engine control relay", *relays))
        assertEquals("Off", decode(s, "ETV relay", *relays))
        assertTrue(s.param("AT temperature").module == Modules.TRANSMISSION)
    }

    @Test
    fun cross() {
        val s = EngineDataScreens.CROSS
        assertTrue(s.useSession)
        assertEquals("49.5 %", decode(s, "Short-term fuel trim", 0x61, 0x07, 0, 0xFF))
        assertEquals("-4.0 psi", decode(s, "ISC learned value (A/C off)", 0x61, 0x2D, 0x80, 0x00))
        assertEquals("On", decode(s, "Engine start signal", 0x61, 0x1D, 0x40))
    }

    @Test
    fun xpander() {
        val s = EngineDataScreens.XPANDER
        assertEquals("20.000 V", decode(s, "Target ETV value", 0x61, 0x12, 0x40, 0x00))
        assertEquals("4000 km", decode(s, "Battery deterioration odometer (last)", 0x61, 0x29, 0x03, 0xE8))
        assertEquals("0.1 A", decode(s, "Vehicle consumption current", 0x61, 0x28, 0x80, 0x00))
        assertEquals("—", decode(s, "Starter counter", 0x61, 0x2A, 0x00)) // short reply
    }
}
