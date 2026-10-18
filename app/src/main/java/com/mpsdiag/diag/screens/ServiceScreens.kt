package com.mpsdiag.diag.screens

import com.mpsdiag.diag.LiveParam
import com.mpsdiag.diag.LiveScreen
import com.mpsdiag.diag.Live
import com.mpsdiag.diag.Live.bit
import com.mpsdiag.diag.Live.f2
import com.mpsdiag.diag.Live.f3
import com.mpsdiag.diag.Modules
import com.mpsdiag.diag.Screens
import com.mpsdiag.diag.ServiceAction
import com.mpsdiag.diag.Variant
import com.mpsdiag.diag.u16
import com.mpsdiag.diag.u8

/**
 * Service and maintenance routines. Each is a live screen showing the values that matter
 * while it runs, with the routine as a one-shot action (sent once, never retried).
 */
object ServiceScreens {

    private const val READ_MANUAL =
        "Only use this function if you know what it is for and have read the workshop manual procedure."

    private val TESTER_PRESENT = intArrayOf(0x3E, 0x01)

    /** `30 <id> 01` (report current state) answers `70 <id> 01 <state>`. */
    private fun state(label: String, id: Int, decoder: (ByteArray) -> String) =
        LiveParam(label, intArrayOf(0x30, id, 0x01), decoder)

    private fun yesNo(b: Boolean) = if (b) "Yes" else "No"

    private val ENGINE_RPM_2101 = LiveParam("Engine speed", 0x01) { "${it.u8(4) * 25} rpm" }
    private val RAIL_TARGET = LiveParam("Fuel pressure (target)", 0x4C) { "${it.u8(2)} MPa" }
    private val RAIL_ACTUAL = LiveParam("Fuel pressure (actual)", 0x4C) { "${it.u8(3)} MPa" }

    private fun module(requestId: Int) = Modules.ALL.first { it.requestId == requestId }

    // ---- Engine ---------------------------------------------------------------------------

    val FUEL_LEAK = LiveScreen(
        id = "fuel_leak",
        title = "Fuel leak test",
        subtitle = "High-pressure leak check after injector work",
        group = Screens.GROUP_SERVICE,
        module = Modules.ENGINE,
        keepAlive = TESTER_PRESENT,
        params = listOf(
            state("Test running", 0x35) { yesNo(it.u8(3) != 0) },
            ENGINE_RPM_2101,
            RAIL_TARGET,
            RAIL_ACTUAL,
        ),
        actions = listOf(
            ServiceAction(
                "Run fuel leak test",
                "Use after replacing injectors or high-pressure fuel lines.\n\n" +
                    "1. Engine running at idle, warmed up.\n" +
                    "2. Press Start: the ECU raises engine speed to about 2000 rpm and rail pressure to about 160 MPa.\n" +
                    "3. When the engine returns to idle, switch the ignition off.\n" +
                    "4. Check every high-pressure connection, the fuel lines and the rail for leaks.\n\n" +
                    "Keep hands and tools clear of the high-pressure system while it runs.",
                listOf(intArrayOf(0x30, 0x35, 0x07, 0xFF)),
                "Test started. \"Test running\" changes to No when the ECU finishes.",
            ),
        ),
    )

    val INJECTOR_TEST = LiveScreen(
        id = "injector_test",
        title = "Injector test",
        subtitle = "ECU test of each injector",
        group = Screens.GROUP_SERVICE,
        module = Modules.ENGINE,
        keepAlive = TESTER_PRESENT,
        params = listOf(
            LiveParam("Engine speed", 0x44) { "${it.u16(4)} rpm" },
            RAIL_ACTUAL,
        ) + (0..3).map { n -> state("Injector ${n + 1} test running", 0x30 + n) { yesNo(it.u8(3) != 0) } },
        actions = (0..3).map { n ->
            ServiceAction(
                "Test injector ${n + 1}",
                "Engine running at idle, warmed up, no electrical loads.\n\n" +
                    "The ECU runs its built-in test on injector ${n + 1} and ends it by itself. " +
                    "Watch the engine speed during the test and compare the results between injectors.",
                listOf(intArrayOf(0x30, 0x30 + n, 0x07, 0x00)),
                "Injector ${n + 1} test started.",
            )
        },
        note = "Run one injector at a time and wait until its test has finished.",
    )

