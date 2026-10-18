package com.mpsdiag.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import com.mpsdiag.R
import com.mpsdiag.Session
import com.mpsdiag.diag.Dtc
import com.mpsdiag.diag.DtcService
import com.mpsdiag.diag.Module
import com.mpsdiag.diag.Modules
import java.io.IOException

class DtcActivity : Activity() {

    private lateinit var spinner: Spinner
    private lateinit var result: TextView
    private lateinit var buttons: List<Button>

    private val selected: Module get() = Modules.ALL[spinner.selectedItemPosition]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dtc)
        spinner = findViewById(R.id.moduleSpinner)
        result = findViewById(R.id.resultText)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Modules.ALL.map { it.name })

        val read = findViewById<Button>(R.id.readButton)
        val clear = findViewById<Button>(R.id.clearButton)
        val scan = findViewById<Button>(R.id.scanAllButton)
        buttons = listOf(read, clear, scan)

        read.setOnClickListener { read(selected) }
        clear.setOnClickListener { confirmClear(selected) }
        scan.setOnClickListener { scanAll() }
    }

    private fun setBusy(busy: Boolean) {
        buttons.forEach { it.isEnabled = !busy }
        spinner.isEnabled = !busy
    }

    private fun describe(module: Module, codes: List<Dtc>): String {
        if (codes.isEmpty()) return getString(R.string.dtc_none, module.name)
        val sb = StringBuilder(getString(R.string.dtc_found, module.name, codes.size))
        for (c in codes) {
            sb.append("\n\n").append(c.code).append("  (").append(c.status.label).append(')')
            DtcService.KNOWN[c.code]?.let { sb.append('\n').append(it) }
        }
        return sb.toString()
    }

    private fun read(module: Module, prefix: String = "") {
        setBusy(true)
        result.setText(R.string.working)
        Session.run({ DtcService.read(it, module) }) { r ->
            setBusy(false)
            result.text = prefix + r.fold(
                onSuccess = { describe(module, it) },
                onFailure = { getString(R.string.error_prefix, it.message) },
            )
        }
    }

    private fun confirmClear(module: Module) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dtc_clear)
            .setMessage(getString(R.string.dtc_clear_confirm, module.name))
            .setPositiveButton(R.string.dtc_clear) { _, _ -> clear(module) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clear(module: Module) {
        setBusy(true)
        result.setText(R.string.working)
        Session.run({ DtcService.clear(it, module) }) { r ->
            setBusy(false)
            r.onSuccess {
                // Read back so the user sees what (if anything) came straight back.
                read(module, getString(R.string.dtc_cleared, module.name) + "\n\n")
            }.onFailure {
                result.text = getString(R.string.dtc_clear_failed, module.name, it.message)
            }
        }
    }

    private fun scanAll() {
        setBusy(true)
        val modules = Modules.SCAN_ALL
        val report = StringBuilder()
        modules.forEachIndexed { i, module ->
            Session.run({ d ->
                Session.postMain { result.text = getString(R.string.dtc_scanning, module.name, i + 1, modules.size) }
                try {
                    describe(module, DtcService.read(d, module))
                } catch (e: IOException) {
                    "${module.name}: ${getString(R.string.dtc_no_answer)}"
                }
            }) { r ->
                r.onSuccess { report.append(it).append("\n\n") }
                if (i == modules.lastIndex || r.isFailure && !Session.isConnected) {
                    setBusy(false)
                    result.text = report.toString().trim()
                }
            }
        }
    }
}
