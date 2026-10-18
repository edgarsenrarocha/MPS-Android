package com.mpsdiag.diag.screens

import com.mpsdiag.diag.InputField
import com.mpsdiag.diag.Live.bit
import com.mpsdiag.diag.Live.enumOf
import com.mpsdiag.diag.Live.f0
import com.mpsdiag.diag.Live.f1
import com.mpsdiag.diag.Live.f2
import com.mpsdiag.diag.Live.le16
import com.mpsdiag.diag.Live.le24
import com.mpsdiag.diag.Live.s8
import com.mpsdiag.diag.LiveParam
import com.mpsdiag.diag.LiveScreen
import com.mpsdiag.diag.Module
import com.mpsdiag.diag.Modules
import com.mpsdiag.diag.Screens
import com.mpsdiag.diag.ServiceAction
import com.mpsdiag.diag.Variant
import com.mpsdiag.diag.u16
import com.mpsdiag.diag.u8

/** Live data from the transmission, transfer case, ABS/ASC and instrument cluster. */
object ModuleScreens {

    private fun module(requestId: Int): Module = Modules.ALL.first { it.requestId == requestId }

    private fun pct(raw: Int, full: Double = 100.0) = "${f1(raw * full / 255)} %"
    private fun supported(b: Boolean) = if (b) "Supported" else "Not supported"

    /** lb·ft → N·m */
    private const val LBFT_TO_NM = 1.355818

    // ---- Automatic transmission (TCM 7E1/7E9, no diagnostic session) ------------------

    private val AT_W6AJA = Variant(
        "W6AJA (6-speed)",
        listOf(
            LiveParam("Engine speed", 0x03) { "${it.u8(3) * 32} rpm" },
            LiveParam("Vehicle speed sensor", 0x02) { "${it.u8(2)} km/h" },
            LiveParam("Input shaft speed", 0x03) { "${it.u8(2) * 32} rpm" },
            LiveParam("Output shaft speed", 0x02) { "${it.u8(3) * 32} rpm" },
            LiveParam("Clutch slip", 0x03) { "${s8(it, 4)} rpm" },
            LiveParam("A/T fluid temperature", 0x03) { "${it.u8(11) - 50} °C" },
            LiveParam("Gear selector position", 0x07) {
                enumOf(it.u8(5) and 0x07, mapOf(0 to "D", 2 to "Ds", 3 to "P/N", 4 to "R", 5 to "L"))
            },
            LiveParam("Power supply voltage", 0x02) { "${f1(it.u8(5) * 0.1)} V" },
            LiveParam("Line pressure", 0x04) { "${f1(it.u8(5) * 925.36 / 255)} psi" },
            // The original labels this row "-"; it is a second (signed) pressure value.
            LiveParam("Pressure 2", 0x04) { "${f1(s8(it, 4) * 184.2 / 127)} psi" },
            LiveParam("Current gear", 0x04) { "${it.u8(2)}" },
            LiveParam("Required gear", 0x04) { "${it.u8(3)}" },
            LiveParam("Torque ratio", 0x03) { f2(it.u8(10) * 3.98 / 255) },
            LiveParam("Sport / Ds mode switch", 0x07) { bit(it, 3, 0) },
            LiveParam("Brake switch", 0x07) { bit(it, 2, 3) },
            LiveParam("ABS active signal", 0x07) { bit(it, 4, 0) },
            LiveParam("TCL active signal", 0x07) { bit(it, 4, 4) },
            LiveParam("Idle switch", 0x07) { bit(it, 2, 1) },
        ),
    )