    val PUMP_VALVE_INIT = LiveScreen(
        id = "pump_valve_init",
        title = "Fuel pump valve initialisation",
        subtitle = "After replacing the high-pressure pump",
        group = Screens.GROUP_SERVICE,
        module = Modules.ENGINE,
        keepAlive = TESTER_PRESENT,
        params = listOf(
            state("Initialisation state", 0x36) { if (it.u8(3) == 0xFF) "Initialised" else "Not initialised" },
            LiveParam("Learned state (2 = learned)", 0x4C) { "${it.u8(6)}" },
            LiveParam("Learned value", 0x4C) { "${it.u8(5) * 10 - 1280} mA" },
            LiveParam("Preset value", 0x4C) { "${it.u8(4) * 25} mA" },
        ),
        actions = listOf(
            ServiceAction(
                "Initialise pump valve",
                "Use only after replacing the high-pressure fuel pump.\n\n" +
                    "Conditions: accelerator released, coolant at 60 °C or more, fuel at 30 °C or more.\n\n" +
                    "1. Ignition ON, engine OFF.\n" +
                    "2. Press Start.\n" +
                    "3. When it is initialised, start the engine and let it idle until \"Learned state\" shows 2.",
                listOf(intArrayOf(0x30, 0x36, 0x07, 0x00)),
                "Initialisation started. Watch \"Initialisation state\", then start the engine and idle.",
            ),
        ),
    )

    val BATTERY_SENSOR = LiveScreen(
        id = "battery_sensor",
        title = "Battery current sensor calibration",
        subtitle = "After replacing the sensor or the engine ECU",
        group = Screens.GROUP_SERVICE,
        module = Modules.ENGINE,
        params = listOf(
            state("Calibration", 0x39) {
                when (it.u16(3)) {
                    0xFFFF -> "Running…"
                    0x0000 -> "Idle / finished"
                    else -> "%04X".format(it.u16(3))
                }
            },
        ),
        actions = listOf(
            ServiceAction(
                "Calibrate battery current sensor",
                "1. Ignition OFF. Disconnect the negative battery cable.\n" +
                    "2. Take the current sensor off the cable (leave its connector plugged in).\n" +
                    "3. Reconnect the cable to the battery WITHOUT the sensor on it.\n" +
                    "4. Ignition ON, engine OFF.\n" +
                    "5. Press Start and wait until Calibration shows \"Idle / finished\".\n" +
                    "6. Ignition OFF and refit the sensor.",
                listOf(intArrayOf(0x30, 0x39, 0x07, 0xFF)),
                "Calibration started. Wait until it shows \"Idle / finished\".",
                check = Live.ENGINE_ROUTINES_SUPPORTED,
            ),
        ),
        warning = READ_MANUAL,
    )

    // ---- Small injection quantity learning ------------------------------------------------

    private fun learningParams(atOilTemp: LiveParam, extra: List<LiveParam> = emptyList()) = listOf(
        state("Learning in progress", 0x37) { yesNo(it.u8(3) != 0) },
        LiveParam("Small qty map counter (must be 0)", 0xBE) { "${it.u8(4)}" },
        LiveParam("Small qty pressure counter (must be 0)", 0xBE) { "${it.u8(5)}" },
        ENGINE_RPM_2101,
        LiveParam("Vehicle speed", 0x01) { "${it.u8(3)} km/h" },
        LiveParam("Battery voltage", 0x01) { "${f2(it.u8(2) * 18.7 / 255)} V" },
        LiveParam("Coolant temperature", 0x02) { "${it.u8(3) - 40} °C" },
        atOilTemp,
        LiveParam("Fuel temperature", 0x49) { "${it.u8(6) - 40} °C" },
        LiveParam("Accelerator (main)", 0x08) { "${(it.u8(2) * 99.6 / 255).toInt()} %" },
        LiveParam("Accelerator (sub)", 0x08) { "${(it.u8(3) * 99.6 / 255).toInt()} %" },
        LiveParam("Small qty injection cylinder", 0x42) { "${it.u8(6) + 1}" },
        RAIL_TARGET,
        RAIL_ACTUAL,
    ) + extra

    private val SQL_CONDITIONS_4N15 = listOf(
        LiveParam("Learning conditions met", 0xC0) { bit(it, 15, 7) },
        LiveParam("Coolant condition met", 0xC0) { bit(it, 15, 6) },
        LiveParam("Fuel temperature condition met", 0xC0) { bit(it, 15, 5) },
        LiveParam("A/T oil temperature condition met", 0xC0) { bit(it, 15, 4) },
    )

