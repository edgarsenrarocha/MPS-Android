package com.mpsdiag.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.mpsdiag.R
import com.mpsdiag.Session

/** Shows the raw adapter traffic; handy when a car answers differently than expected. */
class LogActivity : Activity() {

    private lateinit var text: TextView
    private lateinit var scroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var shown = -1L

    private val ticker = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        setTitle(R.string.menu_log)
        text = findViewById(R.id.logText)
        scroll = findViewById(R.id.logScroll)
        findViewById<Button>(R.id.clearButton).setOnClickListener {
            Session.clearLog()
            update()
        }
        findViewById<Button>(R.id.shareButton).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "MPS Diag adapter log")
                .putExtra(Intent.EXTRA_TEXT, snapshot())
            startActivity(Intent.createChooser(send, getString(R.string.share_log)))
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun snapshot(): String = synchronized(Session.log) { Session.log.joinToString("\n") }

    private fun update() {
        val version = Session.logVersion
        if (version == shown) return
        shown = version
        text.text = snapshot()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
