package com.mpsdiag.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mpsdiag.R
import com.mpsdiag.Session
import com.mpsdiag.diag.Screens

class MainActivity : Activity() {

    private lateinit var statusChip: TextView
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private val sessionListener = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusChip = findViewById(R.id.statusChip)
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)

        connectButton.setOnClickListener { openConnection() }
        findViewById<View>(R.id.statusCard).setOnClickListener { openConnection() }
        buildMenu()
    }

    /** One home-screen entry. [needsConnection] entries open the Connection screen first. */
    private class Item(
        val title: String,
        val subtitle: String,
        val icon: Int,
        val needsConnection: Boolean = true,
        val open: () -> Intent,
    )

    private fun menu(): List<Pair<String, List<Item>>> {
        val fixed = mapOf(
            Screens.GROUP_ENGINE to listOf(
                Item(getString(R.string.menu_dpf), getString(R.string.menu_dpf_desc), R.drawable.ic_dpf) { Intent(this, DpfActivity::class.java) },
                Item(getString(R.string.menu_oil), getString(R.string.menu_oil_desc), R.drawable.ic_oil) { Intent(this, OilActivity::class.java) },
            ),
        )
        val sections = mutableListOf<Pair<String, List<Item>>>()
        sections += getString(R.string.section_diagnostics) to listOf(
            Item(getString(R.string.menu_dtc), getString(R.string.menu_dtc_desc), R.drawable.ic_dtc) { Intent(this, DtcActivity::class.java) },
        )
        val groups = listOf(Screens.GROUP_ENGINE, Screens.GROUP_DRIVETRAIN, Screens.GROUP_CHASSIS, Screens.GROUP_SERVICE)
        for (g in groups) {
            val items = fixed[g].orEmpty() + Screens.ALL.filter { it.group == g }.map { sc ->
                Item(sc.title, sc.subtitle, Icons.forScreen(sc.id)) { LiveDataActivity.intent(this, sc.id) }
            }
            if (items.isNotEmpty()) sections += g to items
        }
        sections += getString(R.string.section_tools) to listOf(
            Item(getString(R.string.menu_connection), getString(R.string.menu_connection_desc), R.drawable.ic_bluetooth, needsConnection = false) {
                Intent(this, ConnectionActivity::class.java)
            },
            Item(getString(R.string.menu_log), getString(R.string.menu_log_desc), R.drawable.ic_log, needsConnection = false) {
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
            val tiles = items.map { item ->
                inflater.inflate(R.layout.item_menu_tile, list, false).also { tile ->
                    tile.findViewById<ImageView>(R.id.tileIcon).setImageResource(item.icon)
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
                }
            }
            Grid.fill(list, tiles)
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
        val (color, chip, text) = when {
            connecting != null -> Triple(R.color.status_warn, R.string.chip_connecting, getString(R.string.connecting, connecting))
            Session.isConnected && t != null ->
                Triple(R.color.status_ok, R.string.chip_connected, "${t.displayName} · ${Session.elm?.version.orEmpty()}")
            else -> Triple(R.color.status_idle, R.string.chip_offline, getString(R.string.not_connected))
        }
        statusChip.setText(chip)
        statusChip.backgroundTintList = ColorStateList.valueOf(getColor(color))
        statusText.text = text
        connectButton.setText(if (Session.isConnected) R.string.manage_connection else R.string.connect)
    }
}
