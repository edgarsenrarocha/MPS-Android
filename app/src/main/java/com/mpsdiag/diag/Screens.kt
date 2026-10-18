package com.mpsdiag.diag

import com.mpsdiag.diag.screens.EngineDataScreens
import com.mpsdiag.diag.screens.ModuleScreens
import com.mpsdiag.diag.screens.ServiceScreens

/** Every data-driven live screen, by id. */
object Screens {
    const val GROUP_ENGINE = "Engine"
    const val GROUP_DRIVETRAIN = "Transmission & 4WD"
    const val GROUP_CHASSIS = "Brakes, steering & body"
    const val GROUP_SERVICE = "Service functions"

    val ALL: List<LiveScreen> =
        EngineDataScreens.ALL + ObdScreen.SCREEN + ModuleScreens.ALL + ServiceScreens.ALL

    fun byId(id: String?): LiveScreen? = ALL.firstOrNull { it.id == id }
}
