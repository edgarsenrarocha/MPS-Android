package com.mpsdiag

import com.mpsdiag.diag.Diag
import com.mpsdiag.diag.InputField
import com.mpsdiag.diag.Live
import com.mpsdiag.diag.LiveParam
import com.mpsdiag.diag.LiveScreen
import com.mpsdiag.diag.Modules
import com.mpsdiag.diag.u8
import com.mpsdiag.elm.Elm327
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveFrameworkTest {

    private fun connect(fake: FakeElm): Diag {
        fake.open()
        return Diag(Elm327(fake)).also { it.initAdapter() }
    }

    private val car = FakeElm { tx, req ->
        val r = req.joinToString("") { "%02X".format(it) }
        when {
            tx == 0x7E1 && r == "2101" -> listOf(bytes(0x61, 0x01, 0x10))
            tx == 0x7E0 && r == "2101" -> listOf(bytes(0x61, 0x01, 0x20))
            tx == 0x7E1 && r == "2105" -> listOf(bytes(0x7F, 0x21, 0x31)) // not supported
            else -> null
        }
    }

    private val screen = LiveScreen(
        id = "t", title = "t", subtitle = "t", group = "g", module = Modules.TRANSMISSION, useSession = false,
        params = listOf(
            LiveParam("TCM value", 0x01) { "${it.u8(2)}" },
            LiveParam("Engine value", 0x01, Modules.ENGINE) { "${it.u8(2)}" },
            LiveParam("Unsupported", 0x05) { "${it.u8(2)}" },
        ),
    )

    @Test
    fun valuesFromAnotherModuleAndBack() {
        val fake = car
        val d = connect(fake)
        assertEquals(listOf("16", "32", "—"), Live.poll(d, screen, screen.params))
        // The engine value was fetched from 7E0 and the screen returned to 7E1 afterwards.
        assertEquals(listOf("7E1: 2101", "7E0: 2101", "7E1: 2105"), fake.requests)
        assertEquals(Modules.TRANSMISSION, d.target)
    }

    @Test
    fun rejectedRequestIsSkippedAfterThreeRounds() {
        val fake = car
        val d = connect(fake)
        val skips = Live.Skips()
        repeat(5) { Live.poll(d, screen, screen.params, skips) }
        assertEquals(3, fake.requests.count { it == "7E1: 2105" })
        assertEquals(5, fake.requests.count { it == "7E1: 2101" })
    }

    @Test
    fun obdWorksAfterMitsubishiSession() {
        // Like the real 4N15 ECU: while in session 92 it ignores generic OBD-II requests.
        var extended = false
        val fake = FakeElm { tx, req ->
            val r = req.joinToString("") { "%02X".format(it) }
            when {
                tx == 0x7E0 && r == "1092" -> { extended = true; listOf(bytes(0x50, 0x92)) }
                tx == 0x7E0 && r == "1081" -> { extended = false; listOf(bytes(0x50, 0x81)) }
                tx == 0x7E0 && r == "2101" -> listOf(bytes(0x61, 0x01, 0x20))
                tx == 0x7DF && r == "010C" -> if (extended) null else listOf(bytes(0x41, 0x0C, 0x0B, 0xB8))
                else -> null
            }
        }
        val d = connect(fake)
        d.open(Modules.ENGINE)
        d.request(0x21, 0x01)
        d.open(Modules.OBD2)
        assertEquals(750, d.request(0x01, 0x0C).let { (it.u8(2) shl 8 or it.u8(3)) / 4 })
        assertEquals(1, fake.requests.count { it == "7E0: 1081" })
    }

    @Test
    fun inputValidation() {
        val km = InputField("Distance", min = 100, max = 100_000, multipleOf = 100)
        assertNull(km.validate(15_000))
        assertEquals("Distance must be a multiple of 100", km.validate(150))
        assertEquals("Distance must be between 100 and 100000", km.validate(50))
        assertEquals("Enter Distance", km.validate(null))
    }
}
