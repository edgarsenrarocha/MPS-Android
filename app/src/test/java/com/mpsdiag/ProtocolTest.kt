package com.mpsdiag

import com.mpsdiag.diag.DieselEngine
import com.mpsdiag.diag.Diag
import com.mpsdiag.diag.Dpf
import com.mpsdiag.diag.DpfService
import com.mpsdiag.diag.Dtc
import com.mpsdiag.diag.DtcService
import com.mpsdiag.diag.Modules
import com.mpsdiag.diag.NegativeResponseException
import com.mpsdiag.diag.NoResponseException
import com.mpsdiag.diag.Oil
import com.mpsdiag.elm.Elm327
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolTest {

    private val vin = "MMAGUKS10JH001234" // chars 6-9 = "KS10" -> Pajero Sport III / 4N15

    /** A small 4N15 engine ECU plus an ABS module. */
    private fun car(
        ecuId: IntArray = intArrayOf(0x5A, 0x9C, 0x00, 0x04, 0x25, 0x00, 0x08),
        regenReply: ByteArray = bytes(0x70, 0x50, 0x07, 0xFF),
        clearReplies: List<ByteArray> = listOf(bytes(0x7F, 0x14, 0x78), bytes(0x54, 0xFF, 0x00)),
    ): FakeElm = FakeElm { tx, req ->
        val r = req.joinToString("") { "%02X".format(it) }
        when (tx) {
            0x7E0 -> when (r) {
                "1092" -> listOf(bytes(0x50, 0x92))
                "1A9C" -> listOf(bytes(*ecuId))
                "1A90" -> listOf(bytes(0x5A, 0x90) + vin.toByteArray())
                "2110" -> listOf(bytes(0x61, 0x10, 0, 0, 0x32, 0x20))
                "2112" -> listOf(bytes(0x61, 0x12, 0, 0x5A, 0x01, 0x2C, 0x03))
                "2117" -> listOf(bytes(0x61, 0x17, 0x6E, 0x64, 0x69, 0x14, 0x28))
                "2159" -> listOf(bytes(0x61, 0x59, 0x81))
                "2158" -> listOf(bytes(0x61, 0x58, 0x05, 0xDC))
                "305007FF" -> listOf(regenReply)
                "30540700" -> listOf(bytes(0x70, 0x54, 0x07, 0x00))
                "30510700" -> listOf(bytes(0x70, 0x51, 0x07, 0x00))
                "1800FF00" -> listOf(bytes(0x58, 0x03, 0x25, 0x2F, 0xE0, 0x20, 0x02, 0x20, 0x24, 0x63, 0x00))
                "14FF00" -> clearReplies
                else -> listOf(bytes(0x7F, req[0].toInt() and 0xFF, 0x12))
            }
            0x784 -> when (r) {
                "1092" -> listOf(bytes(0x50, 0x92))
                "1800FF00" -> listOf(bytes(0x58, 0x00))
                else -> null
            }
            0x7DF -> when (r) {
                "03" -> listOf(bytes(0x43, 0x02, 0x01, 0x33, 0x02, 0x00))
                "07" -> listOf(bytes(0x47, 0x00))
                "04" -> listOf(bytes(0x44))
                else -> null
            }
            else -> null
        }
    }

    private fun connect(fake: FakeElm): Diag {
        fake.open()
        val d = Diag(Elm327(fake))
        assertEquals("ELM327 v1.5", d.initAdapter())
        return d
    }

    @Test
    fun dpfLiveDataDecodes() {
        val d = connect(car())
        assertTrue(Dpf.openEngine(d))
        val values = Dpf.poll(d, Dpf.PARAMS).zip(Dpf.PARAMS).associate { (v, p) -> p.label to v }
        assertEquals("50 °C", values["DPF temperature sensor"])
        assertEquals("6.00 kPa", values["DPF differential pressure"])
        assertEquals("250 °C", values["DPF temperature max"])
        assertEquals("300", values["Regeneration count"])
        assertEquals("Summation", values["Regeneration request mode"])
        assertEquals("On", values["DPF regeneration operating"])
        assertEquals("On", values["DPF regeneration request"])
        assertEquals("1500 s", values["Regeneration time remaining"])
        assertEquals("—", values["Last regeneration time"]) // ECU refused 21 1F
    }

    @Test
    fun vinIsReadAcrossMultipleFramesAndPicksEngine() {
        val d = connect(car())
        assertEquals(vin, Dpf.readVin(d))
        assertEquals(DieselEngine.E4N15, Dpf.engineFromVin(vin))
        assertEquals(DieselEngine.E4N14, Dpf.engineFromVin("JMBXTCW1WAZ000001"))
        assertEquals(null, Dpf.engineFromVin("JMBXTGF2WAZ000001"))
    }

    @Test
    fun unsupportedEcuIsDetected() {
        val d = connect(car(ecuId = intArrayOf(0x5A, 0x9C, 0x00, 0x04, 0x25, 0x00, 0x07)))
        assertFalse(Dpf.openEngine(d))
    }

    @Test
    fun regenerationIsSentOnceWithEngineSpecificId() {
        val fake = car()
        val d = connect(fake)
        Dpf.startRegeneration(d, DieselEngine.E4N15)
        assertEquals(1, fake.requests.count { it == "7E0: 305007FF" })
        assertTrue(fake.requests.contains("7E0: 1092"))
    }

    @Test
    fun refusedRegenerationIsReportedNotRetried() {
        val fake = car(regenReply = bytes(0x7F, 0x30, 0x22))
        val d = connect(fake)
        try {
            Dpf.startRegeneration(d, DieselEngine.E4N15)
            fail("expected refusal")
        } catch (e: NegativeResponseException) {
            assertEquals(0x22, e.code)
        }
        assertEquals(1, fake.requests.count { it == "7E0: 305007FF" })
    }

    @Test
    fun serviceResetsAndOilReset() {
        val fake = car()
        val d = connect(fake)
        Dpf.serviceReset(d, DpfService.DPF_REPLACED)
        Oil.reset(d)
        assertTrue(fake.requests.contains("7E0: 30510700"))
        assertTrue(fake.requests.contains("7E0: 30540700"))
        val oil = Dpf.poll(d, Oil.PARAMS)
        assertEquals(listOf("100 cc", "0 cc", "50 cc"), oil)
    }

    @Test
    fun readsEngineCodesFromMultiFrameReply() {
        val d = connect(car())
        val codes = DtcService.read(d, Modules.ENGINE)
        assertEquals(
            listOf(
                Dtc("P252F", Dtc.Status.CURRENT),
                Dtc("P2002", Dtc.Status.PENDING),
                Dtc("P2463", Dtc.Status.STORED),
            ),
            codes,
        )
    }

    @Test
    fun clearWaitsThroughResponsePendingAndSendsOnce() {
        val fake = car()
        val d = connect(fake)
        DtcService.clear(d, Modules.ENGINE)
        assertEquals(1, fake.requests.count { it == "7E0: 14FF00" })
    }

    @Test
    fun refusedClearIsAnError() {
        val d = connect(car(clearReplies = listOf(bytes(0x7F, 0x14, 0x22))))
        try {
            DtcService.clear(d, Modules.ENGINE)
            fail("expected refusal")
        } catch (e: NegativeResponseException) {
            assertEquals(0x22, e.code)
        }
    }

    @Test
    fun otherModulesAndGenericObd() {
        val d = connect(car())
        assertEquals(emptyList<Dtc>(), DtcService.read(d, Modules.ALL.first { it.requestId == 0x784 }))
        assertEquals(
            listOf(Dtc("P0133", Dtc.Status.CURRENT), Dtc("P0200", Dtc.Status.CURRENT)),
            DtcService.read(d, Modules.OBD2),
        )
        DtcService.clear(d, Modules.OBD2)
        // Switching back to the engine re-selects headers.
        assertEquals(3, DtcService.read(d, Modules.ENGINE).size)
    }

    @Test
    fun silentModuleIsNoResponse() {
        val d = connect(car())
        try {
            DtcService.read(d, Modules.ALL.first { it.requestId == 0x610 })
            fail("expected no response")
        } catch (_: NoResponseException) {
        }
    }

    @Test
    fun dtcFormatting() {
        assertEquals("P0016", DtcService.format(0x0016))
        assertEquals("C0123", DtcService.format(0x4123))
        assertEquals("B1400", DtcService.format(0x9400))
        assertEquals("U1109", DtcService.format(0xD109))
    }
}
