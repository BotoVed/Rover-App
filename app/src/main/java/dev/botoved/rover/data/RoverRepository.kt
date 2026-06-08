package dev.botoved.rover.data

import android.util.Log
import dev.botoved.rover.data.db.*
import kotlinx.coroutines.flow.Flow

class RoverRepository(private val db: RoverDatabase) {

    private val TAG = "Rover"

    fun observeAreas(): Flow<List<AreaEntity>> = db.areaDao().observeAll()
    fun observeDevices(): Flow<List<DeviceEntity>> = db.deviceDao().observeAll()
    fun observeMeta(): Flow<ServerMetaEntity?> = db.serverMetaDao().observe()

    suspend fun saveMeta(fields: Map<*, *>) {
        val data = fields[3] as? Map<*, *> ?: return
        val meta = ServerMetaEntity(
            brand = (data[0L] as? String) ?: "Rover",
            version = (data[1L] as? String) ?: "",
            serverName = (data[2L] as? String) ?: "Rover Hub"
        )
        db.serverMetaDao().insert(meta)
        Log.i(TAG, "DB: meta saved name=${meta.serverName}")
    }

    suspend fun saveAreas(fields: Map<*, *>) {
        val list = fields[3] as? List<*> ?: return
        val entities = list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val id = (m[0L] as? Number)?.toInt() ?: return@mapNotNull null
            val name = (m[1L] as? String) ?: return@mapNotNull null
            AreaEntity(id = id, name = name)
        }
        db.areaDao().deleteAll()
        db.areaDao().insertAll(entities)
        Log.i(TAG, "DB: ${entities.size} areas saved")
    }

    suspend fun clearAll() {
        db.areaDao().deleteAll()
        db.deviceDao().deleteAll()
        Log.i(TAG, "DB: all tables cleared")
    }

    private fun Any?.toSafeString(): String? = when (this) {
        is String -> this
        is Number -> this.toString()
        else -> null
    }

    suspend fun saveDevices(fields: Map<*, *>) {
        val list = fields[3] as? List<*> ?: return
        Log.i(TAG, "DB: saveDevices called, list.size=${list.size}")
        Log.i(TAG, "DB: saving ${list.size} devices")
        if (list.isEmpty()) return
        val entities = list.mapNotNull { item ->
            val m = item as? Map<*, *>
            if (m == null) {
                Log.w(TAG, "DB: skip device — not a Map: ${item?.javaClass}")
                return@mapNotNull null
            }
            Log.i(TAG, "DB: raw item=$m")
            Log.i(TAG, "DB: item key types=${m.keys.map { "${it}:${it?.javaClass?.simpleName}" }}")
            Log.i(TAG, "DB: item[0L]=${m[0L]} (${m[0L]?.javaClass?.simpleName})")
            Log.i(TAG, "DB: item[1L]=${m[1L]} (${m[1L]?.javaClass?.simpleName})")
            Log.i(TAG, "DB: item[2L]=${m[2L]} (${m[2L]?.javaClass?.simpleName})")
            Log.i(TAG, "DB: item[3L]=${m[3L]} (${m[3L]?.javaClass?.simpleName})")
            val id = (m[0L] as? Number)?.toInt()
            val name = m[1L].toSafeString()
            val type = m[2L].toSafeString()
            val areaId = (m[3L] as? Number)?.toInt()
            if (id == null || name == null || type == null) {
                Log.w(TAG, "DB: skip device — missing field: id=$id name=$name type=$type")
                return@mapNotNull null
            }
            DeviceEntity(shortId = id, name = name, type = type, areaId = areaId)
        }
        db.deviceDao().deleteAll()
        db.deviceDao().insertAll(entities)
        Log.i(TAG, "DB: ${entities.size} devices saved")
    }
}
