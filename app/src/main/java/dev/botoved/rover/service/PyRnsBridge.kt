package dev.botoved.rover.service

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform

class PyRnsBridge(private val context: Context) {

    private val TAG = "Rover"
    private var pyModule: PyObject? = null

    private val incomingCallback = MessageCallback()

    inner class MessageCallback {
        @Suppress("unused")
        fun onMessage(sourceHex: String, fields: Map<*, *>) {
            Log.i(TAG, "PyRNS msg from=$sourceHex fields=$fields")
        }
    }

    fun init(): Boolean {
        if (!Python.isStarted()) {
            try {
                Python.start(AndroidPlatform(context))
            } catch (e: Exception) {
                Log.e(TAG, "Python.start failed: ${e.message}", e)
                return false
            }
        }
        val py = Python.getInstance()
        val module = py.getModule("rover_rns")
        module.callAttr("set_incoming_callback", PyObject.fromJava(incomingCallback))
        pyModule = module
        Log.i(TAG, "PyRnsBridge initialized")
        return true
    }

    fun start(configDir: String, host: String, port: Int): String? {
        val module = pyModule ?: return null
        return try {
            val result = module.callAttr("start", configDir, host, port)
            val identity = result.toString()
            Log.i(TAG, "PyRNS identity=$identity host=$host:$port")
            identity
        } catch (e: Exception) {
            Log.e(TAG, "PyRNS start failed: ${e.message}", e)
            null
        }
    }

    fun requestPathAndWait(destHex: String, timeoutS: Double = 3.0): Boolean {
        val module = pyModule ?: return false
        return try {
            val result = module.callAttr("request_path_and_wait", destHex, timeoutS)
            val ok = result.toString().toBoolean()
            Log.i(TAG, "PyRNS requestPathAndWait dest=$destHex result=$ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "PyRNS requestPathAndWait failed: ${e.message}", e)
            false
        }
    }

    fun activeChannel(): String {
        val module = pyModule ?: return "none"
        return try {
            val result = module.callAttr("active_channel")
            val channel = result.toString()
            Log.i(TAG, "PyRNS activeChannel=$channel")
            channel
        } catch (e: Exception) {
            Log.e(TAG, "PyRNS activeChannel failed: ${e.message}", e)
            "error"
        }
    }

    fun dumpDiagnostics(): String {
        val module = pyModule ?: return "no module"
        return try {
            val result = module.callAttr("dump_diagnostics")
            result.toString()
        } catch (e: Exception) {
            Log.e(TAG, "PyRNS dumpDiagnostics failed: ${e.message}", e)
            "error"
        }
    }

    companion object {
        private val TAG = "Rover"

        // SPIKE TEST ONLY — реальный адрес берётся из QR на этапе онбординга
        private const val HAOS_HOST = "192.168.1.114"
        private const val HAOS_PORT = 4242
        private const val SERVER_IDENTITY_HEX = "9c6d5641e8c1ba46376bcbcb8e39c4cc"

        fun runSpike(context: Context) {
            val bridge = PyRnsBridge(context)
            if (!bridge.init()) {
                Log.e(TAG, "SPIKE FAILED: Python init")
                return
            }
            val configDir = context.filesDir.absolutePath + "/rover_rns"
            val identity = bridge.start(configDir, HAOS_HOST, HAOS_PORT)
            if (identity == null) {
                Log.e(TAG, "SPIKE FAILED: start returned null")
                return
            }
            Log.i(TAG, "SPIKE client identity=$identity")

            val channel = bridge.activeChannel()
            Log.i(TAG, "SPIKE activeChannel=$channel")

            val pathOk = bridge.requestPathAndWait(SERVER_IDENTITY_HEX, 120.0)
            Log.i(TAG, "SPIKE requestPathAndWait=$pathOk")

            val diag = bridge.dumpDiagnostics()
            for (line in diag.split("\n")) {
                Log.i(TAG, "DIAG: $line")
            }
        }
    }
}