    val SMALL_QTY_LEARNING = LiveScreen(
        id = "small_qty_learning",
        title = "Small injection quantity learning",
        subtitle = "Injector small-quantity learning at idle",
        group = Screens.GROUP_SERVICE,
        module = Modules.ENGINE,
        variants = listOf(
            Variant("4D56 / 4M41", learningParams(LiveParam("A/T oil temperature", 0x02, Modules.TRANSMISSION) { "${it.u8(7) - 40} °C" })),
            Variant("4N15", learningParams(LiveParam("A/T oil temperature", 0x02, Modules.TRANSMISSION) { "${it.u8(7) - 50} °C" }, SQL_CONDITIONS_4N15)),
            Variant("4N14", learningParams(LiveParam("A/T oil temperature", 0x03) { "${it.u8(2) - 40} °C" })),
        ),
        actions = listOf(
            ServiceAction(
                "Start learning",
                "Learning only starts when both small-quantity counters are 0 (this can take a few days of driving).\n\n" +
                    "At idle, all of these must hold for the whole procedure:\n" +
                    "• Coolant: 4D56 80–90 °C, 4M41 76–90 °C, 4N15/4N14 80–100 °C\n" +
                    "• A/T oil 60 °C or more (Pajero Sport II) / 25 °C or more (Pajero Sport III)\n" +
                    "• Fuel 30–100 °C, intake air −30 to 100 °C\n" +
                    "• Lights, A/C, fan and all accessories OFF\n" +
                    "• Neutral (manual) or P (automatic), steering wheel still\n\n" +
                    "If conditions are lost, learning stops; switch the ignition off for 30 seconds before trying again.",
                listOf(intArrayOf(0x30, 0x37, 0x07, 0xFF)),
                "Learning started. \"Learning in progress\" changes to No when it ends.",
            ),
        ),
    )

    /** Allowed learned-value range per pressure point P1…P5, by engine. */
    private val LIMITS = mapOf(
        "4D56 (Pajero Sport II)" to listOf(-0.11 to 0.11, -0.05 to 0.08, 0.0 to 0.07, 0.0 to 0.07, 0.0 to 0.07),
        "4M41" to listOf(-0.092 to 0.085, -0.066 to 0.066, -0.05 to 0.05, 0.0 to 0.04, 0.0 to 0.04),
        "4N15 / 4N14" to listOf(-0.121 to 0.07, -0.088 to 0.057, -0.047 to 0.023, -0.057 to 0.037, -0.053 to 0.033),
        "L200 4D56" to listOf(-0.1 to 0.13, -0.02 to 0.09, 0.0 to 0.08, 0.012 to 0.022, 0.03 to 0.04),
    )

    private fun learnedValues(limits: List<Pair<Double, Double>>): List<LiveParam> =
        (0..9).flatMap { n ->
            val point = n / 2 + 1
            val (lo, hi) = limits[point - 1]
            listOf(0, 1).map { k ->
                val cylinder = (n % 2) * 2 + k + 1
                LiveParam("P$point cylinder $cylinder", 0xB0 + n) {
                    val v = it.u16(2 + k * 2) / 1000.0 - 0.5
                    val ok = v in lo..hi
                    "${f3(v)}  ${if (ok) "✓" else "✗ (${f3(lo)}…${f3(hi)})"}"
                }
            }
        }

    val SMALL_QTY_VALUES = LiveScreen(
        id = "small_qty_values",
        title = "Small injection learned values",
        subtitle = "Per-cylinder corrections with limits",
        group = Screens.GROUP_SERVICE,
        module = Modules.ENGINE,
        variants = LIMITS.map { (name, limits) -> Variant(name, learnedValues(limits)) },
        note = "Learned correction for each pressure point (P1–P5) and cylinder. ✓ = within the limits for the selected engine. Choose the engine from the menu.",
        pollIntervalMs = 1_000,
    )

    // ---- Transmission ---------------------------------------------------------------------

