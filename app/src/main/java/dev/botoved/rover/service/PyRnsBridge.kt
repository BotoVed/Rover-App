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
        private var handler: ((String, Map<*, *>) -> Unit)? = null

        fun setHandler(h: (String, Map<*, *>) -> Unit) {
            handler = h
        }

        @Suppress("unused")
        fun onMessage(sourceHex: String, fields: Map<*, *>) {
            val tp = (fields[0] as? Number)?.toInt()
            Log.i(TAG, "PyRNS msg src=$sourceHex tp=$tp fields=$fields")
            handler?.invoke(sourceHex, fields)
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

    fun sendRegister(destHex: String, uid: String): Boolean {
        val module = pyModule ?: return false
        return try {
            val result = module.callAttr("send_register", destHex, uid)
            val ok = result.toString().toBoolean()
            Log.i(TAG, "PyRNS sendRegister dest=$destHex uid=$uid ok=$ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "PyRNS sendRegister failed: ${e.message}", e)
            false
        }
    }

    fun send(destHex: String, fields: Map<*, *>, awaitPath: Boolean = false): Boolean {
        val module = pyModule ?: return false
        return try {
            val pyFields = PyObject.fromJava(fields)
            val result = module.callAttr("send", destHex, pyFields, awaitPath)
            val ok = result.toString().toBoolean()
            Log.i(TAG, "PyRNS send dest=$destHex ok=$ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "PyRNS send failed: ${e.message}", e)
            false
        }
    }

    fun setMessageHandler(handler: (String, Map<*, *>) -> Unit) {
        incomingCallback.setHandler(handler)
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
        // Актуальный identity сервера из log: "Rover server identity hash: f6be97eaf5bc4ef313e28f036ddb5503"
        private const val SERVER_IDENTITY_HEX = "f6be97eaf5bc4ef313e28f036ddb5503"
        // QR uid — устанавливается перед тестом (генерируется через options_flow config step на сервере)
        @JvmStatic var QR_UID = "4012"
        @JvmStatic var spikeResult: String? = null

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

            // Set up message handler before sending register
            val configReceived = java.util.concurrent.CompletableFuture<Boolean>()
            bridge.setMessageHandler { srcHex, fields ->
                val tp = (fields[0] as? Number)?.toInt()
                Log.i(TAG, "SPIKE msg src=$srcHex tp=$tp fields=$fields")
                if (tp == 4 || tp == 7) { // CONFIG=4, FORBIDDEN=7
                    configReceived.complete(tp == 4)
                }
            }

            val pathOk = bridge.requestPathAndWait(SERVER_IDENTITY_HEX, 120.0)
            Log.i(TAG, "SPIKE requestPathAndWait=$pathOk")
            if (!pathOk) {
                Log.e(TAG, "SPIKE ABORT: path not found")
                return
            }

            // Send REGISTER with QR uid
            val regOk = bridge.sendRegister(SERVER_IDENTITY_HEX, QR_UID)
            Log.i(TAG, "SPIKE sendRegister=$regOk")
            if (!regOk) {
                Log.e(TAG, "SPIKE ABORT: sendRegister failed")
                return
            }

            // Wait for CONFIG (30s timeout)
            try {
                val approved = configReceived.get(30000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (approved) {
                    Log.i(TAG, "SPIKE ONBOARDING PASSED: CONFIG received")
                    spikeResult = "PASS"
                } else {
                    Log.w(TAG, "SPIKE ONBOARDING FAILED: FORBIDDEN received")
                    spikeResult = "FAIL_FORBIDDEN"
                }
            } catch (e: Exception) {
                Log.e(TAG, "SPIKE ONBOARDING TIMEOUT: no CONFIG/FORBIDDEN in 30s", e)
                spikeResult = "FAIL_TIMEOUT"
            }

            val diag = bridge.dumpDiagnostics()
            for (line in diag.split("\n")) {
                Log.i(TAG, "DIAG: $line")
            }
        }
    }
}
