package dev.botoved.rover.service

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform
import dev.botoved.rover.AppLogger

class PyRnsBridge(private val context: Context) {

    private val TAG = "Rover"
    private var pyModule: PyObject? = null

    private val incomingCallback = MessageCallback()

    inner class MessageCallback {
        private var handler: ((String, Map<Int, Any?>) -> Unit)? = null

        fun setHandler(h: (String, Map<Int, Any?>) -> Unit) {
            handler = h
        }

        @Suppress("unused")
        fun onMessage(sourceHex: String, fieldsJson: String) {
            try {
                val json = org.json.JSONObject(fieldsJson)
                val fields = mutableMapOf<Int, Any?>()
                json.keys().forEach { k ->
                    val intKey = k.toIntOrNull() ?: return@forEach
                    fields[intKey] = fromJson(json.get(k))
                }
                AppLogger.i(TAG, "PyRNS msg src=$sourceHex tp=${fields[0]}")
                handler?.invoke(sourceHex, fields)
            } catch (e: Exception) {
                AppLogger.e(TAG, "PyRNS msg parse error: ${e.message}", e)
            }
        }

        private fun fromJson(v: Any?): Any? = when (v) {
            is org.json.JSONObject -> {
                val m = mutableMapOf<String, Any?>()
                v.keys().forEach { k -> m[k as String] = fromJson(v.get(k)) }
                m
            }
            is org.json.JSONArray -> (0 until v.length()).map { fromJson(v.get(it)) }
            org.json.JSONObject.NULL -> null
            else -> v
        }
    }

    fun init(): Boolean {
        if (!Python.isStarted()) {
            try {
                Python.start(AndroidPlatform(context))
            } catch (e: Exception) {
                AppLogger.e(TAG, "Python.start failed: ${e.message}", e)
                return false
            }
        }
        val py = Python.getInstance()
        val module = py.getModule("rover_rns")
        module.callAttr("set_incoming_callback", PyObject.fromJava(incomingCallback))
        pyModule = module
        bridgeRef = this
        AppLogger.i(TAG, "PyRnsBridge initialized")
        return true
    }

