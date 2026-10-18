package com.mpsdiag.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.mpsdiag.R
import com.mpsdiag.Session
import com.mpsdiag.diag.Dpf
import com.mpsdiag.diag.Modules
import com.mpsdiag.diag.Oil

class OilActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var resetButton: Button
    private lateinit var panel: LiveDataPanel
    private lateinit var poller: Poller<List<String>>
    private var gotData = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_oil)
        status = findViewById(R.id.statusText)
        resetButton = findViewById(R.id.resetButton)
        panel = LiveDataPanel(findViewById<LinearLayout>(R.id.paramList), Oil.PARAMS.map { it.label })

        poller = Poller(this, POLL_INTERVAL_MS, { d ->
            d.open(Modules.ENGINE)
            Dpf.poll(d, Oil.PARAMS)
        }) { result ->
            result.onSuccess {
                panel.update(it)
                if (!gotData) status.setText(R.string.live)
                gotData = true
            }.onFailure {
                status.text = getString(R.string.not_responding, it.message)
            }
            updateButton()
        }

        resetButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.oil_reset)
                .setMessage(R.string.oil_confirm)
                .setPositiveButton(R.string.start) { _, _ -> reset() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        poller.start()
    }

    override fun onStop() {
        poller.stop()
        super.onStop()
    }

    private fun updateButton() {
        resetButton.isEnabled = gotData && !busy && Session.isConnected
    }

    private fun reset() {
        busy = true
        updateButton()
        status.setText(R.string.working)
        Session.run({ Oil.reset(it) }) { result ->
            busy = false
            val message = result.fold(
                onSuccess = { getString(R.string.oil_done) },
                onFailure = { getString(R.string.error_prefix, it.message) },
            )
            status.text = message
            AlertDialog.Builder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
            updateButton()
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_000L
    }
}
