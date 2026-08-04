package com.volvo960.obdctl.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ActuatorDao {
    @Query("SELECT * FROM actuators ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<Actuator>>

    @Query("SELECT * FROM actuators WHERE id = :id")
    suspend fun getById(id: Long): Actuator?

    @Insert
    suspend fun insert(actuator: Actuator): Long

    @Update
    suspend fun update(actuator: Actuator)

    @Delete
    suspend fun delete(actuator: Actuator)

    @Query("UPDATE actuators SET warningAcknowledged = 1 WHERE id = :id")
    suspend fun acknowledgeWarning(id: Long)
}
