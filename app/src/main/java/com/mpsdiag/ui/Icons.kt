package com.mpsdiag.ui

import com.mpsdiag.R

/** Home-screen icon for each live screen. */
object Icons {
    fun forScreen(id: String): Int = when (id) {
        "diesel", "petrol", "cross", "xpander" -> R.drawable.ic_engine
        "obd", "meter" -> R.drawable.ic_gauge
        "transmission", "at_special" -> R.drawable.ic_gearbox
        "transfer" -> R.drawable.ic_awd
        "abs", "epb" -> R.drawable.ic_brake
        "sas" -> R.drawable.ic_steering
        "battery_sensor" -> R.drawable.ic_battery
        "fuel_leak", "injector_test", "pump_valve_init", "small_qty_learning", "small_qty_values" -> R.drawable.ic_fuel
        else -> R.drawable.ic_wrench
    }
}
