package com.mpsdiag.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mpsdiag.R
import com.mpsdiag.Session
import com.mpsdiag.diag.Screens

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
        buildMenu()
    }

    /** One home-screen entry. [needsConnection] entries open the Connection screen first. */
    private class Item(val title: String, val subtitle: String, val needsConnection: Boolean = true, val open: () -> Intent)

    private fun menu(): List<Pair<String, List<Item>>> {
        val fixed = mapOf(
            Screens.GROUP_ENGINE to listOf(
                Item(getString(R.string.menu_dpf), getString(R.string.menu_dpf_desc)) { Intent(this, DpfActivity::class.java) },
                Item(getString(R.string.menu_oil), getString(R.string.menu_oil_desc)) { Intent(this, OilActivity::class.java) },
            ),
        )
        val sections = mutableListOf<Pair<String, List<Item>>>()
        sections += getString(R.string.section_diagnostics) to listOf(
            Item(getString(R.string.menu_dtc), getString(R.string.menu_dtc_desc)) { Intent(this, DtcActivity::class.java) },
        )
        val groups = listOf(Screens.GROUP_ENGINE, Screens.GROUP_DRIVETRAIN, Screens.GROUP_CHASSIS, Screens.GROUP_SERVICE)
        for (g in groups) {
            val items = fixed[g].orEmpty() + Screens.ALL.filter { it.group == g }.map { sc ->
                Item(sc.title, sc.subtitle) { LiveDataActivity.intent(this, sc.id) }
            }
            if (items.isNotEmpty()) sections += g to items
        }
        sections += getString(R.string.section_tools) to listOf(
            Item(getString(R.string.menu_connection), getString(R.string.menu_connection_desc), needsConnection = false) {
                Intent(this, ConnectionActivity::class.java)
            },
            Item(getString(R.string.menu_log), getString(R.string.menu_log_desc), needsConnection = false) {
                Intent(this, LogActivity::class.java)
            },
        )
        return sections
    }

    private fun buildMenu() {
        val list = findViewById<LinearLayout>(R.id.menuList)
        val inflater = LayoutInflater.from(this)
        for ((section, items) in menu()) {
            val header = inflater.inflate(R.layout.item_section_header, list, false) as TextView
            header.text = section
            list.addView(header)
            for (item in items) {
                val tile = inflater.inflate(R.layout.item_menu_tile, list, false)
                tile.findViewById<TextView>(R.id.tileTitle).text = item.title
                tile.findViewById<TextView>(R.id.tileSubtitle).text = item.subtitle
                tile.setOnClickListener {
                    if (item.needsConnection && !Session.isConnected) {
                        Toast.makeText(this, R.string.connect_first, Toast.LENGTH_SHORT).show()
                        openConnection()
                    } else {
                        startActivity(item.open())
                    }
                }
                list.addView(tile)
            }
        }
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

    private fun openConnection() {
        startActivity(Intent(this, ConnectionActivity::class.java))
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
