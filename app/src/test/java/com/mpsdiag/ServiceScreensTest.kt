package com.mpsdiag

import com.mpsdiag.diag.Diag
import com.mpsdiag.diag.Live
import com.mpsdiag.diag.NegativeResponseException
import com.mpsdiag.diag.NotSupportedException
import com.mpsdiag.diag.screens.ServiceScreens
import com.mpsdiag.elm.Elm327
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServiceScreensTest {

    private fun connect(fake: FakeElm): Diag {
        fake.open()
        return Diag(Elm327(fake)).also { it.initAdapter() }
    }

    /** Engine + TCM + SAS + EPB that accept everything and answer a few reads. */
    private fun car(ecuId: IntArray = intArrayOf(0x5A, 0x9C, 0, 0x04, 0x25, 0x00, 0x08), refuse: String? = null) = FakeElm { tx, req ->
        val r = req.joinToString("") { "%02X".format(it) }
        val sid = req[0].toInt() and 0xFF
        when {
            r == refuse -> listOf(bytes(0x7F, sid, 0x22))
            r == "1092" -> listOf(bytes(0x50, 0x92))
            r == "3E01" -> listOf(bytes(0x7E, 0x01))
            r == "1A9C" -> listOf(bytes(*ecuId))
            tx == 0x7E0 && r == "2101" -> listOf(bytes(0x61, 0x01, 0xAA, 0x00, 0x20))
            tx == 0x7E0 && r == "214C" -> listOf(bytes(0x61, 0x4C, 0x28, 0x27, 0x30, 0x80, 0x02))
            tx == 0x7E0 && r == "21B0" -> listOf(bytes(0x61, 0xB0, 0x01, 0xF4, 0x02, 0x8A))
            r.startsWith("30") && req.size == 3 -> listOf(bytes(0x70, req[1].toInt() and 0xFF, 0x01, 0x00, 0x00))
            sid == 0x30 -> listOf(bytes(0x70) + req.copyOfRange(1, req.size))
            sid == 0x31 -> listOf(bytes(0x71, req[1].toInt() and 0xFF))
            tx == 0x622 && r == "2103" -> listOf(bytes(0x61, 0x03, 0x01))
            else -> null
        }
    }

    private fun values(screen: com.mpsdiag.diag.LiveScreen, d: Diag, variant: Int = 0) =
        screen.paramsFor(variant).zip(Live.poll(d, screen, screen.paramsFor(variant))).associate { (p, v) -> p.label to v }

    @Test
    fun screenIdsAndLabelsAreUnique() {
        assertEquals(ServiceScreens.ALL.size, ServiceScreens.ALL.map { it.id }.toSet().size)
        for (s in ServiceScreens.ALL) {
            val lists = if (s.variants.isEmpty()) listOf(s.params) else s.variants.map { it.params }
            for (l in lists) assertEquals(s.id, l.size, l.map { it.label }.toSet().size)
        }
    }

    @Test
    fun fuelLeakTestSendsStartOnceAndDecodes() {
        val fake = car()
        val d = connect(fake)
        val s = ServiceScreens.FUEL_LEAK
        val v = values(s, d)
        assertEquals("No", v["Test running"])
        assertEquals("800 rpm", v["Engine speed"])
        assertEquals("40 MPa", v["Fuel pressure (target)"])
        assertEquals("39 MPa", v["Fuel pressure (actual)"])
        Live.run(d, s, s.actions[0])
        assertEquals(1, fake.requests.count { it == "7E0: 303507FF" })
        assertTrue(fake.requests.contains("7E0: 3E01"))
    }

    @Test
    fun injectorTestsUseTheirOwnIds() {
        val fake = car()
        val d = connect(fake)
        val s = ServiceScreens.INJECTOR_TEST
        s.actions.forEach { Live.run(d, s, it) }
        assertEquals(listOf("7E0: 30300700", "7E0: 30310700", "7E0: 30320700", "7E0: 30330700"),
            fake.requests.filter { it.endsWith("0700") })
    }

    @Test
    fun pumpValueDecodes() {
        val d = connect(car())
        val v = values(ServiceScreens.PUMP_VALVE_INIT, d)
        assertEquals("2", v["Learned state (2 = learned)"])
        assertEquals("0 mA", v["Learned value"]) // 0x80*10 - 1280
        assertEquals("1200 mA", v["Preset value"]) // 0x30*25
    }

    @Test
    fun batterySensorIsBlockedOnUnsupportedEcu() {
        val fake = car(ecuId = intArrayOf(0x5A, 0x9C, 0, 0x04, 0x25, 0x00, 0x07))
        val d = connect(fake)
        val s = ServiceScreens.BATTERY_SENSOR
        try {
            Live.run(d, s, s.actions[0])
            fail("expected refusal")
        } catch (_: NotSupportedException) {
        }
        assertTrue(fake.requests.none { it.endsWith("303907FF") })
    }

    @Test
    fun refusedRoutineIsReportedOnce() {
        val fake = car(refuse = "3101")
        val d = connect(fake)
        val s = ServiceScreens.STEERING_ANGLE
        assertEquals("Calibrated", values(s, d)["Sensor state"])
        try {
            Live.run(d, s, s.actions[0])
            fail("expected refusal")
        } catch (e: NegativeResponseException) {
            assertEquals(0x22, e.code)
        }
        assertEquals(1, fake.requests.count { it == "622: 3101" })
    }

    @Test
    fun learnedValuesAgainstLimits() {
        val d = connect(car())
        // 4N15/4N14 limits are variant 2: P1 −0.121…+0.07
        val v = values(ServiceScreens.SMALL_QTY_VALUES, d, variant = 2)
        assertEquals("0.000  ✓", v["P1 cylinder 1"]) // 0x01F4 = 500 -> 0.0
        assertEquals("0.150  ✗ (-0.121…0.070)", v["P1 cylinder 2"]) // 0x028A = 650 -> 0.15
    }

    @Test
    fun epbCommands() {
        val fake = car()
        val d = connect(fake)
        val s = ServiceScreens.EPB
        assertEquals("Stopped", values(s, d)["Brake motors"])
        Live.run(d, s, s.actions[0])
        Live.run(d, s, s.actions[1])
        assertEquals(listOf("610: 30120701", "610: 30120700"), fake.requests.filter { it.startsWith("610: 301207") })
    }
}
