package com.volodapatik.rtsprelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var rtspInput: EditText
    private lateinit var serverInput: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(RelayService.EXTRA_STATUS) ?: return
            val log = intent.getStringExtra(RelayService.EXTRA_LOG) ?: ""
            statusView.text = "Статус: $status"
            if (log.isNotEmpty()) {
                logView.text = log
            }
            val running = intent.getBooleanExtra(RelayService.EXTRA_RUNNING, false)
            startBtn.isEnabled = !running
            stopBtn.isEnabled = running
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rtspInput = findViewById(R.id.rtspUrl)
        serverInput = findViewById(R.id.serverUrl)
        startBtn = findViewById(R.id.startButton)
        stopBtn = findViewById(R.id.stopButton)
        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.logView)

        // Типовий URL для багатьох китайських камер
        if (rtspInput.text.isNullOrBlank()) {
            rtspInput.setText("rtsp://192.168.1.109:554/user=admin&password=&channel=1&stream=0.sdp")
        }

        startBtn.setOnClickListener {
            val rtsp = rtspInput.text.toString().trim()
            val server = serverInput.text.toString().trim()
            if (rtsp.isEmpty()) {
                statusView.text = "Статус: вкажи RTSP адресу камери"
                return@setOnClickListener
            }
            val intent = Intent(this, RelayService::class.java).apply {
                action = RelayService.ACTION_START
                putExtra(RelayService.EXTRA_RTSP_URL, rtsp)
                putExtra(RelayService.EXTRA_SERVER_URL, server)
            }
            ContextCompat.startForegroundService(this, intent)
            statusView.text = "Статус: запуск..."
            startBtn.isEnabled = false
            stopBtn.isEnabled = true
        }

        stopBtn.setOnClickListener {
            val intent = Intent(this, RelayService::class.java).apply {
                action = RelayService.ACTION_STOP
            }
            startService(intent)
            statusView.text = "Статус: зупинка..."
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RelayService.ACTION_STATUS)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
    }
}