    private val AT_V4A5A = Variant(
        "V4A5A / V5A5A (4/5-speed)",
        listOf(
            LiveParam("Engine speed", 0x01) { "${f0(it.u16(2) / 2.0)} rpm" },
            LiveParam("Engine torque", 0x01) { "${f1((it.u8(5) * 438.61 / 255 - 111.9) * LBFT_TO_NM)} Nm" },
            LiveParam("Vehicle speed", 0x01, Modules.ENGINE) { "${it.u8(3) * 2} km/h" },
            LiveParam("Input shaft speed", 0x02) { "${f0(it.u16(3) / 2.0)} rpm" },
            LiveParam("Output shaft speed", 0x02) { "${f0(it.u16(5) / 2.0)} rpm" },
            LiveParam("A/T fluid temperature", 0x02) { "${it.u8(7) - 40} °C" },
            LiveParam("TCM control relay output", 0x02) { "${f2(it.u8(8) * 33.15 / 255)} V" },
            LiveParam("Damper clutch slip", 0x02) { "${it.u8(10) - 51} rpm" },
            LiveParam("Shift position", 0x02) {
                enumOf(
                    it.u8(11),
                    mapOf(0x00 to "-", 0x01 to "P/N", 0x02 to "R", 0x04 to "1", 0x08 to "2", 0x10 to "3", 0x20 to "4", 0x40 to "4'", 0x80 to "5"),
                )
            },
            LiveParam("Deceleration change mode value", 0x03) { "${f1(100 - it.u8(2) * 100.0 / 255)} %" },
            LiveParam("Downshift solenoid duty", 0x03) { "${f1(100 - it.u8(3) * 100.0 / 255)} %" },
            LiveParam("Second solenoid valve duty", 0x03) { "${f1(100 - it.u8(4) * 100.0 / 255)} %" },
            LiveParam("Upshift solenoid valve duty", 0x03) { pct(it.u8(5)) },
            LiveParam("Reduction solenoid valve duty", 0x03) { "${f1(100 - it.u8(6) * 100.0 / 255)} %" },
            LiveParam("Damper clutch (DCC) solenoid duty", 0x03) { pct(it.u8(7)) },
            LiveParam("Stop light switch", 0x04) { bit(it, 2, 1) },
            LiveParam("A/C compressor relay", 0x04) { bit(it, 2, 3) },
            LiveParam("Upshift off signal", 0x04) { bit(it, 2, 4) },
            LiveParam("4LLc shutdown", 0x04) { bit(it, 3, 5) },
            LiveParam("Overdrive off switch", 0x04) { bit(it, 3, 0) },
            LiveParam("Select switch", 0x04) { bit(it, 3, 1) },
            LiveParam("Shift lever switch (up)", 0x04) { bit(it, 3, 2) },
            LiveParam("Shift lever switch (down)", 0x04) { bit(it, 3, 3) },
            LiveParam("Inhibitor switch", 0x04) {
                enumOf(
                    it.u8(4),
                    mapOf(0x01 to "P", 0x02 to "R", 0x04 to "N", 0x08 to "D", 0x10 to "4", 0x20 to "3", 0x40 to "2", 0x80 to "L"),
                )
            },
            // Short (5-byte) replies have no paddle byte; the value then shows "—".
            LiveParam("Paddle switch (down)", 0x04) { bit(it, 5, 1) },
            LiveParam("Paddle switch (up)", 0x04) { bit(it, 5, 0) },
            LiveParam("INVECS-II stop control", 0x04) { bit(it, 3, 7) },
            LiveParam("Accelerator / throttle position", 0x01) { pct(it.u8(4), 99.61) },
        ),
    )

