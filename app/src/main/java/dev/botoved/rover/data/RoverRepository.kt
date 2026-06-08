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
            brand = (data[0] as? String) ?: "Rover",
            version = (data[1] as? String) ?: "",
            serverName = (data[2] as? String) ?: "Rover Hub"
        )
        db.serverMetaDao().insert(meta)
        Log.i(TAG, "DB: meta saved name=${meta.serverName}")
    }

    suspend fun saveAreas(fields: Map<*, *>) {
        val list = fields[3] as? List<*> ?: return
        val entities = list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val id = (m[0] as? Number)?.toInt() ?: return@mapNotNull null
            val name = (m[1] as? String) ?: return@mapNotNull null
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

    suspend fun saveDevices(fields: Map<*, *>) {
        val list = fields[3] as? List<*> ?: return
        val entities = list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val id = (m[0] as? Number)?.toInt() ?: return@mapNotNull null
            val name = (m[1] as? String) ?: return@mapNotNull null
            val type = (m[2] as? String) ?: return@mapNotNull null
            val areaId = (m[3] as? Number)?.toInt()
            DeviceEntity(shortId = id, name = name, type = type, areaId = areaId)
        }
        db.deviceDao().deleteAll()
        db.deviceDao().insertAll(entities)
        Log.i(TAG, "DB: ${entities.size} devices saved")
    }
}