    fun start(configDir: String, host: String, port: Int): String? {
        val module = pyModule ?: return null
        return try {
            val result = module.callAttr("start", configDir, host, port)
            val identity = result.toString()
            AppLogger.i(TAG, "PyRNS identity=$identity host=$host:$port")
            identity
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("already running", ignoreCase = true)) {
                AppLogger.i(TAG, "PyRNS start: RNS already running, getting identity directly")
                return try {
                    val result = module.callAttr("get_identity", configDir)
                    val identity = result.toString()
                    AppLogger.i(TAG, "PyRNS identity (reinit)=$identity host=$host:$port")
                    identity
                } catch (e2: Exception) {
                    AppLogger.e(TAG, "PyRNS get_identity failed: ${e2.message}", e2)
                    null
                }
            }
            AppLogger.e(TAG, "PyRNS start failed: ${e.message}", e)
            null
        }
    }

    fun requestPathAndWait(destHex: String, timeoutS: Double = 3.0): Boolean {
        val module = pyModule ?: return false
        return try {
            val result = module.callAttr("request_path_and_wait", destHex, timeoutS)
            val ok = result.toString().toBoolean()
            AppLogger.i(TAG, "PyRNS requestPathAndWait dest=$destHex result=$ok")
            ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "PyRNS requestPathAndWait failed: ${e.message}", e)
            false
        }
    }

    fun activeChannel(): String {
        val module = pyModule ?: return "none"
        return try {
            val result = module.callAttr("active_channel")
            val channel = result.toString()
            AppLogger.i(TAG, "PyRNS activeChannel=$channel")
            channel
        } catch (e: Exception) {
            AppLogger.e(TAG, "PyRNS activeChannel failed: ${e.message}", e)
            "error"
        }
    }

    fun setServerPk(pkB64: String): Boolean {
        val module = pyModule ?: return false
        return try {
            val result = module.callAttr("set_server_pk", pkB64)
            val ok = result.toString().toBoolean()
            AppLogger.i(TAG, "PyRNS setServerPk ok=$ok")
            ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "PyRNS setServerPk failed: ${e.message}", e)
            false
        }
    }

    fun sendRegister(destHex: String, uid: String): Boolean {
        val module = pyModule ?: return false
        return try {
            val result = module.callAttr("send_register", destHex, uid)
            val ok = result.toString().toBoolean()
            AppLogger.i(TAG, "PyRNS sendRegister dest=$destHex uid=$uid ok=$ok")
            ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "PyRNS sendRegister failed: ${e.message}", e)
            false
        }
    }

    fun sendPing(destHex: String, hashes: Map<*, *> = emptyMap<Any, Any>()): Boolean {
        val module = pyModule ?: return false
        return try {
            val pyHashes = PyObject.fromJava(hashes)
            val result = module.callAttr("send_ping", destHex, pyHashes)
            val ok = result.toString().toBoolean()
            AppLogger.i(TAG, "PyRNS sendPing dest=$destHex ok=$ok")
            ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "PyRNS sendPing failed: ${e.message}", e)
            false
        }
    }

    fun sendReq(destHex: String, sections: List<String>): Boolean {
        val module = pyModule ?: return false
        return try {
            val pySections = PyObject.fromJava(sections)
            val result = module.callAttr("send_req", destHex, pySections)
            val ok = result.toString().toBoolean()
            AppLogger.i(TAG, "PyRNS sendReq dest=$destHex sections=$sections ok=$ok")
            ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "PyRNS sendReq failed: ${e.message}", e)
            false
        }
    }

    fun send(destHex: String, fields: Map<*, *>, awaitPath: Boolean = false): Boolean {
        val module = pyModule ?: return false
        return try {
            val pyFields = PyObject.fromJava(fields)
            val result = module.callAttr("send", destHex, pyFields, awaitPath)
            val ok = result.toString().toBoolean()
            AppLogger.i(TAG, "PyRNS send dest=$destHex ok=$ok")
            ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "PyRNS send failed: ${e.message}", e)
            false
        }
    }

    fun setMessageHandler(handler: (String, Map<Int, Any?>) -> Unit) {
        incomingCallback.setHandler(handler)
    }

    fun dumpDiagnostics(): String {
        val module = pyModule ?: return "no module"
        return try {
            val result = module.callAttr("dump_diagnostics")
            result.toString()
        } catch (e: Exception) {
            AppLogger.e(TAG, "PyRNS dumpDiagnostics failed: ${e.message}", e)
            "error"
        }
    }

    companion object {
        private val TAG = "Rover"
        @JvmStatic var bridgeRef: PyRnsBridge? = null

        fun isBridgeAvailable(): Boolean = bridgeRef != null

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
                AppLogger.e(TAG, "SPIKE FAILED: Python init")
                return
            }
            val configDir = context.filesDir.absolutePath + "/rover_rns"
            val identity = bridge.start(configDir, HAOS_HOST, HAOS_PORT)
            if (identity == null) {
                AppLogger.e(TAG, "SPIKE FAILED: start returned null")
                return
            }
            AppLogger.i(TAG, "SPIKE client identity=$identity")

            val channel = bridge.activeChannel()
            AppLogger.i(TAG, "SPIKE activeChannel=$channel")

            // Set up message handler before sending register
            val configReceived = java.util.concurrent.CompletableFuture<Boolean>()
            bridge.setMessageHandler { srcHex, fields ->
                val tp = (fields[0] as? Number)?.toInt()
                AppLogger.i(TAG, "SPIKE msg src=$srcHex tp=$tp fields=$fields")
                if (tp == 4 || tp == 7) { // CONFIG=4, FORBIDDEN=7
                    configReceived.complete(tp == 4)
                }
            }

            val pathOk = bridge.requestPathAndWait(SERVER_IDENTITY_HEX, 120.0)
            AppLogger.i(TAG, "SPIKE requestPathAndWait=$pathOk")
            if (!pathOk) {
                AppLogger.e(TAG, "SPIKE ABORT: path not found")
                return
            }

            // Send REGISTER with QR uid
            val regOk = bridge.sendRegister(SERVER_IDENTITY_HEX, QR_UID)
            AppLogger.i(TAG, "SPIKE sendRegister=$regOk")
            if (!regOk) {
                AppLogger.e(TAG, "SPIKE ABORT: sendRegister failed")
                return
            }

            // Wait for CONFIG (30s timeout)
            try {
                val approved = configReceived.get(30000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (approved) {
                    AppLogger.i(TAG, "SPIKE ONBOARDING PASSED: CONFIG received")
                    spikeResult = "PASS"
                } else {
                    Log.w(TAG, "SPIKE ONBOARDING FAILED: FORBIDDEN received")
                    spikeResult = "FAIL_FORBIDDEN"
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "SPIKE ONBOARDING TIMEOUT: no CONFIG/FORBIDDEN in 30s", e)
                spikeResult = "FAIL_TIMEOUT"
            }

            val diag = bridge.dumpDiagnostics()
            for (line in diag.split("\n")) {
                AppLogger.i(TAG, "DIAG: $line")
            }
        }

        fun debugSendReqArray() {
            val bridge = bridgeRef
            if (bridge == null) {
                AppLogger.e(TAG, "DEBUG: no bridge available")
                return
            }
            val ok = bridge.sendReq(SERVER_IDENTITY_HEX, listOf("m", "a", "d"))
            AppLogger.i(TAG, "DEBUG sendReq(m,a,d)=$ok")
        }
    }
}