    private val AT_W8AWA = Variant(
        "W8AWA / V8AWG (8-speed)",
        listOf(
            LiveParam("Engine speed", 0x01) { "${f0(it.u16(2) / 2.0)} rpm" },
            LiveParam("Accelerator pedal", 0x01) { pct(it.u8(4), 102.0) },
            LiveParam("Engine torque", 0x01) { "${f1((it.u8(5) * 1517.41 / 255 - 369.1) * LBFT_TO_NM)} Nm" },
            LiveParam("Vehicle speed", 0x02) { "${f1(it.u8(9) * 2.8)} km/h" },
            LiveParam("Input shaft speed", 0x02) { "${f0(it.u16(3) / 2.0)} rpm" },
            LiveParam("Output shaft speed", 0x02) { "${f0(it.u16(5) / 2.0)} rpm" },
            LiveParam("A/T fluid temperature (line)", 0x02) { "${it.u8(7) - 50} °C" },
            LiveParam("A/T fluid temperature (oil pan)", 0x02) { "${it.u8(8) - 50} °C" },
            LiveParam("Linear solenoid 1 duty", 0x03) { pct(it.u8(6)) },
            LiveParam("Linear solenoid 2 duty", 0x03) { pct(it.u8(7)) },
            LiveParam("Linear solenoid 3 duty", 0x03) { pct(it.u8(8)) },
            LiveParam("Linear solenoid 4 duty", 0x03) { pct(it.u8(9)) },
            LiveParam("Linear solenoid 5 duty", 0x03) { pct(it.u8(10)) },
            LiveParam("Shift solenoid 1", 0x03) { bit(it, 2, 0) },
            LiveParam("Shift solenoid 2", 0x03) { bit(it, 3, 0) },
            LiveParam("Idle neutral control", 0x03) { supported(it.u8(12) and 0x10 != 0) },
            LiveParam("Auto stop-start control", 0x03) { supported(it.u8(12) and 0x08 != 0) },
            LiveParam("Lock-up solenoid duty", 0x03) { pct(it.u8(4)) },
            LiveParam("Line pressure solenoid duty", 0x03) { pct(it.u8(5)) },
            LiveParam("Inhibitor switch: D", 0x04) { bit(it, 4, 3) },
            LiveParam("Inhibitor switch: N", 0x04) { bit(it, 4, 2) },
            LiveParam("Inhibitor switch: P", 0x04) { bit(it, 4, 0) },
            LiveParam("Inhibitor switch: R", 0x04) { bit(it, 4, 1) },
            LiveParam("Paddle switch (up)", 0x04) { bit(it, 5, 0) },
            LiveParam("Paddle switch (down)", 0x04) { bit(it, 5, 1) },
            LiveParam("Select switch", 0x04) { bit(it, 3, 1) },
            LiveParam("Shift lever switch (up)", 0x04) { bit(it, 3, 2) },
            LiveParam("Shift lever switch (down)", 0x04) { bit(it, 3, 3) },
            LiveParam("Selector position 1", 0x02) { bit(it, 11, 2) },
            LiveParam("Selector position 2", 0x02) { bit(it, 11, 3) },
            LiveParam("Selector position 3", 0x02) { bit(it, 11, 4) },
            LiveParam("Selector position 4", 0x02) { bit(it, 11, 5) },
            LiveParam("Selector position 5", 0x02) { bit(it, 11, 6) },
            LiveParam("Selector position 6", 0x02) { bit(it, 11, 7) },
            LiveParam("Selector position 7", 0x02) { bit(it, 12, 0) },
            LiveParam("Selector position 8", 0x02) { bit(it, 12, 1) },
            LiveParam("Selector position P/N", 0x02) { bit(it, 11, 0) },
            LiveParam("Selector position R", 0x02) { bit(it, 11, 1) },
        ),
    )

    /** Maps the TCM's `5A 87` identification (bytes 3–5) to a variant index. */
    fun transmissionVariant(id: ByteArray): Int? {
        if (id.size < 6) return null
        val a = id.u8(3)
        val b = id.u8(4)
        val c = id.u8(5)
        return when {
            a == 0x91 && b == 0xFF && c == 0xFF -> 0
            a == 0x91 && b == 0x00 && (c == 0x00 || c == 0x01) -> 1
            a == 0x92 && b == 0x00 && c in 0x02..0x04 -> 2
            else -> null
        }
    }

    val TRANSMISSION = LiveScreen(
        id = "transmission",
        title = "Automatic transmission",
        subtitle = "Speeds, temperatures, solenoids, switches",
        group = Screens.GROUP_DRIVETRAIN,
        module = Modules.TRANSMISSION,
        useSession = false,
        variants = listOf(AT_W6AJA, AT_V4A5A, AT_W8AWA),
        detectVariant = { d ->
            try {
                transmissionVariant(d.request(0x1A, 0x87))
            } catch (_: Exception) {
                null
            }
        },
        note = "The transmission type is detected automatically; change it with \"Choose type\" in the menu if the values look wrong.",
    )

    // ---- Super Select 4WD II transfer case (7B6/7B7) -----------------------------------

