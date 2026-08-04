package com.volvo960.obdctl.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun behaviorToString(value: ActuatorBehavior): String = value.name

    @TypeConverter
    fun stringToBehavior(value: String): ActuatorBehavior =
        runCatching { ActuatorBehavior.valueOf(value) }.getOrDefault(ActuatorBehavior.HOLD_REPEAT)
}
