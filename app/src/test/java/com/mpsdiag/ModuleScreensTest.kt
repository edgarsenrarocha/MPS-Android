package com.mpsdiag

import com.mpsdiag.diag.Diag
import com.mpsdiag.diag.LiveParam
import com.mpsdiag.diag.LiveScreen
import com.mpsdiag.diag.screens.ModuleScreens
import com.mpsdiag.elm.Elm327
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleScreensTest {

    private fun LiveScreen.value(variant: Int, label: String, reply: ByteArray): String {
        val p: LiveParam = paramsFor(variant).first { it.label == label }
        return p.decode(reply)
    }

    @Test
    fun idsAndLabelsAreUnique() {
        val ids = ModuleScreens.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (s in ModuleScreens.ALL) {
            val lists = if (s.variants.isEmpty()) listOf(s.params) else s.variants.map { it.params }
            for (l in lists) {
                val labels = l.map { it.label }
                assertEquals("duplicate label in ${s.id}", labels.size, labels.toSet().size)
                assertTrue(labels.isNotEmpty())
            }
        }
    }

    @Test
    fun paramCounts() {
        val at = ModuleScreens.TRANSMISSION
        assertEquals(listOf(18, 28, 38), at.variants.map { it.params.size })
        assertEquals(23, ModuleScreens.TRANSFER.params.size)
        assertEquals(listOf(7, 20), ModuleScreens.ABS.variants.map { it.params.size })
        assertEquals(13, ModuleScreens.METER.params.size)
    }

    @Test
    fun transmissionW6ajaDecodes() {
        val at = ModuleScreens.TRANSMISSION
        // 61 03: R2 input, R3 engine, R4 slip (signed), R10 torque ratio, R11 temp
        val r03 = bytes(0x61, 0x03, 0x40, 0x19, 0xF6, 0, 0, 0, 0, 0, 0xFF, 0x82)
        assertEquals("800 rpm", at.value(0, "Engine speed", r03))
        assertEquals("2048 rpm", at.value(0, "Input shaft speed", r03))
        assertEquals("-10 rpm", at.value(0, "Clutch slip", r03))
        assertEquals("80 °C", at.value(0, "A/T fluid temperature", r03))
        assertEquals("3.98", at.value(0, "Torque ratio", r03))
        val r07 = bytes(0x61, 0x07, 0x08, 0x01, 0x10, 0x04)
        assertEquals("R", at.value(0, "Gear selector position", r07))
        assertEquals("On", at.value(0, "Brake switch", r07))
        assertEquals("On", at.value(0, "TCL active signal", r07))
        val r04 = bytes(0x61, 0x04, 3, 4, 0x81, 0xFF)
        assertEquals("-184.2 psi", at.value(0, "Pressure 2", r04))
        assertEquals("925.4 psi", at.value(0, "Line pressure", r04))
    }

    @Test
    fun transmissionV4a5aDecodes() {
        val at = ModuleScreens.TRANSMISSION
        val r01 = bytes(0x61, 0x01, 0x06, 0x40, 0xFF, 0xFF)
        assertEquals("800 rpm", at.value(1, "Engine speed", r01))
        assertEquals("99.6 %", at.value(1, "Accelerator / throttle position", r01))
        // ((438.61 - 111.9) * 1.355818) = 442.96
        assertEquals("443.0 Nm", at.value(1, "Engine torque", r01))
        val r02 = bytes(0x61, 0x02, 0, 0x0B, 0xB8, 0, 0, 0x78, 0xFF, 0, 0x33, 0x20)
        assertEquals("1500 rpm", at.value(1, "Input shaft speed", r02))
        assertEquals("80 °C", at.value(1, "A/T fluid temperature", r02))
        assertEquals("33.15 V", at.value(1, "TCM control relay output", r02))
        assertEquals("0 rpm", at.value(1, "Damper clutch slip", r02))
        assertEquals("4", at.value(1, "Shift position", r02))
        val r04short = bytes(0x61, 0x04, 0x0A, 0x81, 0x08)
        assertEquals("D", at.value(1, "Inhibitor switch", r04short))
        assertEquals("On", at.value(1, "INVECS-II stop control", r04short))
        assertEquals("—", at.value(1, "Paddle switch (up)", r04short))
        // vehicle speed comes from the engine ECU
        val speed = at.paramsFor(1).first { it.label == "Vehicle speed" }
        assertEquals(0x7E0, speed.module?.requestId)
        assertEquals("100 km/h", speed.decode(bytes(0x61, 0x01, 0, 50)))
    }

    @Test
    fun transmissionW8awaDecodes() {
        val at = ModuleScreens.TRANSMISSION
        val r02 = bytes(0x61, 0x02, 0, 0x0F, 0xA0, 0x07, 0xD0, 0x82, 0x83, 50, 0, 0x05, 0x02)
        assertEquals("2000 rpm", at.value(2, "Input shaft speed", r02))
        assertEquals("1000 rpm", at.value(2, "Output shaft speed", r02))
        assertEquals("80 °C", at.value(2, "A/T fluid temperature (line)", r02))
        assertEquals("81 °C", at.value(2, "A/T fluid temperature (oil pan)", r02))
        assertEquals("140.0 km/h", at.value(2, "Vehicle speed", r02))
        assertEquals("On", at.value(2, "Selector position P/N", r02))
        assertEquals("On", at.value(2, "Selector position 1", r02))
        assertEquals("On", at.value(2, "Selector position 8", r02))
        assertEquals("Off", at.value(2, "Selector position 7", r02))
        val r03 = bytes(0x61, 0x03, 1, 0, 0, 0, 0xFF, 0, 0, 0, 0, 0, 0x10)
        assertEquals("100.0 %", at.value(2, "Linear solenoid 1 duty", r03))
        assertEquals("Supported", at.value(2, "Idle neutral control", r03))
        assertEquals("Not supported", at.value(2, "Auto stop-start control", r03))
        assertEquals("On", at.value(2, "Shift solenoid 1", r03))
    }

    @Test
    fun transmissionVariantMapping() {
        fun id(a: Int, b: Int, c: Int) = bytes(0x5A, 0x87, 0x00, a, b, c)
        assertEquals(0, ModuleScreens.transmissionVariant(id(0x91, 0xFF, 0xFF)))
        assertEquals(1, ModuleScreens.transmissionVariant(id(0x91, 0x00, 0x00)))
        assertEquals(1, ModuleScreens.transmissionVariant(id(0x91, 0x00, 0x01)))
        assertEquals(2, ModuleScreens.transmissionVariant(id(0x92, 0x00, 0x02)))
        assertEquals(2, ModuleScreens.transmissionVariant(id(0x92, 0x00, 0x04)))
        assertNull(ModuleScreens.transmissionVariant(id(0x93, 0x00, 0x00)))
    }

    @Test
    fun transmissionDetectionOverTheWire() {
        val fake = FakeElm { tx, req ->
            if (tx == 0x7E1 && req.size == 2 && req[0] == 0x1A.toByte() && req[1] == 0x87.toByte()) {
                listOf(bytes(0x5A, 0x87, 0x00, 0x92, 0x00, 0x03))
            } else {
                null
            }
        }
        fake.open()
        val d = Diag(Elm327(fake))
        d.initAdapter()
        d.select(ModuleScreens.TRANSMISSION.module)
        assertEquals(2, ModuleScreens.TRANSMISSION.detectVariant!!(d))

        val silent = FakeElm { _, _ -> null }
        silent.open()
        val d2 = Diag(Elm327(silent))
        d2.initAdapter()
        d2.select(ModuleScreens.TRANSMISSION.module)
        assertNull(ModuleScreens.TRANSMISSION.detectVariant!!(d2))
    }

    @Test
    fun transferDecodes() {
        val t = ModuleScreens.TRANSFER
        val r10 = bytes(0x61, 0x10, 0x32, 0x32, 0x03, 0xE8, 0, 0, 0, 0, 0, 0, 0x07, 0xD0, 0x0F, 0xA0)
        assertEquals("100.0 km/h", t.value(0, "Vehicle speed", r10))
        assertEquals("500 rpm", t.value(0, "Wheel speed FL", r10))
        assertEquals("1000 rpm", t.value(0, "Front propeller shaft speed", r10))
        assertEquals("2000 rpm", t.value(0, "Rear propeller shaft speed", r10))
        assertEquals("4HLc → 4LLc", t.value(0, "Transfer case position", bytes(0x61, 0x13, 0x09)))
        assertEquals("4LLc", t.value(0, "Transfer select switch", bytes(0x61, 0x16, 0x04)))
        assertEquals("On", t.value(0, "Select switch 4H", bytes(0x61, 0x22, 0x08)))
        val r12 = bytes(0x61, 0x12, 0x02, 0x00) // 512 * 1566.29 / 65535 = 12.24
        assertEquals("12.24 V", t.value(0, "Supply voltage", r12))
        assertEquals("Neutral", t.value(0, "A/T neutral condition", bytes(0x61, 0x28, 0x01)))
    }

    @Test
    fun absDecodes() {
        val a = ModuleScreens.ABS
        val r20 = bytes(0x61, 0x20, 0xFF, 0x80, 0, 0, 0xFF, 0x80)
        assertEquals("326.0 km/h", a.value(0, "Wheel speed FR", r20))
        assertEquals("163.6 km/h", a.value(0, "Wheel speed FL", r20))
        assertEquals("20.0 V", a.value(0, "Supply voltage", r20))
        assertEquals("On", a.value(0, "Stop lamp switch", r20))
        val r30 = bytes(0x61, 0x30, 0, 0, 0xFF, 0x14, 0x80, 0xFF, 0, 0xFF)
        assertEquals("-118.0 °", a.value(1, "Steering wheel angle", r30))
        assertEquals("4.58 m/s²", a.value(1, "Lateral G sensor", r30))
        assertEquals("-4.62 m/s²", a.value(1, "Longitudinal G sensor", r30))
        assertEquals("3189 psi", a.value(1, "Master cylinder pressure", r30))
        assertEquals("-18.46 m/s²", a.value(1, "G sensor (uncalibrated)", r30))
        val r43 = bytes(0x61, 0x43, 0xA0, 0, 7, 0x84)
        assertEquals("On", a.value(1, "TCL OFF switch", r43))
        assertEquals("On", a.value(1, "ASC inactive", r43))
        assertEquals("7", a.value(1, "ASC off counter", r43))
        assertEquals("Locked", a.value(1, "Rear differential", r43))
        assertEquals("On", a.value(1, "Rear diff lock off switch", r43))
    }

    @Test
    fun meterDecodes() {
        val m = ModuleScreens.METER
        assertEquals("123456 km", m.value(0, "Odometer", bytes(0x61, 0xAD, 0x40, 0xE2, 0x01)))
        assertEquals("12.5 V", m.value(0, "Supply voltage", bytes(0x61, 0xAF, 125)))
        val a3 = bytes(0x61, 0xA3, 0xE8, 0x03, 66)
        assertEquals("100.0 Ω", m.value(0, "Fuel gauge resistance", a3))
        assertEquals("35 L", m.value(0, "Fuel gauge", a3))
        val bc = bytes(0x61, 0xBC, 0x96, 0x00, 0, 0, 0x06)
        assertEquals("15000 km", m.value(0, "Distance to service reminder", bc))
        assertEquals("6 months", m.value(0, "Months to service reminder", bc))
        val unset = bytes(0x61, 0xDE, 0xFF, 0xFF, 0, 0, 0xFF)
        assertEquals("---", m.value(0, "Distance to extra reminder", unset))
        assertEquals("---", m.value(0, "Months to extra reminder", unset))
        assertEquals("EU 2", m.value(0, "Service schedule", bytes(0x61, 0xDF, 17)))
        val bf = bytes(0x61, 0xBF, 0x10, 0x27, 0x00, 0, 0, 0, 0x2C, 0x01)
        assertEquals("10000 km", m.value(0, "Integrated mileage for reminder", bf))
        assertEquals("300 days", m.value(0, "Integrated days for reminder", bf))
    }

    private fun hex(list: List<IntArray>) = list.joinToString(" | ") { r -> r.joinToString(" ") { "%02X".format(it) } }

    @Test
    fun meterWritesBuildExactBytes() {
        val a = ModuleScreens.METER_ACTIONS
        assertEquals(23, ModuleScreens.SCHEDULES.size)
        assertEquals("3B DE 96 00 FF FF 0C FF", hex(a[0].requestsFor(listOf(15_000, 12))))
        assertEquals("3B DE E8 03 FF FF 24 FF", hex(a[0].requestsFor(listOf(100_000, 36))))
        assertEquals("3B DE 00 00 FF FF 00 FF", hex(a[1].requestsFor(emptyList())))
        assertEquals("3B DF 15", hex(a[2].requestsFor(listOf(21))))
        assertEquals("3B BF 40 E2 01 FF FF FF FE 00", hex(a[3].requestsFor(listOf(123_456, 254))))
        assertEquals("3B D9 FF 00 FF", hex(a[4].requestsFor(listOf(25_500, 255))))
    }

    @Test
    fun meterInputValidation() {
        val extra = ModuleScreens.METER_ACTIONS[0].inputs
        assertNull(extra[0].validate(15_000))
        assertTrue(extra[0].validate(15_050) != null)
        assertTrue(extra[0].validate(100_100) != null)
        assertTrue(extra[1].validate(37) != null)
        val integrated = ModuleScreens.METER_ACTIONS[3].inputs
        assertTrue(integrated[0].validate(200_000) != null)
        assertTrue(integrated[1].validate(255) != null)
        assertNull(integrated[1].validate(254))
    }
}