    private val TRANSFER_POSITIONS = mapOf(
        1 to "2H", 2 to "4H", 3 to "4HLc", 4 to "4LLc",
        5 to "2H → 4HLc", 6 to "4HLc → 2H", 7 to "4HLc → 4H", 8 to "4H → 4HLc", 9 to "4HLc → 4LLc", 10 to "4LLc → 4HLc",
    )

    val TRANSFER = LiveScreen(
        id = "transfer",
        title = "Super Select 4WD II",
        subtitle = "Transfer case: mode, shafts, wheel speeds, motor",
        group = Screens.GROUP_DRIVETRAIN,
        module = module(0x7B6),
        params = listOf(
            LiveParam("Transfer case position", 0x13) { enumOf(it.u8(2) and 0x0F, TRANSFER_POSITIONS) },
            LiveParam("Transfer select switch", 0x16) {
                enumOf(it.u8(2) and 0x07, mapOf(0 to "-", 1 to "2H", 2 to "4H", 3 to "4HLc", 4 to "4LLc"))
            },
            LiveParam("Vehicle speed", 0x10) { "${f1(it.u16(2) / 128.5)} km/h" },
            LiveParam("Wheel speed FL", 0x10) { "${f0(it.u16(4) * 0.5)} rpm" },
            LiveParam("Wheel speed FR", 0x10) { "${f0(it.u16(6) * 0.5)} rpm" },
            LiveParam("Wheel speed RL", 0x10) { "${f0(it.u16(8) * 0.5)} rpm" },
            LiveParam("Wheel speed RR", 0x10) { "${f0(it.u16(10) * 0.5)} rpm" },
            LiveParam("Front propeller shaft speed", 0x10) { "${f0(it.u16(12) * 0.5)} rpm" },
            LiveParam("Rear propeller shaft speed", 0x10) { "${f0(it.u16(14) * 0.5)} rpm" },
            LiveParam("Engine speed", 0x14) { "${it.u16(4)} rpm" },
            LiveParam("Accelerator pedal", 0x14) { "${it.u8(6) * 102 / 255} %" },
            LiveParam("Supply voltage", 0x12) { "${f2(it.u16(2) * 1566.29 / 65535)} V" },
            LiveParam("Transfer motor voltage", 0x15) { "${f2(it.u16(3) * 321.12 / 65535)} V" },
            // The original also shows a "motor current" row, but it decodes the same bytes as the
            // voltage above; it is left out rather than show a wrong value.
            LiveParam("Transfer motor PWM output", 0x15) { "${f1(it.u8(6) * 0.5)} %" },
            LiveParam("Transfer motor relay", 0x15) { bit(it, 2, 2) },
            LiveParam("Transfer motor", 0x15) { bit(it, 2, 0) },
            LiveParam("Select switch 2H", 0x22) { bit(it, 2, 0) },
            LiveParam("Select switch 4H", 0x22) { bit(it, 2, 3) },
            LiveParam("Select switch 4HLc", 0x22) { bit(it, 2, 1) },
            LiveParam("Select switch 4LLc", 0x22) { bit(it, 2, 2) },
            LiveParam("Self-holding output", 0x32) { bit(it, 2, 0) },
            LiveParam("A/T neutral condition", 0x28) {
                enumOf(it.u8(2) and 0x03, mapOf(0 to "Not in neutral", 1 to "Neutral", 2 to "Not present"))
            },
            LiveParam("Manual transmission neutral", 0x29) { bit(it, 2, 0) },
        ),
    )

    // ---- ABS / ASC (784/785) ----------------------------------------------------------

    private val ABS_WHEELS = listOf(
        LiveParam("Wheel speed FL", 0x20) { "${f1(it.u8(3) * 326.0 / 255)} km/h" },
        LiveParam("Wheel speed FR", 0x20) { "${f1(it.u8(2) * 326.0 / 255)} km/h" },
        LiveParam("Wheel speed RL", 0x20) { "${f1(it.u8(5) * 326.0 / 255)} km/h" },
        LiveParam("Wheel speed RR", 0x20) { "${f1(it.u8(4) * 326.0 / 255)} km/h" },
        LiveParam("Supply voltage", 0x20) { "${f1(it.u8(6) * 20.0 / 255)} V" },
    )
    private val ABS_LONG_G = LiveParam("Longitudinal G sensor", 0x30) { "${f2(it.u8(8) * 9.2 / 255 - 4.62)} m/s²" }
    private val ABS_STOP_LAMP = LiveParam("Stop lamp switch", 0x20) { bit(it, 7, 7) }

