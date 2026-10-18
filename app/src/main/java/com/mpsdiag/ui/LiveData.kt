package com.mpsdiag.ui

import android.app.Activity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.mpsdiag.R
import com.mpsdiag.Session
import com.mpsdiag.diag.Diag

/** Fills [container] with one "label / value" row per label and updates the values in place. */
class LiveDataPanel(container: LinearLayout, labels: List<String>) {

    private val values: List<TextView>

    init {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        values = labels.map { label ->
            val row = inflater.inflate(R.layout.item_param, container, false)
            row.findViewById<TextView>(R.id.paramLabel).text = label
            container.addView(row)
            row.findViewById(R.id.paramValue)
        }
    }

    fun update(newValues: List<String>) {
        newValues.forEachIndexed { i, v -> if (i < values.size) values[i].text = v }
    }
}

/**
 * Repeats [read] on the adapter thread while started, pausing [intervalMs] between rounds.
 * Other requests (e.g. a button press) queue up behind the current round, so they never
 * collide with polling on the wire.
 */
class Poller<T>(
    private val activity: Activity,
    private val intervalMs: Long,
    private val read: (Diag) -> T,
    private val onResult: (Result<T>) -> Unit,
) {
    private var running = false
    private var scheduled = false

    fun start() {
        if (running) return
        running = true
        next()
    }

    fun stop() {
        running = false
    }

    private fun next() {
        if (!running || scheduled) return
        scheduled = true
        Session.run(read) { result ->
            scheduled = false
            if (!running || activity.isFinishing) return@run
            onResult(result)
            if (result.isFailure && !Session.isConnected) {
                running = false
                return@run
            }
            activity.window.decorView.postDelayed({ next() }, intervalMs)
        }
    }
}
