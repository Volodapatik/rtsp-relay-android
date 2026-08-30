package com.volodapatik.rtsprelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RelayService : Service() {

    companion object {
        const val ACTION_START = "com.volodapatik.rtsprelay.START"
        const val ACTION_STOP = "com.volodapatik.rtsprelay.STOP"
        const val ACTION_STATUS = "com.volodapatik.rtsprelay.STATUS"

        const val EXTRA_RTSP_URL = "rtsp_url"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_STATUS = "status"
        const val EXTRA_LOG = "log"
        const val EXTRA_RUNNING = "running"

        private const val TAG = "RelayService"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "relay"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private val logBuffer = StringBuilder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rtsprelay:relay")
        wakeLock?.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRelay()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val rtsp = intent?.getStringExtra(EXTRA_RTSP_URL) ?: ""
                val server = intent?.getStringExtra(EXTRA_SERVER_URL) ?: ""
                if (rtsp.isNotBlank() && running.compareAndSet(false, true)) {
                    startForeground(NOTIF_ID, buildNotification("Запуск..."))
                    wakeLock?.acquire(60 * 60 * 1000L) // max 1 година за раз
                    startRelay(rtsp, server)
                }
            }
        }
        return START_STICKY
    }

    private fun startRelay(rtspUrl: String, serverUrl: String) {
        worker = thread(name = "rtsp-relay-worker") {
            try {
                appendLog("Перевірка RTSP: $rtspUrl")
                broadcastStatus("перевірка камери...", true)

                val ok = testRtspConnection(rtspUrl)
                if (!ok) {
                    appendLog("НЕ ВДАЛОСЯ підключитися до камери")
                    broadcastStatus("помилка підключення до камери", false)
                    running.set(false)
                    stopForeground(true)
                    stopSelf()
                    return@thread
                }

                appendLog("Камера відповіла (RTSP OK)")
                updateNotification("Камера OK, очікування сервера")

                if (serverUrl.isBlank()) {
                    appendLog("Сервер не вказано — працюємо в режимі тільки перевірки")
                    appendLog("Вкажи адресу Railway/MediaMTX і перезапусти")
                    broadcastStatus("камера OK (сервер не задано)", true)
                    // Тримаємо сервіс живим, щоб користувач бачив статус
                    while (running.get()) {
                        Thread.sleep(5000)
                    }
                    return@thread
                }

                appendLog("Сервер: $serverUrl")
                appendLog("Реальний relay (FFmpeg / push) ще в розробці")
                appendLog("Зараз: тільки перевірка з'єднання з камерою")
                broadcastStatus("камера OK (relay незабаром)", true)

                while (running.get()) {
                    Thread.sleep(5000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay error", e)
                appendLog("Помилка: ${e.message}")
                broadcastStatus("помилка: ${e.message}", false)
            } finally {
                running.set(false)
                try {
                    wakeLock?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Проста перевірка RTSP поверх TCP.
     * Відправляє OPTIONS + DESCRIBE і дивиться, чи камера відповідає.
     * Працює на API 22 без сторонніх бібліотек.
     */
    private fun testRtspConnection(rtspUrl: String): Boolean {
        return try {
            val uri = Uri.parse(rtspUrl)
            val host = uri.host ?: return false
            val port = if (uri.port > 0) uri.port else 554

            appendLog("TCP $host:$port ...")

            Socket().use { socket ->
                socket.soTimeout = 8000
                socket.connect(InetSocketAddress(host, port), 6000)

                val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

                // OPTIONS
                val options = buildString {
                    append("OPTIONS $rtspUrl RTSP/1.0\r\n")
                    append("CSeq: 1\r\n")
                    append("User-Agent: RTSPRelay/0.2\r\n")
                    append("\r\n")
                }
                writer.write(options)
                writer.flush()

                val optionsResp = readRtspResponse(reader)
                appendLog("OPTIONS → ${optionsResp.firstLine}")

                // DESCRIBE
                val describe = buildString {
                    append("DESCRIBE $rtspUrl RTSP/1.0\r\n")
                    append("CSeq: 2\r\n")
                    append("Accept: application/sdp\r\n")
                    append("User-Agent: RTSPRelay/0.2\r\n")
                    append("\r\n")
                }
                writer.write(describe)
                writer.flush()

                val describeResp = readRtspResponse(reader)
                appendLog("DESCRIBE → ${describeResp.firstLine}")

                if (describeResp.firstLine.contains("401")) {
                    appendLog("Потрібна авторизація (401). Додай user:pass у URL")
                }

                describeResp.firstLine.contains("200") || optionsResp.firstLine.contains("200")
            }
        } catch (e: Exception) {
            appendLog("TCP помилка: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private data class RtspResponse(val firstLine: String, val headers: Map<String, String>, val body: String)

    private fun readRtspResponse(reader: BufferedReader): RtspResponse {
        val first = reader.readLine() ?: return RtspResponse("empty", emptyMap(), "")
        val headers = mutableMapOf<String, String>()
        var line: String?
        var contentLength = 0
        while (true) {
            line = reader.readLine()
            if (line.isNullOrEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                headers[key] = value
                if (key == "content-length") {
                    contentLength = value.toIntOrNull() ?: 0
                }
            }
        }
        val body = if (contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read)
        } else ""
        return RtspResponse(first, headers, body)
    }

    private fun stopRelay() {
        running.set(false)
        worker?.interrupt()
        worker = null
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        broadcastStatus("зупинено", false)
        appendLog("Сервіс зупинено")
    }

    private fun appendLog(msg: String) {
        Log.i(TAG, msg)
        synchronized(logBuffer) {
            if (logBuffer.isNotEmpty()) logBuffer.append('\n')
            logBuffer.append(msg)
            // Обмежуємо розмір логу
            if (logBuffer.length > 2000) {
                logBuffer.delete(0, logBuffer.length - 1500)
            }
        }
        broadcastStatus(null, running.get())
    }

    private fun broadcastStatus(status: String?, isRunning: Boolean) {
        val log = synchronized(logBuffer) { logBuffer.toString() }
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status ?: "працює")
            putExtra(EXTRA_LOG, log)
            putExtra(EXTRA_RUNNING, isRunning)
        }
        sendBroadcast(intent)
        if (status != null) {
            updateNotification(status)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RTSP Relay",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("RTSP Relay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopRelay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
