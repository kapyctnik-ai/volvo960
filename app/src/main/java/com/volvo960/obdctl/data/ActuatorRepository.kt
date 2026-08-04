package com.volvo960.obdctl.data

import kotlinx.coroutines.flow.Flow

class ActuatorRepository(private val dao: ActuatorDao) {

    fun observeAll(): Flow<List<Actuator>> = dao.observeAll()

    suspend fun getById(id: Long): Actuator? = dao.getById(id)

    suspend fun save(actuator: Actuator): Long =
        if (actuator.id == 0L) dao.insert(actuator) else { dao.update(actuator); actuator.id }

    suspend fun delete(actuator: Actuator) = dao.delete(actuator)

    suspend fun acknowledgeWarning(id: Long) = dao.acknowledgeWarning(id)
}
