package com.mpsdiag.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mpsdiag.R
import com.mpsdiag.Session
import com.mpsdiag.diag.ConnectionTest
import com.mpsdiag.transport.BleTransport
import com.mpsdiag.transport.BluetoothSppTransport
import com.mpsdiag.transport.Transport
import com.mpsdiag.transport.WifiTransport
import javax.net.SocketFactory

/**
 * Everything about getting connected: Bluetooth state and permissions, paired and nearby
 * adapters, Wi-Fi adapters, and a step-by-step connection test.
 */
@SuppressLint("MissingPermission") // Every Bluetooth call happens after hasPermissions().
class ConnectionActivity : Activity() {

    private data class Entry(val device: BluetoothDevice, val ble: Boolean, val paired: Boolean)

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var connectedActions: View
    private lateinit var testButton: Button
    private lateinit var testCard: View
    private lateinit var testList: LinearLayout
    private lateinit var btState: TextView
    private lateinit var btAction: Button
    private lateinit var deviceList: LinearLayout
    private lateinit var scanProgress: ProgressBar
    private lateinit var wifiAddress: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var bt: BluetoothAdapter? = null
    private val entries = linkedMapOf<String, Entry>()
    private var scanning = false
    private var testing = false
    private val sessionListener = { refreshStatus() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> refreshBluetooth()
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    addFound(d, ble = d.type == BluetoothDevice.DEVICE_TYPE_LE)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (scanning) bt?.startDiscovery()
            }
        }
    }

    private val leCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            addFound(d, ble = d.type == BluetoothDevice.DEVICE_TYPE_LE || d.type == BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        connectedActions = findViewById(R.id.connectedActions)
        testButton = findViewById(R.id.testButton)
        testCard = findViewById(R.id.testCard)
        testList = findViewById(R.id.testList)
        btState = findViewById(R.id.btStateText)
        btAction = findViewById(R.id.btActionButton)
        deviceList = findViewById(R.id.deviceList)
        scanProgress = findViewById(R.id.scanProgress)
        wifiAddress = findViewById(R.id.wifiAddress)

        bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

        testButton.setOnClickListener { runTest() }
        findViewById<Button>(R.id.disconnectButton).setOnClickListener { Session.disconnect() }
        findViewById<Button>(R.id.btSettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        wifiAddress.setText(prefs.getString(PREF_WIFI, "${WifiTransport.DEFAULT_HOST}:${WifiTransport.DEFAULT_PORT}"))
        findViewById<Button>(R.id.wifiConnectButton).setOnClickListener { connectWifi() }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // These broadcasts come from the system Bluetooth process.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onStart() {
        super.onStart()
        Session.addListener(sessionListener)
        refreshStatus()
        refreshBluetooth()
    }

    override fun onStop() {
        stopScan()
        Session.removeListener(sessionListener)
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    // ---- Connection status ------------------------------------------------------------

    private fun refreshStatus() {
        val connecting = Session.connectingTo
        val t = Session.transport
        val (color, text) = when {
            connecting != null -> R.color.status_warn to getString(R.string.connecting, connecting)
            Session.isConnected && t != null ->
                R.color.status_ok to getString(R.string.connected_to, "${t.displayName}\n${Session.elm?.version.orEmpty()}")
            else -> R.color.status_idle to getString(R.string.not_connected)
        }
        (statusDot.background.mutate() as GradientDrawable).setColor(getColor(color))
        statusText.text = text
        connectedActions.visibility = if (Session.isConnected) View.VISIBLE else View.GONE
        testButton.isEnabled = !testing
        renderDevices()
    }

    // ---- Bluetooth state, permissions, device list -------------------------------------

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermissions() = requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun refreshBluetooth() {
        val adapter = bt
        when {
            adapter == null -> {
                btState.setText(R.string.conn_bt_none)
                btAction.visibility = View.GONE
            }
            !hasPermissions() -> {
                btState.setText(R.string.conn_bt_no_permission)
                btAction.setText(R.string.conn_allow)
                btAction.setOnClickListener { requestPermissions(requiredPermissions().toTypedArray(), REQ_PERMISSIONS) }
            }
            !adapter.isEnabled -> {
                stopScan()
                btState.setText(R.string.conn_bt_off)
                btAction.setText(R.string.conn_turn_on)
                btAction.setOnClickListener {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE)
                }
            }
            else -> {
                btState.setText(if (scanning) R.string.conn_bt_scanning else R.string.conn_bt_ready)
                btAction.setText(if (scanning) R.string.conn_stop_scan else R.string.conn_scan)
                btAction.setOnClickListener { if (scanning) stopScan() else startScan() }
                loadPaired(adapter)
            }
        }
        scanProgress.visibility = if (scanning) View.VISIBLE else View.GONE
        renderDevices()
    }

    private fun loadPaired(adapter: BluetoothAdapter) {
        val found = entries.values.filter { !it.paired }
        entries.clear()
        adapter.bondedDevices.forEach { d ->
            entries[d.address] = Entry(d, ble = d.type == BluetoothDevice.DEVICE_TYPE_LE, paired = true)
        }
        found.forEach { if (it.device.address !in entries) entries[it.device.address] = it }
    }

    private fun addFound(d: BluetoothDevice, ble: Boolean) {
        if (d.address in entries) return
        if (d.name.isNullOrBlank()) return
        entries[d.address] = Entry(d, ble, paired = d.bondState == BluetoothDevice.BOND_BONDED)
        renderDevices()
    }

    private fun looksLikeObd(name: String?): Boolean {
        val n = name?.uppercase() ?: return false
        return OBD_NAMES.any { n.contains(it) }
    }

    private fun renderDevices() {
        if (!::deviceList.isInitialized) return
        deviceList.removeAllViews()
        val adapterReady = bt?.isEnabled == true && hasPermissions()
        if (!adapterReady) return
        val last = prefs.getString(PREF_LAST_BT, null)
        val connectedName = if (Session.isConnected) Session.transport?.displayName else null
        val sorted = entries.values.sortedWith(
            compareByDescending<Entry> { it.device.address == last }
                .thenByDescending { looksLikeObd(it.device.name) }
                .thenByDescending { it.paired }
                .thenBy { it.device.name ?: "" },
        )
        if (sorted.isEmpty()) {
            val empty = TextView(this).apply {
                setTextAppearance(R.style.Text_Secondary)
                setText(R.string.conn_no_devices)
                setPadding(0, 16, 0, 16)
            }
            deviceList.addView(empty)
            return
        }
        val inflater = LayoutInflater.from(this)
        val busy = Session.connectingTo != null
        for (e in sorted) {
            val row = inflater.inflate(R.layout.item_device, deviceList, false)
            val name = e.device.name?.takeIf { it.isNotBlank() } ?: e.device.address
            row.findViewById<TextView>(R.id.deviceName).text = name
            val info = mutableListOf(
                getString(if (e.paired) R.string.conn_paired else R.string.conn_found),
                if (e.ble) "BLE" else "Classic",
                e.device.address,
            )
            if (e.device.address == last) info.add(0, getString(R.string.conn_last_used))
            else if (looksLikeObd(e.device.name)) info.add(0, getString(R.string.conn_likely_obd))
            row.findViewById<TextView>(R.id.deviceInfo).text = info.joinToString(" · ")
            val isConnected = connectedName != null && connectedName == name
            row.findViewById<TextView>(R.id.deviceAction).setText(if (isConnected) R.string.conn_connected_here else R.string.connect)
            row.isEnabled = !busy && !isConnected
            row.setOnClickListener { connectBluetooth(e) }
            deviceList.addView(row)
        }
    }

    private fun startScan() {
        val adapter = bt ?: return
        if (scanning || !adapter.isEnabled) return
        scanning = true
        adapter.startDiscovery()
        try {
            adapter.bluetoothLeScanner?.startScan(leCallback)
        } catch (_: Exception) {
        }
        handler.postDelayed({ stopScan() }, SCAN_MS)
        refreshBluetooth()
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacksAndMessages(null)
        try {
            bt?.cancelDiscovery()
            bt?.bluetoothLeScanner?.stopScan(leCallback)
        } catch (_: Exception) {
        }
        if (::btState.isInitialized) refreshBluetooth()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshBluetooth()
    }

    @Deprecated("Framework activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        refreshBluetooth()
    }

    // ---- Connecting and testing ---------------------------------------------------------

    private fun connectBluetooth(e: Entry) {
        val adapter = bt ?: return
        stopScan()
        val transport = if (e.ble) BleTransport(applicationContext, e.device) else BluetoothSppTransport(adapter, e.device)
        connect(transport) {
            prefs.edit().putString(PREF_LAST_BT, e.device.address).apply()
        }
    }

    private fun connectWifi() {
        val text = wifiAddress.text.toString().trim()
        val host = text.substringBefore(':')
        if (host.isEmpty()) return
        val port = text.substringAfter(':', "").toIntOrNull() ?: WifiTransport.DEFAULT_PORT
        prefs.edit().putString(PREF_WIFI, "$host:$port").apply()
        connect(WifiTransport(host, port, wifiSocketFactory())) {}
    }

    /** Socket factory bound to the Wi-Fi network, so traffic is not sent over mobile data. */
    private fun wifiSocketFactory(): SocketFactory {
        val cm = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        return wifi?.socketFactory ?: SocketFactory.getDefault()
    }

    private fun connect(transport: Transport, onConnected: () -> Unit) {
        testList.removeAllViews()
        testCard.visibility = View.GONE
        Session.connect(transport) { result ->
            result.onSuccess {
                onConnected()
                runTest()
            }.onFailure {
                testList.removeAllViews()
                testCard.visibility = View.VISIBLE
                addStep(
                    ConnectionTest.Step(
                        getString(R.string.conn_step_connect),
                        ConnectionTest.Outcome.FAIL,
                        getString(R.string.conn_connect_hints, it.message ?: it.javaClass.simpleName),
                    ),
                )
            }
        }
    }

    private fun runTest() {
        if (testing) return
        testing = true
        testButton.isEnabled = false
        testList.removeAllViews()
        testCard.visibility = View.VISIBLE
        Session.run({ d -> ConnectionTest.run(d) { step -> Session.postMain { addStep(step) } } }) { result ->
            testing = false
            testButton.isEnabled = true
            result.exceptionOrNull()?.let {
                addStep(ConnectionTest.Step(getString(R.string.conn_test), ConnectionTest.Outcome.FAIL, it.message ?: "error"))
            }
        }
    }

    private fun addStep(step: ConnectionTest.Step) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_test_step, testList, false)
        val (icon, color) = when (step.outcome) {
            ConnectionTest.Outcome.OK -> "✓" to R.color.status_ok
            ConnectionTest.Outcome.WARN -> "!" to R.color.status_warn
            ConnectionTest.Outcome.FAIL -> "✗" to R.color.status_error
        }
        row.findViewById<TextView>(R.id.stepIcon).apply {
            text = icon
            setTextColor(getColor(color))
        }
        row.findViewById<TextView>(R.id.stepTitle).text = step.title
        row.findViewById<TextView>(R.id.stepDetail).text = step.detail
        testList.addView(row)
    }

    companion object {
        const val PREFS = "settings"
        const val PREF_LAST_BT = "last_bt_address"
        const val PREF_WIFI = "wifi_address"
        private const val REQ_PERMISSIONS = 1
        private const val REQ_ENABLE = 2
        private const val SCAN_MS = 15_000L
        private val OBD_NAMES = listOf("OBD", "ELM", "VLINK", "V-LINK", "VLINKER", "VGATE", "ICAR", "KONNWEI", "VEEPEAK", "OBDLINK")
    }
}
