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
    private var ffmpegRelay: FfmpegRelay? = null
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
                    // Тримай WakeLock довше для 24/7 (перезапуск сервісу оновить)
                    wakeLock?.acquire(12 * 60 * 60 * 1000L)
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

                if (serverUrl.isBlank()) {
                    appendLog("Сервер не вказано — лише перевірка камери")
                    appendLog("Вкажи RTMP/RTSP адресу сервера і перезапусти")
                    broadcastStatus("камера OK (сервер не задано)", true)
                    while (running.get()) {
                        Thread.sleep(5000)
                    }
                    return@thread
                }

                appendLog("Сервер: $serverUrl")
                broadcastStatus("запуск FFmpeg relay...", true)
                updateNotification("Relay: запуск FFmpeg")

                val relay = FfmpegRelay(this@RelayService) { msg -> appendLog(msg) }
                ffmpegRelay = relay

                val started = relay.start(rtspUrl, serverUrl)
                if (!started) {
                    appendLog("FFmpeg не запустився.")
                    appendLog("Потрібен binary: app/src/main/assets/ffmpeg")
                    appendLog("(armeabi-v7a, static, з RTSP+RTMP)")
                    broadcastStatus("немає FFmpeg binary", true)
                    // Тримаємо сервіс, щоб лог було видно
                    while (running.get()) {
                        Thread.sleep(5000)
                    }
                    return@thread
                }

                appendLog("FFmpeg запущено — йде трансляція")
                broadcastStatus("трансляція йде", true)
                updateNotification("Трансляція активна")

                // Слідкуємо за процесом, при падінні пробуємо перезапуск
                var restarts = 0
                while (running.get()) {
                    Thread.sleep(3000)
                    if (!relay.isAlive()) {
                        appendLog("FFmpeg зупинився")
                        if (!running.get()) break
                        if (restarts >= 10) {
                            appendLog("Забагато перезапусків, стоп")
                            broadcastStatus("FFmpeg впав", false)
                            break
                        }
                        restarts++
                        appendLog("Перезапуск FFmpeg (#$restarts)...")
                        Thread.sleep(2000)
                        if (!running.get()) break
                        if (!relay.start(rtspUrl, serverUrl)) {
                            appendLog("Перезапуск не вдався")
                            broadcastStatus("помилка relay", false)
                            break
                        }
                        broadcastStatus("трансляція йде (restart)", true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay error", e)
                appendLog("Помилка: ${e.message}")
                broadcastStatus("помилка: ${e.message}", false)
            } finally {
                ffmpegRelay?.stop()
                ffmpegRelay = null
                running.set(false)
                try {
                    wakeLock?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

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

                val options = buildString {
                    append("OPTIONS $rtspUrl RTSP/1.0\r\n")
                    append("CSeq: 1\r\n")
                    append("User-Agent: RTSPRelay/0.3\r\n")
                    append("\r\n")
                }
                writer.write(options)
                writer.flush()
                val optionsResp = readRtspResponse(reader)
                appendLog("OPTIONS → ${optionsResp.firstLine}")

                val describe = buildString {
                    append("DESCRIBE $rtspUrl RTSP/1.0\r\n")
                    append("CSeq: 2\r\n")
                    append("Accept: application/sdp\r\n")
                    append("User-Agent: RTSPRelay/0.3\r\n")
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
        ffmpegRelay?.stop()
        ffmpegRelay = null
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
            if (logBuffer.length > 2500) {
                logBuffer.delete(0, logBuffer.length - 1800)
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
