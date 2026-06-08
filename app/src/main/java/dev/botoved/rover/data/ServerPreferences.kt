package dev.botoved.rover.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
