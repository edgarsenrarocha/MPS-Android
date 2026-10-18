package com.mpsdiag.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.mpsdiag.R
import com.mpsdiag.Session
import com.mpsdiag.diag.Live
import com.mpsdiag.diag.LiveParam
import com.mpsdiag.diag.LiveScreen
import com.mpsdiag.diag.Screens
import com.mpsdiag.diag.ServiceAction

/** Shows any [LiveScreen]: live values from one module plus its service actions. */
class LiveDataActivity : Activity() {

    private lateinit var screen: LiveScreen
    private lateinit var status: TextView
    private lateinit var paramList: LinearLayout
    private lateinit var actionButtons: List<Button>
    private lateinit var poller: Poller<List<String>>
    private var shown: List<LiveParam> = emptyList()
    private lateinit var panel: LiveDataPanel
    private var gotData = false
    private var busy = false
    private val skips = Live.Skips()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = Screens.byId(intent.getStringExtra(EXTRA_SCREEN)) ?: run {
            finish()
            return
        }
        setContentView(R.layout.activity_live)
        title = screen.title
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = findViewById(R.id.statusText)
        paramList = findViewById(R.id.paramList)

        findViewById<TextView>(R.id.noteText).apply {
            text = screen.note
            visibility = if (screen.note.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        val actionsBox = findViewById<LinearLayout>(R.id.actionList)
        val inflater = LayoutInflater.from(this)
        actionButtons = screen.actions.map { action ->
            (inflater.inflate(R.layout.item_action_button, actionsBox, false) as Button).also { b ->
                b.text = action.title
                b.isEnabled = false
                b.setOnClickListener { confirm(action) }
                actionsBox.addView(b)
            }
        }

        applySelection()
        poller = Poller(this, screen.pollIntervalMs, { d ->
            d.open(screen.module) // (re)opens the session; also keeps it alive
            Live.poll(d, shown, skips)
        }) { result ->
            result.onSuccess {
                panel.update(it)
                if (!gotData) status.setText(R.string.live)
                gotData = true
            }.onFailure {
                status.text = getString(R.string.module_not_responding, screen.module.name, it.message)
            }
            updateButtons()
        }
    }

    override fun onStart() {
        super.onStart()
        if (::poller.isInitialized) poller.start()
    }

    override fun onStop() {
        if (::poller.isInitialized) poller.stop()
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (screen.params.size > 1) menu.add(0, MENU_CHOOSE, 0, R.string.choose_values)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != MENU_CHOOSE) return super.onOptionsItemSelected(item)
        val labels = screen.params.map { it.label }.toTypedArray()
        val checked = BooleanArray(labels.size) { screen.params[it] in shown }
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_values)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val keep = labels.filterIndexed { i, _ -> checked[i] }.toSet()
                prefs().edit().putStringSet(selectionKey(), keep).apply()
                applySelection()
            }
            .setNeutralButton(R.string.select_all) { _, _ ->
                prefs().edit().remove(selectionKey()).apply()
                applySelection()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun selectionKey() = "values_${screen.id}"

    /** Shows only the values the user picked (all by default); fewer values refresh faster. */
    private fun applySelection() {
        val saved = prefs().getStringSet(selectionKey(), null)
        shown = if (saved.isNullOrEmpty()) screen.params else screen.params.filter { it.label in saved }
        skips.clear()
        panel = LiveDataPanel(paramList, shown.map { it.label })
    }

    private fun updateButtons() {
        val ready = gotData && !busy && Session.isConnected
        actionButtons.forEach { it.isEnabled = ready }
    }

    private fun confirm(action: ServiceAction) {
        AlertDialog.Builder(this)
            .setTitle(action.title)
            .setMessage(action.instructions)
            .setPositiveButton(R.string.start) { _, _ -> run(action) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Sends the action once (never repeated automatically) and reports the outcome. */
    private fun run(action: ServiceAction) {
        busy = true
        updateButtons()
        status.setText(R.string.working)
        Session.run({ d -> Live.run(d, screen.module, action) }) { result ->
            busy = false
            val message = result.fold(
                onSuccess = { action.successMessage },
                onFailure = { getString(R.string.error_prefix, it.message) },
            )
            status.text = message
            AlertDialog.Builder(this).setTitle(action.title).setMessage(message)
                .setPositiveButton(android.R.string.ok, null).show()
            updateButtons()
        }
    }

    companion object {
        private const val EXTRA_SCREEN = "screen"
        private const val PREFS = "settings"
        private const val MENU_CHOOSE = 1

        fun intent(context: Context, screenId: String): Intent =
            Intent(context, LiveDataActivity::class.java).putExtra(EXTRA_SCREEN, screenId)
    }
}
