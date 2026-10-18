package com.mpsdiag.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.mpsdiag.R
import com.mpsdiag.Session
import com.mpsdiag.transport.Transport
import com.mpsdiag.transport.WifiTransport
import javax.net.SocketFactory

class MainActivity : Activity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private var connecting = false
    private val sessionListener = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)

        connectButton.setOnClickListener {
            if (Session.isConnected) Session.disconnect() else chooseAdapterType()
        }
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

    private fun openIfConnected(cls: Class<out Activity>) {
        if (!Session.isConnected) {
            Toast.makeText(this, R.string.connect_first, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, cls))
    }

    private fun refresh() {
        val t = Session.transport
        val (color, text) = when {
            connecting -> R.color.status_warn to statusText.text.toString()
            Session.isConnected && t != null ->
                R.color.status_ok to getString(R.string.connected_to, "${t.displayName} · ${Session.elm?.version.orEmpty()}")
            else -> R.color.status_idle to getString(R.string.not_connected)
        }
        (statusDot.background.mutate() as GradientDrawable).setColor(getColor(color))
        statusText.text = text
        connectButton.setText(if (Session.isConnected) R.string.disconnect else R.string.connect)
        connectButton.isEnabled = !connecting
    }

    private fun chooseAdapterType() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_adapter)
            .setItems(arrayOf(getString(R.string.adapter_bluetooth), getString(R.string.adapter_wifi))) { _, which ->
                if (which == 0) ensurePermissionsThenPick() else askWifiAddress()
            }
            .show()
    }

    private fun askWifiAddress() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val input = EditText(this).apply {
            setText(prefs.getString(PREF_WIFI, "${WifiTransport.DEFAULT_HOST}:${WifiTransport.DEFAULT_PORT}"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val pad = resources.getDimensionPixelSize(R.dimen.screen_padding)
        val frame = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.adapter_wifi)
            .setMessage(R.string.wifi_hint)
            .setView(frame)
            .setPositiveButton(R.string.connect) { _, _ ->
                val text = input.text.toString().trim()
                val host = text.substringBefore(':')
                val port = text.substringAfter(':', "").toIntOrNull() ?: WifiTransport.DEFAULT_PORT
                if (host.isEmpty()) return@setPositiveButton
                prefs.edit().putString(PREF_WIFI, "$host:$port").apply()
                connect(WifiTransport(host, port, wifiSocketFactory()))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Socket factory bound to the Wi-Fi network, so traffic is not sent over mobile data. */
    private fun wifiSocketFactory(): SocketFactory {
        if (Build.VERSION.SDK_INT < 23) return SocketFactory.getDefault()
        val cm = getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        return wifi?.socketFactory ?: SocketFactory.getDefault()
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun ensurePermissionsThenPick() {
        if (Build.VERSION.SDK_INT >= 23) {
            val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
                return
            }
        }
        pickDevice()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            pickDevice()
        } else {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    private fun pickDevice() {
        DevicePicker(this) { connect(it) }.show()
    }

    private fun connect(transport: Transport) {
        connecting = true
        statusText.text = getString(R.string.connecting, transport.displayName)
        refresh()
        Session.connect(transport) { result ->
            connecting = false
            result.exceptionOrNull()?.let {
                Toast.makeText(this, getString(R.string.connect_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
            refresh()
        }
    }

    companion object {
        private const val REQ_PERMISSIONS = 1
        private const val PREFS = "settings"
        private const val PREF_WIFI = "wifi_address"
    }
}
