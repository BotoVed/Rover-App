package dev.botoved.rover

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(filesDir: File) {
        val dir = File(filesDir, "rover_rns")
        dir.mkdirs()
        val f = File(dir, "debug.log")
        val ts = fmt.format(Date())
        try { f.appendText("\n=== Kt session $ts ===\n") } catch (_: Exception) {}
        file = f
    }

    fun i(tag: String, msg: String) { write("I", tag, msg); Log.i(tag, msg) }
    fun w(tag: String, msg: String) { write("W", tag, msg); Log.w(tag, msg) }
    fun e(tag: String, msg: String) { write("E", tag, msg); Log.e(tag, msg) }
    fun e(tag: String, msg: String, t: Throwable) { write("E", tag, "$msg: ${t.message}"); Log.e(tag, msg, t) }

    private fun write(level: String, tag: String, msg: String) {
        val line = "[${fmt.format(Date())}] [$level/Kt/$tag] $msg\n"
        try { synchronized(lock) { file?.appendText(line) } } catch (_: Exception) {}
    }
}
