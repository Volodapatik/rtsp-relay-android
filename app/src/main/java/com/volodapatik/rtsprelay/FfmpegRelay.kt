package com.volodapatik.rtsprelay

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a bundled ffmpeg binary to relay RTSP -> RTSP/RTMP.
 * Designed for Android 5.1 (API 22), armeabi-v7a.
 */
class FfmpegRelay(
    private val context: Context,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "FfmpegRelay"
        private const val ASSET_FFMPEG = "ffmpeg"
        private const val ASSET_LIBCXX = "libc++_shared.so"
    }

    private var process: Process? = null
    private val stopped = AtomicBoolean(false)
    private var libDir: File? = null

    fun ensureBinary(): File? {
        val dir = context.filesDir
        libDir = dir
        val out = File(dir, ASSET_FFMPEG)
        try {
            context.assets.open(ASSET_FFMPEG).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            out.setReadable(true, true)
            out.setExecutable(true, true)

            try {
                val libOut = File(dir, ASSET_LIBCXX)
                context.assets.open(ASSET_LIBCXX).use { input ->
                    FileOutputStream(libOut).use { output -> input.copyTo(output) }
                }
                libOut.setReadable(true, true)
                onLog("libc++_shared.so extracted (${libOut.length() / 1024} KB)")
            } catch (e: Exception) {
                Log.w(TAG, "No libc++_shared.so in assets", e)
            }

            onLog("FFmpeg binary extracted (${out.length() / 1024} KB)")
            return out
        } catch (e: Exception) {
            Log.w(TAG, "No ffmpeg in assets", e)
            onLog("FFmpeg binary відсутній у assets/")
            return if (out.exists() && out.canExecute()) out else null
        }
    }

    fun buildCommand(ffmpegPath: String, rtspUrl: String, serverUrl: String): List<String> {
        val dest = serverUrl.trim()
        // Flags tuned for flaky Chinese IP camera RTSP over TCP
        val args = mutableListOf(
            ffmpegPath,
            "-hide_banner",
            "-loglevel", "warning",
            "-rtsp_transport", "tcp",
            "-rtsp_flags", "prefer_tcp",
            "-fflags", "+genpts+discardcorrupt+igndts",
            "-err_detect", "ignore_err",
            "-use_wallclock_as_timestamps", "1",
            "-i", rtspUrl,
            "-c", "copy",
            "-flush_packets", "1"
        )
        when {
            dest.startsWith("rtmp://", ignoreCase = true) -> {
                args.add("-f")
                args.add("flv")
                args.add(dest)
            }
            else -> {
                // RTSP publish to MediaMTX
                args.add("-f")
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
        onLog("FFmpeg: ${cmd.joinToString(" ").take(200)}...")

        return try {
            val pb = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .directory(context.filesDir)

            val env = pb.environment()
            val libPath = (libDir ?: context.filesDir).absolutePath
            val existing = env["LD_LIBRARY_PATH"]
            env["LD_LIBRARY_PATH"] = if (existing.isNullOrBlank()) libPath else "$libPath:$existing"

            process = pb.start()

            Thread {
                try {
                    BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (stopped.get()) break
                            val l = line ?: continue
                            if (l.isNotBlank()) onLog("ffmpeg: ${l.take(220)}")
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
        try { process?.destroy() } catch (_: Exception) {}
        try { process?.waitFor() } catch (_: Exception) {}
        process = null
    }
}
