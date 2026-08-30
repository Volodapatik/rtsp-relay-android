package com.volodapatik.rtsprelay

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val rtsp = findViewById<EditText>(R.id.rtspUrl)
        val server = findViewById<EditText>(R.id.serverUrl)
        val start = findViewById<Button>(R.id.startButton)
        val stop = findViewById<Button>(R.id.stopButton)
        val status = findViewById<TextView>(R.id.status)

        rtsp.setText("rtsp://192.168.1.109:554/user=admin&password=&channel=1&stream=0.sdp")

        start.setOnClickListener {
            val intent = Intent(this, RelayService::class.java)
            intent.putExtra("rtsp_url", rtsp.text.toString().trim())
            intent.putExtra("server_url", server.text.toString().trim())
            startService(intent)
            status.text = "Статус: запуск..."
            start.isEnabled = false
            stop.isEnabled = true
        }

        stop.setOnClickListener {
            stopService(Intent(this, RelayService::class.java))
            status.text = "Статус: зупинено"
            start.isEnabled = true
            stop.isEnabled = false
        }
    }
}
