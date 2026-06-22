package dev.botoved.rover.ui.onboarding

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dev.botoved.rover.data.ServerPreferences
import dev.botoved.rover.service.WifiChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed class OnboardingState {
    object Scanning : OnboardingState()
    data class Sending(val noWifi: Boolean = false) : OnboardingState()
    data class WaitingApproval(
        val step: String = "Отправка запроса...",
        val noWifi: Boolean = false
    ) : OnboardingState()
    object Approved : OnboardingState()
    data class Error(val message: String) : OnboardingState()
}

class OnboardingViewModel(
    private val prefs: ServerPreferences
) : ViewModel() {

    private val TAG = "Rover"

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Scanning)
    val state: StateFlow<OnboardingState> = _state
    private var approvalTimeoutJob: Job? = null

    fun onQrScanned(raw: String, context: Context) {
        try {
            val json = JSONObject(raw)
            val rvr = json.getJSONObject("rvr")
            val fmt = rvr.optInt("fmt", 1)

            if (fmt != 2) {
                _state.value = OnboardingState.Error(
                    "Устаревший QR (fmt=$fmt). Обнови Rover до версии 0.2.0+"
                )
                return
            }

            val dst = rvr.getString("dst")
            val nm = rvr.getString("nm")
            val pk = rvr.getString("pk")
            val tcp = rvr.optString("tcp").ifEmpty { null }
            val ssid = rvr.optString("ssid").ifEmpty { null }
            val uid = rvr.optString("uid", "")

            if (dst.length != 32 || !dst.all { it.isLetterOrDigit() }) {
                _state.value = OnboardingState.Error("Неверный destination hash: $dst")
                return
            }

            Log.i(TAG, "QR v2 parsed: dst=$dst nm=$nm tcp=$tcp ssid=$ssid uid=$uid")
            viewModelScope.launch {
                _state.value = OnboardingState.Sending(noWifi = tcp != null && ssid != null && !WifiChecker.isOnSsid(context, ssid))
                delay(100)
                prefs.saveServer(dst, nm, pk, tcp, ssid)
                prefs.saveUid(uid)

                val intent = Intent("dev.botoved.rover.ACTION_REGISTER").apply {
                    putExtra("dst", dst)
                    putExtra("pk", pk)
                    putExtra("uid", uid)
                    tcp?.let { putExtra("tcp", it) }
                    ssid?.let { putExtra("ssid", it) }
                }
                LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
                Log.i(TAG, "ACTION_REGISTER broadcast sent")
                _state.value = OnboardingState.WaitingApproval(
                    step = "Запрос отправлен, ожидаем сервер...",
                    noWifi = tcp != null && ssid != null && !WifiChecker.isOnSsid(context, ssid)
                )

                approvalTimeoutJob?.cancel()
                approvalTimeoutJob = viewModelScope.launch {
                    delay(150_000)
                    if (_state.value is OnboardingState.WaitingApproval) {
                        _state.value = OnboardingState.Error(
                            "Сервер не ответил за 150 секунд. Проверь подключение и попробуй снова."
                        )
                    }
                }
            }
        } catch (e: Exception) {
            _state.value = OnboardingState.Error("Не удалось прочитать QR: ${e.message}")
        }
    }

    fun onApproved() {
        approvalTimeoutJob?.cancel()
        _state.value = OnboardingState.Approved
        viewModelScope.launch {
            prefs.setApproved()
            Log.i(TAG, "Application approved — DataStore updated")
        }
    }

    fun onReset() {
        approvalTimeoutJob?.cancel()
        _state.value = OnboardingState.Scanning
    }

    fun updateStep(step: String) {
        val current = _state.value
        if (current is OnboardingState.WaitingApproval) {
            _state.value = current.copy(step = step)
        }
    }
}
