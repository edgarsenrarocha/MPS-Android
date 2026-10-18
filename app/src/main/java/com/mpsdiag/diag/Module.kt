package com.mpsdiag.diag

/**
 * A control unit on the CAN bus (11-bit, 500 kbit/s).
 *
 * @param customFlowControl the engine and transmission ECUs work with the ELM327's default
 *        flow control; every other Mitsubishi module needs it set explicitly.
 * @param obd generic OBD-II (functional request 7DF, services 03/07/04) instead of the
 *        Mitsubishi diagnostic session with services 18/14.
 */
data class Module(
    val name: String,
    val requestId: Int,
    val responseId: Int,
    val customFlowControl: Boolean = true,
    val obd: Boolean = false,
)

object Modules {
    val ENGINE = Module("Engine (ECM)", 0x7E0, 0x7E8, customFlowControl = false)
    val TRANSMISSION = Module("Automatic transmission", 0x7E1, 0x7E9, customFlowControl = false)
    val OBD2 = Module("Generic OBD-II (emissions)", 0x7DF, 0x7E8, customFlowControl = false, obd = true)

    /** Modules offered for reading/clearing fault codes, in menu order. */
    val ALL: List<Module> = listOf(
        ENGINE,
        TRANSMISSION,
        Module("ABS / ASC", 0x784, 0x785),
        Module("SRS airbag (Pajero Sport KS)", 0x6E0, 0x51C),
        Module("SRS airbag (Pajero IV)", 0x78C, 0x78D),
        Module("TPMS / keyless (KOS)", 0x773, 0x774),
        Module("ETACS body control (Pajero Sport KH)", 0x7A0, 0x7A1),
        Module("ETACS body control (Pajero Sport KS)", 0x620, 0x504),
        Module("Air conditioning (Pajero Sport KH)", 0x7A2, 0x7A3),
        Module("Air conditioning (Pajero Sport KS)", 0x688, 0x511),
        Module("Steering angle sensor", 0x622, 0x484),
        Module("Super Select 4WD II (Pajero Sport)", 0x7B6, 0x7B7),
        Module("Super Select 4WD II (Pajero IV)", 0x786, 0x787),
        Module("Electric parking brake", 0x610, 0x611),
        Module("Instrument cluster", 0x6A0, 0x514),
        Module("Adaptive headlights (AFS/ACL)", 0x77B, 0x77A),
        Module("Forward collision / cruise (FCM/ACC)", 0x718, 0x719),
        Module("Audio", 0x7A8, 0x535),
        Module("e-Call", 0x74D, 0x74E),
        Module("Gateway", 0x71C, 0x71D),
        Module("Parking / turn sensors", 0x763, 0x764),
        Module("Multimedia monitor", 0x790, 0x532),
        OBD2,
    )

    /** Modules visited by "Scan all" (each CAN id pair once; generic OBD-II last). */
    val SCAN_ALL: List<Module> = ALL.distinctBy { it.requestId to it.responseId }
}
