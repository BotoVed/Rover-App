package dev.botoved.rover.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "areas")
data class AreaEntity(
    @PrimaryKey val id: Int,
    val name: String
)

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val shortId: Int,
    val name: String,
    val type: String,
    val areaId: Int?
)

@Entity(tableName = "server_meta")
data class ServerMetaEntity(
    @PrimaryKey val id: Int = 1,
    val serverName: String,
    val version: String,
    val brand: String
)

@Dao
interface AreaDao {
    @Query("SELECT * FROM areas ORDER BY name ASC")
    fun observeAll(): Flow<List<AreaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(areas: List<AreaEntity>)

    @Query("DELETE FROM areas")
    suspend fun deleteAll()
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY name ASC")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(devices: List<DeviceEntity>)

    @Query("DELETE FROM devices")
    suspend fun deleteAll()
}

@Dao
interface ServerMetaDao {
    @Query("SELECT * FROM server_meta WHERE id = 1")
    fun observe(): Flow<ServerMetaEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(meta: ServerMetaEntity)
}

@Database(
    entities = [AreaEntity::class, DeviceEntity::class, ServerMetaEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RoverDatabase : RoomDatabase() {
    abstract fun areaDao(): AreaDao
    abstract fun deviceDao(): DeviceDao
    abstract fun serverMetaDao(): ServerMetaDao
}
