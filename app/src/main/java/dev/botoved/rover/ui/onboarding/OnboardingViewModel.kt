package dev.botoved.rover.ui.onboarding

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dev.botoved.rover.data.ServerPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed class OnboardingState {
    object Scanning : OnboardingState()
    data class Confirming(
        val destHash: String,
        val name: String,
        val pk: String,
        val tcp: String?,
        val ssid: String?,
        val uid: String
    ) : OnboardingState()
    object Sending : OnboardingState()
    object WaitingApproval : OnboardingState()
    object Approved : OnboardingState()
    data class Error(val message: String) : OnboardingState()
}

class OnboardingViewModel(
    private val prefs: ServerPreferences
) : ViewModel() {

    private val TAG = "Rover"

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Scanning)
    val state: StateFlow<OnboardingState> = _state

    fun onQrScanned(raw: String) {
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
            _state.value = OnboardingState.Confirming(dst, nm, pk, tcp, ssid, uid)
        } catch (e: Exception) {
            _state.value = OnboardingState.Error("Не удалось прочитать QR: ${e.message}")
        }
    }

    fun onConfirm(
        destHash: String,
        name: String,
        pk: String,
        tcp: String?,
        ssid: String?,
        uid: String,
        context: Context
    ) {
        viewModelScope.launch {
            _state.value = OnboardingState.Sending
            prefs.saveServer(destHash, name, pk, tcp, ssid)
            prefs.saveUid(uid)

            val intent = Intent("dev.botoved.rover.ACTION_REGISTER").apply {
                putExtra("dst", destHash)
                putExtra("pk", pk)
                putExtra("uid", uid)
                tcp?.let { putExtra("tcp", it) }
                ssid?.let { putExtra("ssid", it) }
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            Log.i(TAG, "ACTION_REGISTER broadcast sent")
            _state.value = OnboardingState.WaitingApproval
        }
    }

    fun onApproved() {
        _state.value = OnboardingState.Approved
    }

    fun onReset() {
        _state.value = OnboardingState.Scanning
    }
}
