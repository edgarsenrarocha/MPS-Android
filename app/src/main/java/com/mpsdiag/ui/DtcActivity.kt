package com.mpsdiag.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.mpsdiag.R
import com.mpsdiag.Session
import com.mpsdiag.diag.Dtc
import com.mpsdiag.diag.DtcService
import com.mpsdiag.diag.Module
import com.mpsdiag.diag.Modules
import com.mpsdiag.diag.NoResponseException
import java.io.IOException

class DtcActivity : Activity() {

    /** What one module answered. */
    private sealed class Outcome {
        class Codes(val codes: List<Dtc>) : Outcome()
        object NoAnswer : Outcome()
        class Failed(val message: String) : Outcome()
    }

    private lateinit var spinner: Spinner
    private lateinit var status: TextView
    private lateinit var resultList: LinearLayout
    private lateinit var shareButton: Button
    private lateinit var buttons: List<Button>
    private var report = ""

    private val selected: Module get() = Modules.ALL[spinner.selectedItemPosition]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dtc)
        spinner = findViewById(R.id.moduleSpinner)
        status = findViewById(R.id.statusText)
        resultList = findViewById(R.id.resultList)
        shareButton = findViewById(R.id.shareButton)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Modules.ALL.map { it.name })

        val read = findViewById<Button>(R.id.readButton)
        val clear = findViewById<Button>(R.id.clearButton)
        val scan = findViewById<Button>(R.id.scanAllButton)
        buttons = listOf(read, clear, scan)

        read.setOnClickListener { read(selected) }
        clear.setOnClickListener { confirmClear(selected) }
        scan.setOnClickListener { scanAll() }
        shareButton.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name) + " – " + getString(R.string.menu_dtc))
                .putExtra(Intent.EXTRA_TEXT, report)
            startActivity(Intent.createChooser(send, getString(R.string.dtc_share)))
        }
    }

    private fun setBusy(busy: Boolean) {
        buttons.forEach { it.isEnabled = !busy }
        spinner.isEnabled = !busy
    }

    private fun outcomeOf(module: Module, diag: com.mpsdiag.diag.Diag): Outcome = try {
        Outcome.Codes(DtcService.read(diag, module))
    } catch (_: NoResponseException) {
        Outcome.NoAnswer
    } catch (e: IOException) {
        Outcome.Failed(e.message ?: e.javaClass.simpleName)
    }

    // ---- Rendering ---------------------------------------------------------------------------

    private fun statusLabel(s: Dtc.Status) = when (s) {
        Dtc.Status.CURRENT -> R.string.dtc_status_current to R.color.status_error
        Dtc.Status.PENDING -> R.string.dtc_status_pending to R.color.status_warn
        Dtc.Status.STORED -> R.string.dtc_status_stored to R.color.status_idle
    }

    /** Shows results as one card per code, plus summary lines for quiet modules. */
    private fun render(results: List<Pair<Module, Outcome>>, prefix: String? = null) {
        resultList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val text = StringBuilder()
        var codeCount = 0
        var moduleCount = 0
        for ((module, outcome) in results) {
            if (outcome !is Outcome.Codes || outcome.codes.isEmpty()) continue
            moduleCount++
            text.append(module.name).append('\n')
            for (dtc in outcome.codes) {
                codeCount++
                val (label, color) = statusLabel(dtc.status)
                val card = inflater.inflate(R.layout.item_dtc, resultList, false)
                card.findViewById<TextView>(R.id.dtcCode).text = dtc.code
                card.findViewById<TextView>(R.id.dtcModule).text = module.name
                card.findViewById<TextView>(R.id.dtcStatus).apply {
                    setText(label)
                    backgroundTintList = ColorStateList.valueOf(getColor(color))
                }
                card.findViewById<View>(R.id.dtcBar).backgroundTintList = ColorStateList.valueOf(getColor(color))
                val description = DtcService.KNOWN[dtc.code]
                card.findViewById<TextView>(R.id.dtcDescription).apply {
                    this.text = description
                    visibility = if (description == null) View.GONE else View.VISIBLE
                }
                resultList.addView(card)
                text.append("  ").append(dtc.code).append(" (").append(getString(label).lowercase()).append(')')
                description?.let { text.append(" – ").append(it) }
                text.append('\n')
            }
        }

        val clean = results.filter { (it.second as? Outcome.Codes)?.codes?.isEmpty() == true }.map { it.first.name }
        val silent = results.filter { it.second is Outcome.NoAnswer }.map { it.first.name }
        val failed = results.mapNotNull { (m, o) -> (o as? Outcome.Failed)?.let { "${m.name}: ${it.message}" } }
        fun summary(line: String) {
            val v = TextView(this).apply {
                this.text = line
                setTextAppearance(R.style.Text_Secondary)
                setBackgroundResource(R.drawable.bg_card)
                val pad = resources.getDimensionPixelSize(R.dimen.card_padding)
                setPadding(pad, pad, pad, pad)
            }
            resultList.addView(v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.gap_small) })
            text.append(line).append('\n')
        }
        if (clean.isNotEmpty()) summary(getString(R.string.dtc_no_faults_in, clean.joinToString(", ")))
        if (silent.isNotEmpty()) summary(getString(R.string.dtc_no_answer_from, silent.joinToString(", ")))
        failed.forEach { summary(getString(R.string.error_prefix, it)) }

        val headline = if (codeCount == 0) getString(R.string.dtc_summary_none)
        else getString(R.string.dtc_summary, codeCount, moduleCount)
        status.text = listOfNotNull(prefix, headline).joinToString("\n")
        report = headline + "\n\n" + text.toString().trim()
        shareButton.visibility = View.VISIBLE
    }

    // ---- Actions -----------------------------------------------------------------------------

    private fun read(module: Module, prefix: String? = null) {
        setBusy(true)
        status.setText(R.string.working)
        Session.run({ d -> outcomeOf(module, d) }) { r ->
            setBusy(false)
            r.onSuccess { render(listOf(module to it), prefix) }
                .onFailure { status.text = getString(R.string.error_prefix, it.message) }
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
        status.setText(R.string.working)
        Session.run({ DtcService.clear(it, module) }) { r ->
            setBusy(false)
            r.onSuccess {
                // Read back so the user sees what (if anything) came straight back.
                read(module, getString(R.string.dtc_cleared, module.name))
            }.onFailure {
                status.text = getString(R.string.dtc_clear_failed, module.name, it.message)
            }
        }
    }

    private fun scanAll() {
        setBusy(true)
        resultList.removeAllViews()
        shareButton.visibility = View.GONE
        val modules = Modules.SCAN_ALL
        val results = mutableListOf<Pair<Module, Outcome>>()
        modules.forEachIndexed { i, module ->
            Session.run({ d ->
                Session.postMain { status.text = getString(R.string.dtc_scanning, module.name, i + 1, modules.size) }
                outcomeOf(module, d)
            }) { r ->
                results += module to (r.getOrNull() ?: Outcome.Failed(r.exceptionOrNull()?.message ?: "error"))
                if (i == modules.lastIndex || r.isFailure && !Session.isConnected) {
                    setBusy(false)
                    render(results)
                }
            }
        }
    }
}
