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
    fun inputValidation() {
        val km = InputField("Distance", min = 100, max = 100_000, multipleOf = 100)
        assertNull(km.validate(15_000))
        assertEquals("Distance must be a multiple of 100", km.validate(150))
        assertEquals("Distance must be between 100 and 100000", km.validate(50))
        assertEquals("Enter Distance", km.validate(null))
    }
}
