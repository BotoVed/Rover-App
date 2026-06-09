package dev.botoved.rover.ui.dashboard

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dev.botoved.rover.data.RoverRepository
import dev.botoved.rover.data.ServerPreferences
import dev.botoved.rover.data.db.AreaEntity
import dev.botoved.rover.data.db.DeviceEntity
import dev.botoved.rover.data.db.ServerMetaEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DeviceState(
    val shortId: Int,
    val name: String,
    val type: String,
    val areaId: Int?,
    val isOn: Boolean? = null,
    val primaryValue: String? = null,
    val isPending: Boolean = false
)

data class ZoneUiState(
    val areaId: Int?,
    val areaName: String,
    val devices: List<DeviceState>,
    val isExpanded: Boolean = true
)

data class DashboardUiState(
    val serverName: String = "",
    val zones: List<ZoneUiState> = emptyList(),
    val isOnline: Boolean = false,
    val isLoading: Boolean = true
)

class DashboardViewModel(
    private val repository: RoverRepository,
    private val context: android.content.Context
) : ViewModel() {

    private fun sendCmd(fields: Map<Int, Any>) {
        val json = org.json.JSONObject()
        fields.forEach { (k, v) -> json.put(k.toString(), v) }
        val intent = Intent("dev.botoved.rover.ACTION_CMD").apply {
            putExtra("fields", json.toString())
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        Log.i("Rover", "CMD broadcast fields=$fields")
    }

    fun onToggle(device: DeviceState, isOn: Boolean) {
        _deviceStates.value = _deviceStates.value.toMutableMap().apply {
            this[device.shortId] = (this[device.shortId] ?: device).copy(isPending = true)
        }
        sendCmd(mapOf(0 to 5, 9 to device.shortId, 2 to isOn))
    }

    fun onSlider(device: DeviceState, key: Int, value: Float) {
        sendCmd(mapOf(0 to 5, 9 to device.shortId, key to value.toInt()))
    }

    fun onAction(device: DeviceState, action: String) {
        val extraFields: Map<Int, Any> = when (device.type) {
            "CV" -> mapOf(6 to action)
            "LK" -> mapOf(2 to (action == "unlock"))
            "AL" -> mapOf(6 to action)
            "MS" -> mapOf(6 to action)
            else -> emptyMap()
        }
        sendCmd(mapOf(0 to 5, 9 to device.shortId) + extraFields)
    }

    fun onHvacMode(device: DeviceState, mode: String) {
        sendCmd(mapOf(0 to 5, 9 to device.shortId, 6 to mode))
    }

    fun onFanMode(device: DeviceState, mode: String) {
        sendCmd(mapOf(0 to 5, 9 to device.shortId, 7 to mode))
    }

    private val _deviceStates = MutableStateFlow<Map<Int, DeviceState>>(emptyMap())

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline

    private val _expandedZones = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val expandedZones: StateFlow<Map<Int, Boolean>> = _expandedZones

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.observeMeta(),
        repository.observeAreas(),
        repository.observeDevices(),
        _deviceStates,
        _expandedZones
    ) { meta, areas, devices, states, expanded ->
        Log.i("Rover", "VM: areas=${areas.size} devices=${devices.size} meta=${meta?.serverName}")
        val areaMap = areas.associateBy { it.id }

        val deviceStates = devices.map { entity ->
            val live = states[entity.shortId]
            live?.copy(
                name = entity.name,
                type = entity.type,
                areaId = entity.areaId
            ) ?: DeviceState(
                shortId = entity.shortId,
                name = entity.name,
                type = entity.type,
                areaId = entity.areaId
            )
        }

        val grouped = deviceStates.groupBy { it.areaId }

        val namedZones = areas.map { area ->
            ZoneUiState(
                areaId = area.id,
                areaName = area.name,
                devices = grouped[area.id] ?: emptyList(),
                isExpanded = expanded.getOrDefault(area.id, true)
            )
        }
        val noZone = (grouped[null] ?: emptyList()) +
            deviceStates.filter { it.areaId != null && it.areaId !in areaMap }
        val allZones = if (noZone.isNotEmpty()) {
            namedZones + ZoneUiState(
                areaId = null,
                areaName = "Устройства вне групп",
                devices = noZone,
                isExpanded = expanded.getOrDefault(-1, true)
            )
        } else {
            namedZones
        }

        DashboardUiState(
            serverName = meta?.serverName ?: "",
            zones = allZones,
            isOnline = _isOnline.value,
            isLoading = !repository.isConfigReceived
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(isLoading = true)
    )

    fun onStatusReceived(states: List<Map<*, *>>) {
        val current = _deviceStates.value.toMutableMap()
        for (s in states) {
            val id = (s["id"] as? Number)?.toInt() ?: continue
            val existing = current[id] ?: DeviceState(
                shortId = id, name = "", type = "", areaId = null
            )
            current[id] = existing.copy(
                isOn = parseIsOn(s),
                primaryValue = parsePrimaryValue(s)
            )
        }
        _deviceStates.value = current
    }

    fun onPushReceived(fields: Map<*, *>) {
        val id = (fields["9"] as? Number)?.toInt()
            ?: (fields[9] as? Number)?.toInt()
            ?: return
        Log.i("Rover", "PUSH received fields=$fields id=$id")
        val current = _deviceStates.value.toMutableMap()
        val existing = current[id] ?: DeviceState(
            shortId = id, name = "", type = "", areaId = null
        )
        current[id] = existing.copy(
            isOn = parseIsOn(fields),
            primaryValue = parsePrimaryValue(fields),
            isPending = false
        )
        _deviceStates.value = current
        Log.i("Rover", "PUSH applied id=$id isOn=${current[id]?.isOn} value=${current[id]?.primaryValue}")
    }

    fun onConnectionChanged(online: Boolean) {
        _isOnline.value = online
    }

    fun reconnect() {
        viewModelScope.launch {
            repository.clearAll()
            ServerPreferences(context).clear()
        }
    }

    fun onZoneExpandToggle(areaId: Int?) {
        _expandedZones.value = _expandedZones.value.toMutableMap().apply {
            val key = areaId ?: -1
            this[key] = !(this[key] ?: true)
        }
    }

    private fun parseIsOn(fields: Map<*, *>): Boolean? {
        // PUSH: integer key 1 = v
        // STATUS: string key "v"
        val v = fields["v"] ?: fields["1"] ?: fields[1] ?: return null
        Log.i("Rover", "parseIsOn fields keys=${fields.keys} v=$v")
        return when (v.toString()) {
            "on", "open", "playing", "unlocked", "true" -> true
            "off", "closed", "idle", "locked", "false", "paused", "standby" -> false
            else -> null
        }
    }

    private fun parsePrimaryValue(fields: Map<*, *>): String? {
        // Temperature (CL): key "t" or 6
        (fields["t"] ?: fields["6"] ?: fields[6])?.let { return "${it}°C" }
        // Brightness (LT): key "b" or 2
        (fields["b"] ?: fields["2"] ?: fields[2])?.let { return "${it}%" }
        // Position (CV): key "p" or 5
        (fields["p"] ?: fields["5"] ?: fields[5])?.let { return "${it}%" }
        // Volume (MS): key "vol" or 14
        (fields["vol"] ?: fields["14"] ?: fields[14])?.let { return "${it}%" }
        // Fan speed (FN): key "sp" or 21
        (fields["sp"] ?: fields["21"] ?: fields[21])?.let { return "${it}%" }
        // Primary value + unit (SE, others): key "v"/"1" + "u"/"25"
        val v = fields["v"] ?: fields["1"] ?: fields[1]
        val u = fields["u"] ?: fields["25"] ?: fields[25]
        if (v != null) return if (u != null) "$v $u" else "$v"
        return null
    }
}