    val AT_SPECIAL = LiveScreen(
        id = "at_special",
        title = "A/T special functions",
        subtitle = "Reset adaptations, INVECS-II stop",
        group = Screens.GROUP_SERVICE,
        module = Modules.TRANSMISSION,
        params = listOf(
            LiveParam("Learned values (8-speed / 4N15)", 0x20) { if (it.u8(2) and 1 == 1) "Learned" else "Default" },
            LiveParam("INVECS-II stop control", 0x04) { bit(it, 3, 7) },
        ),
        actions = listOf(
            ServiceAction(
                "Reset adaptations (4N15 / 8-speed)",
                "Resets the transmission's learned values on 4N15 vehicles.\n\nIgnition ON, engine NOT running.",
                listOf(intArrayOf(0x30, 0xF0, 0x07, 0x01)),
                "Adaptations reset. Switch the ignition off for 30 seconds before driving.",
            ),
            ServiceAction(
                "Reset adaptations (other A/T)",
                "Resets the transmission's learned values (4/5/6-speed units).\n\nIgnition ON, engine NOT running.",
                listOf(intArrayOf(0x31, 0x02)),
                "Adaptations reset. Switch the ignition off for 30 seconds before driving.",
            ),
            ServiceAction(
                "INVECS-II stop",
                "Stops INVECS-II adaptive shift control (not available on 4N15 vehicles).\n\nIgnition ON, engine NOT running.",
                listOf(intArrayOf(0x31, 0x01)),
                "Done. Switch the ignition off for 30 seconds before driving.",
            ),
        ),
        warning = "These functions reset what the transmission has learned. The engine must not be running. " + READ_MANUAL,
    )

    // ---- Chassis --------------------------------------------------------------------------

    val STEERING_ANGLE = LiveScreen(
        id = "sas",
        title = "Steering angle sensor",
        subtitle = "Calibration and initialisation",
        group = Screens.GROUP_SERVICE,
        module = module(0x622),
        requestDelayMs = 100,
        params = listOf(
            LiveParam("Sensor state", 0x03) {
                when (it.u8(2) and 3) {
                    1 -> "Calibrated"
                    2 -> "Not calibrated"
                    else -> "Unknown (${it.u8(2) and 3})"
                }
            },
        ),
        actions = listOf(
            ServiceAction(
                "Calibrate (set centre)",
                "Do this after a wheel alignment or after replacing the sensor.\n\n" +
                    "Vehicle on level ground, wheels straight ahead, steering wheel centred and still. Ignition ON.",
                listOf(intArrayOf(0x31, 0x01)),
                "Calibration done. \"Sensor state\" should show Calibrated.",
            ),
            ServiceAction(
                "Initialise NEW sensor",
                "ONLY for a newly fitted, never-initialised sensor.\n\n" +
                    "Initialising a sensor that is already initialised erases its zero point and can damage it. " +
                    "After initialising, run Calibrate.",
                listOf(intArrayOf(0x31, 0x02)),
                "Initialisation done. Now run Calibrate.",
            ),
        ),
        warning = READ_MANUAL,
    )

    val EPB = LiveScreen(
        id = "epb",
        title = "Electric parking brake",
        subtitle = "Service position for rear pad replacement",
        group = Screens.GROUP_SERVICE,
        module = module(0x610),
        requestDelayMs = 20,
        params = listOf(
            state("Brake motors", 0x12) { if (it.u8(3) != 0) "Moving…" else "Stopped" },
        ),
        actions = listOf(
            ServiceAction(
                "Retract for pad replacement",
                "Selector in P, ignition ON, engine OFF. Chock the wheels.\n\n" +
                    "The motors retract the pistons and stop by themselves (the brake warning lamp flashes). " +
                    "Wait until \"Brake motors\" shows Stopped. The pistons may still need pushing back fully by hand. " +
                    "Then switch the ignition off and replace the pads.",
                listOf(intArrayOf(0x30, 0x12, 0x07, 0x01)),
                "Retracting. Wait for \"Stopped\" before working on the brakes.",
            ),
            ServiceAction(
                "Exit service position",
                "Run after fitting the new pads. Ignition ON, engine OFF.\n\n" +
                    "Then apply the parking brake once: the motors adjust themselves and the warning lamp goes out.",
                listOf(intArrayOf(0x30, 0x12, 0x07, 0x00)),
                "Done. Apply the parking brake once to let the system adjust.",
            ),
        ),
        warning = READ_MANUAL,
    )

    val ALL: List<LiveScreen> = listOf(
        FUEL_LEAK,
        INJECTOR_TEST,
        PUMP_VALVE_INIT,
        SMALL_QTY_LEARNING,
        SMALL_QTY_VALUES,
        BATTERY_SENSOR,
        AT_SPECIAL,
        STEERING_ANGLE,
        EPB,
    )
}