    private val ABS_ONLY = Variant("ABS only", ABS_WHEELS + listOf(ABS_LONG_G, ABS_STOP_LAMP))

    private val ABS_ASC = Variant(
        "ABS + ASC (stability control)",
        ABS_WHEELS + listOf(
            LiveParam("Lateral G sensor", 0x30) { "${f2(it.u8(9) * 9.2 / 255 - 4.62)} m/s²" },
            ABS_LONG_G,
            LiveParam("Master cylinder pressure", 0x30) { "${f0(it.u8(7) * 3536.0 / 255 - 346.65)} psi" },
            LiveParam("Steering wheel angle", 0x30) { "${f1(it.u8(5) * 0.5 + s8(it, 4) * 128)} °" },
            LiveParam("Yaw rate", 0x30) { "${f1(it.u8(6) * 248.88 / 255 - 124.93)} °/s" },
            ABS_STOP_LAMP,
            LiveParam("TCL OFF switch", 0x43) { bit(it, 2, 7) },
            LiveParam("At engine start", 0x43) { bit(it, 2, 4) },
            LiveParam("Lateral G sensor (uncalibrated)", 0x30) { "${f2(it.u8(3) * 36.78 / 255 - 18.46)} m/s²" },
            LiveParam("G sensor (uncalibrated)", 0x30) { "${f2(it.u8(2) * 36.78 / 255 - 18.46)} m/s²" },
            LiveParam("ASC inactive", 0x43) { bit(it, 2, 5) },
            LiveParam("Rear diff lock off switch", 0x43) { bit(it, 5, 7) },
            LiveParam("ASC off counter", 0x43) { "${it.u8(4)}" },
            LiveParam("Rear diff lock indicator", 0x43) { bit(it, 5, 3) },
            LiveParam("Rear differential", 0x43) { if (it.u8(5) and 0x04 != 0) "Locked" else "Unlocked" },
        ),
    )

    val ABS = LiveScreen(
        id = "abs",
        title = "ABS / ASC",
        subtitle = "Wheel speeds, G sensors, yaw, steering angle",
        group = Screens.GROUP_CHASSIS,
        module = module(0x784),
        variants = listOf(ABS_ONLY, ABS_ASC),
        note = "Pick \"ABS + ASC\" with \"Choose type\" in the menu if the vehicle has stability control.",
    )

    // ---- Instrument cluster (6A0/514) -------------------------------------------------

    /** Service schedules, indexed by the value stored in the cluster ("-" = unused value). */
    val SCHEDULES: List<String> = listOf(
        "Display OFF", "JPN 1-1", "JPN 1-2", "JPN 2", "JPN 3-1", "JPN 3-2", "JPN 4", "-",
        "GCC/EXP", "MMAL", "-", "NAS 10", "NAS 11", "NAS 20", "NAS 21", "-",
        "EU 1", "EU 2", "Optional INT", "EU 3", "GCC/EXP 2", "Normal", "Severe",
    )

    /** Mileage stored as km/100 (little-endian); FFFF = not set. */
    private fun reminderKm(r: ByteArray) = if (r.u8(2) == 0xFF && r.u8(3) == 0xFF) "---" else "${le16(r, 2) * 100} km"
    private fun reminderMonths(r: ByteArray) = if (r.u8(6) == 0xFF) "---" else "${r.u8(6)} months"

    private const val CONFIRM = "Do you really want to perform this function?\n\nIgnition ON."
    private const val WRITTEN = "Written. The values below refresh automatically."

    private fun lo(v: Int) = v and 0xFF
    private fun mid(v: Int) = (v shr 8) and 0xFF
    private fun hi(v: Int) = (v shr 16) and 0xFF

