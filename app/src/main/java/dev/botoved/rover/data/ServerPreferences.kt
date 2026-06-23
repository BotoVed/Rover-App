package dev.botoved.rover.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "rover_prefs")

class ServerPreferences(private val context: Context) {

    companion object {
        val SERVER_DEST_HASH = stringPreferencesKey("server_dest_hash")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val SERVER_PK = stringPreferencesKey("server_pk")
        val SERVER_TCP = stringPreferencesKey("server_tcp")
        val SERVER_SSID = stringPreferencesKey("server_ssid")
        val IS_REGISTERED = stringPreferencesKey("is_registered")
        val KEY_UID = stringPreferencesKey("uid")
        val RNODE_ENABLED = booleanPreferencesKey("rnode_enabled")
        val RNODE_PORT = stringPreferencesKey("rnode_port")
        val RNODE_FREQ = longPreferencesKey("rnode_freq")
        val RNODE_BW = longPreferencesKey("rnode_bw")
        val RNODE_SF = intPreferencesKey("rnode_sf")
        val RNODE_CR = intPreferencesKey("rnode_cr")
        val RNODE_TXPOWER = intPreferencesKey("rnode_txpower")
    }

    val serverDestHash: Flow<String?> = context.dataStore.data
        .map { it[SERVER_DEST_HASH] }

    val serverName: Flow<String?> = context.dataStore.data
        .map { it[SERVER_NAME] }

    val serverPk: Flow<String?> = context.dataStore.data
        .map { it[SERVER_PK] }

    val serverTcp: Flow<String?> = context.dataStore.data
        .map { it[SERVER_TCP] }

    val serverSsid: Flow<String?> = context.dataStore.data
        .map { it[SERVER_SSID] }

    val isRegistered: Flow<String?> = context.dataStore.data
        .map { it[IS_REGISTERED] }

    suspend fun saveServer(
        destHash: String,
        name: String,
        pk: String,
        tcp: String?,
        ssid: String?
    ) {
        context.dataStore.edit { prefs ->
            prefs[SERVER_DEST_HASH] = destHash
            prefs[SERVER_NAME] = name
            prefs[SERVER_PK] = pk
            tcp?.let { prefs[SERVER_TCP] = it }
            ssid?.let { prefs[SERVER_SSID] = it }
            prefs[IS_REGISTERED] = "pending"
        }
    }

    suspend fun setApproved() {
        context.dataStore.edit { it[IS_REGISTERED] = "approved" }
    }

    val uid: Flow<String?> = context.dataStore.data
        .map { it[KEY_UID] }

    suspend fun saveUid(uid: String) {
        context.dataStore.edit { it[KEY_UID] = uid }
    }

    val rnodeEnabled: Flow<Boolean> = context.dataStore.data.map { it[RNODE_ENABLED] ?: false }
    val rnodePort: Flow<String> = context.dataStore.data.map { it[RNODE_PORT] ?: "/dev/ttyUSB0" }
    val rnodeFreq: Flow<Long> = context.dataStore.data.map { it[RNODE_FREQ] ?: 869500000L }
    val rnodeBw: Flow<Long> = context.dataStore.data.map { it[RNODE_BW] ?: 125000L }
    val rnodeSf: Flow<Int> = context.dataStore.data.map { it[RNODE_SF] ?: 7 }
    val rnodeCr: Flow<Int> = context.dataStore.data.map { it[RNODE_CR] ?: 5 }
    val rnodeTxpower: Flow<Int> = context.dataStore.data.map { it[RNODE_TXPOWER] ?: 14 }

    suspend fun saveRNodeConfig(
        enabled: Boolean, port: String, freq: Long, bw: Long, sf: Int, cr: Int, txpower: Int
    ) {
        context.dataStore.edit { prefs ->
            prefs[RNODE_ENABLED] = enabled
            prefs[RNODE_PORT] = port
            prefs[RNODE_FREQ] = freq
            prefs[RNODE_BW] = bw
            prefs[RNODE_SF] = sf
            prefs[RNODE_CR] = cr
            prefs[RNODE_TXPOWER] = txpower
        }
    }

    fun buildRNodeJson(
        enabled: Boolean, port: String, freq: Long, bw: Long, sf: Int, cr: Int, txpower: Int
    ): String = org.json.JSONObject().apply {
        put("enabled", enabled)
        put("port", port)
        put("frequency", freq)
        put("bandwidth", bw)
        put("spreadingfactor", sf)
        put("codingrate", cr)
        put("txpower", txpower)
    }.toString()

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
