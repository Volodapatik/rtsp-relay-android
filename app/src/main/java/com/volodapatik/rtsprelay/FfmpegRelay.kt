package com.volodapatik.rtsprelay

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a bundled (or extracted) ffmpeg binary to relay RTSP -> RTSP/RTMP.
 * Designed for Android 5.1 (API 22), armeabi-v7a.
 *
 * Place a static ffmpeg binary at: app/src/main/assets/ffmpeg
 * (armeabi-v7a / armv7, ideally with RTSP + RTMP support, -c copy capable)
 */
class FfmpegRelay(
    private val context: Context,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "FfmpegRelay"
        private const val ASSET_NAME = "ffmpeg"
    }

    private var process: Process? = null
    private val stopped = AtomicBoolean(false)

    fun ensureBinary(): File? {
        val out = File(context.filesDir, "ffmpeg")
        try {
            if (out.exists() && out.canExecute() && out.length() > 100_000) {
                return out
            }
            // Try copy from assets
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(out).use { output ->
                    input.copyTo(output)
                }
            }
            out.setReadable(true, true)
            out.setExecutable(true, true)
            onLog("FFmpeg binary extracted (${out.length() / 1024} KB)")
            return out
        } catch (e: Exception) {
            Log.w(TAG, "No ffmpeg in assets", e)
            onLog("FFmpeg binary відсутній у assets/")
            onLog("Потрібен файл assets/ffmpeg (armeabi-v7a)")
            return if (out.exists() && out.canExecute()) out else null
        }
    }

    /**
     * Build ffmpeg args for copy-relay (no re-encode).
     * Supports rtmp:// and rtsp:// destinations.
     */
    fun buildCommand(ffmpegPath: String, rtspUrl: String, serverUrl: String): List<String> {
        val dest = serverUrl.trim()
        val args = mutableListOf(
            ffmpegPath,
            "-hide_banner",
            "-loglevel", "warning",
            "-rtsp_transport", "tcp",
            "-i", rtspUrl,
            "-c", "copy",
            "-f"
        )
        when {
            dest.startsWith("rtmp://", ignoreCase = true) -> {
                args.add("flv")
                args.add(dest)
            }
            dest.startsWith("rtsp://", ignoreCase = true) -> {
                args.add("rtsp")
                args.add("-rtsp_transport")
                args.add("tcp")
                args.add(dest)
            }
            else -> {
                // default try RTSP
                args.add("rtsp")
                args.add("-rtsp_transport")
                args.add("tcp")
                args.add(dest)
            }
        }
        return args
    }

    fun start(rtspUrl: String, serverUrl: String): Boolean {
        stopped.set(false)
        val bin = ensureBinary() ?: return false

        val cmd = buildCommand(bin.absolutePath, rtspUrl, serverUrl)
        onLog("FFmpeg: ${cmd.joinToString(" ").take(180)}...")

        return try {
            val pb = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .directory(context.filesDir)
            process = pb.start()

            // Drain stdout so process doesn't block
            Thread {
                try {
                    BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (stopped.get()) break
                            val l = line ?: continue
                            if (l.isNotBlank()) onLog("ffmpeg: ${l.take(200)}")
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { name = "ffmpeg-log"; isDaemon = true; start() }

            true
        } catch (e: Exception) {
            onLog("Не вдалося запустити FFmpeg: ${e.message}")
            false
        }
    }

    fun isAlive(): Boolean {
        val p = process ?: return false
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    fun stop() {
        stopped.set(true)
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        try {
            // Android 8+ has destroyForcibly; API 22 only has destroy()
            process?.waitFor()
        } catch (_: Exception) {
        }
        process = null
    }
}
