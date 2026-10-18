package com.mpsdiag.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.mpsdiag.R
import com.mpsdiag.Session

class MainActivity : Activity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private val sessionListener = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)

        connectButton.setOnClickListener { openConnection() }
        findViewById<View>(R.id.statusCard).setOnClickListener { openConnection() }
        tile(R.id.tileDpf, R.string.menu_dpf, R.string.menu_dpf_desc) { openIfConnected(DpfActivity::class.java) }
        tile(R.id.tileOil, R.string.menu_oil, R.string.menu_oil_desc) { openIfConnected(OilActivity::class.java) }
        tile(R.id.tileDtc, R.string.menu_dtc, R.string.menu_dtc_desc) { openIfConnected(DtcActivity::class.java) }
        tile(R.id.tileLog, R.string.menu_log, R.string.menu_log_desc) { startActivity(Intent(this, LogActivity::class.java)) }
    }

    override fun onStart() {
        super.onStart()
        Session.addListener(sessionListener)
        refresh()
    }

    override fun onStop() {
        Session.removeListener(sessionListener)
        super.onStop()
    }

    private fun tile(id: Int, title: Int, subtitle: Int, onClick: () -> Unit) {
        val v = findViewById<View>(id)
        v.findViewById<TextView>(R.id.tileTitle).setText(title)
        v.findViewById<TextView>(R.id.tileSubtitle).setText(subtitle)
        v.setOnClickListener { onClick() }
    }

    private fun openConnection() {
        startActivity(Intent(this, ConnectionActivity::class.java))
    }

    private fun openIfConnected(cls: Class<out Activity>) {
        if (!Session.isConnected) {
            Toast.makeText(this, R.string.connect_first, Toast.LENGTH_SHORT).show()
            openConnection()
            return
        }
        startActivity(Intent(this, cls))
    }

    private fun refresh() {
        val connecting = Session.connectingTo
        val t = Session.transport
        val (color, text) = when {
            connecting != null -> R.color.status_warn to getString(R.string.connecting, connecting)
            Session.isConnected && t != null ->
                R.color.status_ok to getString(R.string.connected_to, "${t.displayName} · ${Session.elm?.version.orEmpty()}")
            else -> R.color.status_idle to getString(R.string.not_connected)
        }
        (statusDot.background.mutate() as GradientDrawable).setColor(getColor(color))
        statusText.text = text
        connectButton.setText(if (Session.isConnected) R.string.menu_connection else R.string.connect)
    }
}
