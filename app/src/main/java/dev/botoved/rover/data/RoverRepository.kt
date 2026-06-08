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
            serverName = (data["server_name"] as? String) ?: "Rover Hub",
            version = (data["version"] as? String) ?: "",
            brand = (data["brand"] as? String) ?: "Rover"
        )
        db.serverMetaDao().insert(meta)
        Log.i(TAG, "DB: meta saved name=${meta.serverName}")
    }

    suspend fun saveAreas(fields: Map<*, *>) {
        val list = fields[3] as? List<*> ?: return
        val entities = list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val id = (m["id"] as? Number)?.toInt() ?: return@mapNotNull null
            val name = (m["name"] as? String) ?: return@mapNotNull null
            AreaEntity(id = id, name = name)
        }
        db.areaDao().deleteAll()
        db.areaDao().insertAll(entities)
        Log.i(TAG, "DB: ${entities.size} areas saved")
    }

    suspend fun saveDevices(fields: Map<*, *>) {
        val list = fields[3] as? List<*> ?: return
        val entities = list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val id = (m["id"] as? Number)?.toInt() ?: return@mapNotNull null
            val name = (m["n"] as? String) ?: return@mapNotNull null
            val type = (m["t"] as? String) ?: return@mapNotNull null
            val areaId = (m["a"] as? Number)?.toInt()
            DeviceEntity(shortId = id, name = name, type = type, areaId = areaId)
        }
        db.deviceDao().deleteAll()
        db.deviceDao().insertAll(entities)
        Log.i(TAG, "DB: ${entities.size} devices saved")
    }
}
