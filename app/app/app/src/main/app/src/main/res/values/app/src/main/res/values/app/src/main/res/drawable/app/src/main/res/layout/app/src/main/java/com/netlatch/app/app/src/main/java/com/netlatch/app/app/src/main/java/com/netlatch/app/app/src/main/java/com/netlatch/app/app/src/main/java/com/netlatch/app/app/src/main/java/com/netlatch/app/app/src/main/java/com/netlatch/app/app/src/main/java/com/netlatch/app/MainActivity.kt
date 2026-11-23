package com.netlatch.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var toggleVpn: Button
    private lateinit var lagSwitch: Switch
    private lateinit var bubbleBtn: Button
    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        toggleVpn = findViewById(R.id.toggleVpn)
        lagSwitch = findViewById(R.id.lagSwitch)
        bubbleBtn = findViewById(R.id.bubbleBtn)
        logView = findViewById(R.id.logView)

        val prefs = Prefs.get(this)

        val auto = findViewById<Switch>(R.id.autoSwitch)
        auto.isChecked = prefs.getBoolean(Prefs.KEY_AUTO, false)
        auto.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean(Prefs.KEY_AUTO, v).apply()
            sendAction(LagVpnService.ACTION_SETTLE)
        }

        val bidi = findViewById<Switch>(R.id.bidiSwitch)
        bidi.isChecked = prefs.getBoolean(Prefs.KEY_BIDI, false)
        bidi.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean(Prefs.KEY_BIDI, v).apply()
            sendAction(LagVpnService.ACTION_SETTLE)
        }

        val profiles = findViewById<RadioGroup>(R.id.profiles)
        profiles.check(
            when (prefs.getInt(Prefs.KEY_PROFILE, 1)) {
                0 -> R.id.p0
                2 -> R.id.p2
                else -> R.id.p1
            }
        )
        profiles.setOnCheckedChangeListener { _, id ->
            val p = when (id) {
                R.id.p0 -> 0
                R.id.p2 -> 2
                else -> 1
            }
            prefs.edit().putInt(Prefs.KEY_PROFILE, p).apply()
            sendAction(LagVpnService.ACTION_SETTLE)
        }

        lagSwitch.setOnCheckedChangeListener { _, _ ->
            sendAction(LagVpnService.ACTION_TOGGLE_LAG)
        }

        toggleVpn.setOnClickListener {
            if (State.running) {
                sendAction(LagVpnService.ACTION_STOP)
            } else {
                val prep = VpnService.prepare(this)
                if (prep != null) startActivityForResult(prep, 1) else startTunnel()
            }
        }

        bubbleBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivityForResult(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ), 3
                )
                return@setOnClickListener
            }
            val on = !prefs.getBoolean(Prefs.KEY_BUBBLE, false)
            prefs.edit().putBoolean(Prefs.KEY_BUBBLE, on).apply()
            if (on && !State.running) {
                val prep = VpnService.prepare(this)
                if (prep != null) startActivityForResult(prep, 1) else startTunnel()
            } else {
                sendAction(LagVpnService.ACTION_TOGGLE_BUBBLE)
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        LogBus.setListener {
            runOnUiThread { logView.text = LogBus.snapshot() }
        }
        logView.text = LogBus.snapshot()
        handler.post(ticker)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            1 -> if (resultCode == RESULT_OK) startTunnel()
            3 -> if (Settings.canDrawOverlays(this)) {
                Prefs.get(this).edit().putBoolean(Prefs.KEY_BUBBLE, true).apply()
                if (State.running) sendAction(LagVpnService.ACTION_TOGGLE_BUBBLE)
            }
        }
    }

    private fun startTunnel() {
        ContextCompat.startForegroundService(this, Intent(this, LagVpnService::class.java))
    }

    private fun sendAction(action: String) {
        startService(Intent(this, LagVpnService::class.java).setAction(action))
    }

    private fun render() {
        status.text = when {
            !State.running -> "Túnel inactivo"
            State.lag -> "Cortando"
            State.endpoints > 0 -> "Activo · ${State.endpoints} endpoints del juego"
            else -> "Activo · sin endpoints (entra a una partida)"
        }
        toggleVpn.text = if (State.running) getString(R.string.stop) else getString(R.string.start)
        lagSwitch.isEnabled = State.running

        val bubbleOn = Prefs.get(this).getBoolean(Prefs.KEY_BUBBLE, false) &&
            Settings.canDrawOverlays(this)
        bubbleBtn.text = if (bubbleOn) "Burbuja: activada" else "Burbuja flotante"

        if (lagSwitch.isChecked != State.lag && State.running) {
            lagSwitch.setOnCheckedChangeListener(null)
            lagSwitch.isChecked = State.lag
            lagSwitch.setOnCheckedChangeListener { _, _ ->
                sendAction(LagVpnService.ACTION_TOGGLE_LAG)
            }
        }
    }

    override fun onDestroy() {
        LogBus.setListener(null)
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }
}
