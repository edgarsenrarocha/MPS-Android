package com.mpsdiag.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.mpsdiag.R
import com.mpsdiag.Session
import com.mpsdiag.diag.InputField
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
    private lateinit var panel: LiveDataPanel
    private var shown: List<LiveParam> = emptyList()
    private var variant = 0
    private var variantKnown = false
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
                b.setOnClickListener { askInputs(action) }
                actionsBox.addView(b)
            }
        }

        variant = prefs().getInt(variantKey(), 0)
        variantKnown = prefs().contains(variantKey())
        applySelection()
        poller = Poller(this, screen.pollIntervalMs, { d -> Live.poll(d, screen, shown, skips) }) { result ->
            result.onSuccess {
                panel.update(it)
                if (!gotData) showLiveStatus()
                gotData = true
            }.onFailure {
                status.text = getString(R.string.module_not_responding, screen.module.name, it.message)
            }
            updateButtons()
        }

        val warning = screen.warning
        if (warning != null && savedInstanceState == null) {
            AlertDialog.Builder(this)
                .setTitle(screen.title)
                .setMessage(warning)
                .setCancelable(false)
                .setPositiveButton(R.string.continue_label, null)
                .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!::poller.isInitialized) return
        val detect = screen.detectVariant
        if (detect != null && screen.variants.isNotEmpty()) {
            Session.run({ d ->
                Live.open(d, screen)
                detect(d)
            }) { r ->
                r.getOrNull()?.let { setVariant(it, remember = false) }
                poller.start()
            }
        } else {
            poller.start()
        }
    }

    override fun onStop() {
        if (::poller.isInitialized) poller.stop()
        super.onStop()
    }

    private fun showLiveStatus() {
        status.text = if (screen.variants.isEmpty()) getString(R.string.live)
        else getString(R.string.live_variant, screen.variants[variant].name)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (screen.variants.size > 1) menu.add(0, MENU_VARIANT, 0, R.string.choose_variant)
        if (screen.paramsFor(variant).size > 1) menu.add(0, MENU_CHOOSE, 1, R.string.choose_values)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_VARIANT -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.choose_variant)
                    .setSingleChoiceItems(screen.variants.map { it.name }.toTypedArray(), variant) { d, which ->
                        setVariant(which, remember = true)
                        d.dismiss()
                    }
                    .show()
            }
            MENU_CHOOSE -> chooseValues()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun setVariant(v: Int, remember: Boolean) {
        if (remember) prefs().edit().putInt(variantKey(), v).apply()
        variantKnown = true
        if (v == variant && ::panel.isInitialized) return
        variant = v
        applySelection()
        if (gotData) showLiveStatus()
        invalidateOptionsMenu()
    }

    private fun chooseValues() {
        val all = screen.paramsFor(variant)
        val labels = all.map { it.label }.toTypedArray()
        val checked = BooleanArray(labels.size) { all[it] in shown }
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
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun selectionKey() = "values_${screen.id}_$variant"
    private fun variantKey() = "variant_${screen.id}"

    /** Shows only the values the user picked (all by default); fewer values refresh faster. */
    private fun applySelection() {
        val all = screen.paramsFor(variant)
        val saved = prefs().getStringSet(selectionKey(), null)
        shown = if (saved.isNullOrEmpty()) all else all.filter { it.label in saved }.ifEmpty { all }
        skips.clear()
        panel = LiveDataPanel(paramList, shown.map { it.label })
    }

    private fun updateButtons() {
        val ready = gotData && !busy && Session.isConnected
        actionButtons.forEach { it.isEnabled = ready }
    }

    /** Collects the action's inputs (if any), then asks for confirmation. */
    private fun askInputs(action: ServiceAction) {
        if (action.inputs.isEmpty()) {
            confirm(action, emptyList())
            return
        }
        val pad = resources.getDimensionPixelSize(R.dimen.screen_padding)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val readers = action.inputs.map { field -> addInput(box, field) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(action.title)
            .setView(box)
            .setPositiveButton(R.string.next, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = readers.map { it() }
                val problem = action.inputs.zip(values).firstNotNullOfOrNull { (f, v) -> f.validate(v) }
                if (problem != null) {
                    AlertDialog.Builder(this).setMessage(problem).setPositiveButton(android.R.string.ok, null).show()
                } else {
                    dialog.dismiss()
                    confirm(action, values.map { it!! })
                }
            }
        }
        dialog.show()
    }

    /** Adds a labelled field for [field] to [box] and returns a reader for its value. */
    private fun addInput(box: LinearLayout, field: InputField): () -> Int? {
        box.addView(TextView(this).apply {
            text = field.label
            setTextAppearance(R.style.Text_Secondary)
        })
        val choices = field.choices
        if (choices != null) {
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
            box.addView(spinner)
            return { spinner.selectedItemPosition }
        }
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = field.hint ?: "${field.min} – ${field.max}"
        }
        box.addView(edit)
        return { edit.text.toString().trim().toIntOrNull() }
    }

    private fun confirm(action: ServiceAction, values: List<Int>) {
        AlertDialog.Builder(this)
            .setTitle(action.title)
            .setMessage(action.instructions)
            .setPositiveButton(R.string.start) { _, _ -> run(action, values) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Sends the action once (never repeated automatically) and reports the outcome. */
    private fun run(action: ServiceAction, values: List<Int>) {
        busy = true
        updateButtons()
        status.setText(R.string.working)
        Session.run({ d -> Live.run(d, screen, action, values) }) { result ->
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
        private const val MENU_VARIANT = 2

        fun intent(context: Context, screenId: String): Intent =
            Intent(context, LiveDataActivity::class.java).putExtra(EXTRA_SCREEN, screenId)
    }
}