    val METER_ACTIONS: List<ServiceAction> = listOf(
        ServiceAction(
            title = "Extra service reminder: set",
            instructions = "The mileage must be a multiple of 100 km. This sets the months and mileage of an " +
                "optional (extra) service reminder; the reminder can depend on months or on mileage.\n\n$CONFIRM",
            inputs = listOf(
                InputField("Mileage (km)", min = 100, max = 100_000, multipleOf = 100),
                InputField("Months", min = 1, max = 36),
            ),
            build = { (km, months) ->
                val v = km / 100
                listOf(intArrayOf(0x3B, 0xDE, lo(v), mid(v), 0xFF, 0xFF, months, 0xFF))
            },
            successMessage = WRITTEN,
        ),
        ServiceAction(
            title = "Extra service reminder: cancel",
            instructions = "Removes the extra service reminder.\n\n$CONFIRM",
            requests = listOf(intArrayOf(0x3B, 0xDE, 0x00, 0x00, 0xFF, 0xFF, 0x00, 0xFF)),
            successMessage = WRITTEN,
        ),
        ServiceAction(
            title = "Service reminder schedule",
            instructions = "Sets the periodic service reminder schedule shown by the cluster.\n\n$CONFIRM",
            inputs = listOf(InputField("Schedule", choices = SCHEDULES)),
            build = { (schedule) -> listOf(intArrayOf(0x3B, 0xDF, schedule)) },
            successMessage = WRITTEN,
        ),
        ServiceAction(
            title = "Adjust integrated mileage / days",
            instructions = "Sets the mileage and days the cluster has counted towards the service reminder.\n\n$CONFIRM",
            inputs = listOf(
                InputField("Integrated mileage (km)", min = 1, max = 199_999),
                InputField("Integrated days", min = 1, max = 254),
            ),
            build = { (km, days) ->
                listOf(intArrayOf(0x3B, 0xBF, lo(km), mid(km), hi(km), 0xFF, 0xFF, 0xFF, lo(days), mid(days)))
            },
            successMessage = WRITTEN,
        ),
        ServiceAction(
            title = "Optional interval (Optional INT schedule)",
            instructions = "Sets the mileage and month interval used by the \"Optional INT\" schedule. " +
                "The mileage must be a multiple of 100 km.\n\n$CONFIRM",
            inputs = listOf(
                InputField("Interval mileage (km)", min = 100, max = 25_500, multipleOf = 100),
                InputField("Interval months", min = 1, max = 255),
            ),
            build = { (km, months) ->
                val v = km / 100
                listOf(intArrayOf(0x3B, 0xD9, lo(v), mid(v), months))
            },
            successMessage = WRITTEN,
        ),
    )

    val METER = LiveScreen(
        id = "meter",
        title = "Instrument cluster",
        subtitle = "Odometer, fuel gauge, service reminder",
        group = Screens.GROUP_CHASSIS,
        module = module(0x6A0),
        params = listOf(
            LiveParam("Supply voltage", 0xAF) { "${f1(it.u8(2) * 0.1)} V" },
            LiveParam("Odometer", 0xAD) { "${le24(it, 2)} km" },
            LiveParam("Fuel gauge resistance", 0xA3) { "${f1(le16(it, 2) * 0.1)} Ω" },
            LiveParam("Fuel gauge", 0xA3) { "${f0((it.u8(4) - 16) * 0.7)} L" },
            LiveParam("Distance to service reminder", 0xBC) { reminderKm(it) },
            LiveParam("Months to service reminder", 0xBC) { reminderMonths(it) },
            LiveParam("Distance to extra reminder", 0xDE) { reminderKm(it) },
            LiveParam("Months to extra reminder", 0xDE) { reminderMonths(it) },
            LiveParam("Service schedule", 0xDF) { SCHEDULES.getOrNull(it.u8(2)) ?: "Unknown (%02X)".format(it.u8(2)) },
            LiveParam("Integrated mileage for reminder", 0xBF) { "${le24(it, 2)} km" },
            LiveParam("Integrated days for reminder", 0xBF) { "${le16(it, 8)} days" },
            LiveParam("Optional interval mileage", 0xD9) { "${le16(it, 2) * 100} km" },
            LiveParam("Optional interval months", 0xD9) { "${it.u8(4)} months" },
        ),
        actions = METER_ACTIONS,
    )

    val ALL: List<LiveScreen> = listOf(TRANSMISSION, TRANSFER, ABS, METER)
}
