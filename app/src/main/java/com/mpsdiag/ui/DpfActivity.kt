package com.mpsdiag.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.mpsdiag.R
import com.mpsdiag.Session
import com.mpsdiag.diag.DieselEngine
import com.mpsdiag.diag.Dpf
import com.mpsdiag.diag.DpfService
import com.mpsdiag.diag.Modules

class DpfActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var vehicle: TextView
    private lateinit var regenButton: Button
    private lateinit var serviceButton: Button
    private lateinit var panel: LiveDataPanel
    private lateinit var poller: Poller<List<String>>

    private var supported = false
    private var engine: DieselEngine? = null
    private var gotData = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dpf)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = findViewById(R.id.statusText)
        vehicle = findViewById(R.id.vehicleText)
        regenButton = findViewById(R.id.regenButton)
        serviceButton = findViewById(R.id.serviceButton)
        panel = LiveDataPanel(findViewById<LinearLayout>(R.id.paramList), Dpf.PARAMS.map { it.label })

        poller = Poller(this, POLL_INTERVAL_MS, { d ->
            d.open(Modules.ENGINE) // (re)opens the diagnostic session; also keeps it alive
            Dpf.poll(d, Dpf.PARAMS)
        }) { result ->
            result.onSuccess {
                panel.update(it)
                if (!gotData) status.setText(R.string.live)
                gotData = true
            }.onFailure {
                status.text = getString(R.string.not_responding, it.message)
            }
            updateButtons()
        }

        regenButton.setOnClickListener { pickEngine() }
        serviceButton.setOnClickListener { pickService() }
    }

    override fun onStart() {
        super.onStart()
        Session.run({ d ->
            val ok = Dpf.openEngine(d)
            val vin = Dpf.readVin(d)
            Triple(ok, vin, Dpf.engineFromVin(vin))
        }) { result ->
            result.onSuccess { (ok, vin, guess) ->
                supported = ok
                engine = engine ?: guess
                vehicle.text = when {
                    vin != null -> getString(R.string.dpf_vehicle, vin, engine?.label ?: "?")
                    else -> getString(R.string.dpf_vehicle_unknown)
                }
                if (!ok) vehicle.append("\n" + getString(R.string.dpf_not_supported))
            }.onFailure {
                status.text = getString(R.string.not_responding, it.message)
            }
            updateButtons()
        }
        poller.start()
    }

    override fun onStop() {
        poller.stop()
        super.onStop()
    }

    private fun updateButtons() {
        val ready = supported && gotData && !busy && Session.isConnected
        regenButton.isEnabled = ready
        serviceButton.isEnabled = ready
    }

    private fun pickEngine() {
        val engines = DieselEngine.values()
        var choice = engines.indexOf(engine).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.dpf_pick_engine)
            .setSingleChoiceItems(engines.map { it.label }.toTypedArray(), choice) { _, which -> choice = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                engine = engines[choice]
                confirmRegeneration(engines[choice])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRegeneration(e: DieselEngine) {
        val warning = when (e) {
            DieselEngine.E4N15 -> R.string.dpf_regen_warning_4n15
            DieselEngine.E4N14 -> R.string.dpf_regen_warning_4n14
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dpf_regen_title, e.label))
            .setMessage(warning)
            .setPositiveButton(R.string.start) { _, _ -> runOnce({ d -> Dpf.startRegeneration(d, e) }, R.string.dpf_regen_sent) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickService() {
        val services = DpfService.values()
        AlertDialog.Builder(this)
            .setTitle(R.string.dpf_pick_service)
            .setItems(services.map { it.label }.toTypedArray()) { _, which ->
                val s = services[which]
                AlertDialog.Builder(this)
                    .setTitle(R.string.dpf_pick_service)
                    .setMessage(getString(R.string.dpf_service_confirm, s.label))
                    .setPositiveButton(R.string.start) { _, _ -> runOnce({ d -> Dpf.serviceReset(d, s) }, R.string.dpf_service_done) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Sends a one-shot command (never repeated automatically) and reports the outcome. */
    private fun runOnce(action: (com.mpsdiag.diag.Diag) -> Unit, successMessage: Int) {
        busy = true
        updateButtons()
        status.setText(R.string.working)
        Session.run(action) { result ->
            busy = false
            val message = result.fold(
                onSuccess = { getString(successMessage) },
                onFailure = { getString(R.string.error_prefix, it.message) },
            )
            status.text = message
            AlertDialog.Builder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
            updateButtons()
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 300L
    }
}
