package com.volvo960.obdctl.service

/** Runtime state of one actuator's active hold-repeat loop, for UI and notification display. */
data class HoldStatus(
    val actuatorId: Long,
    val actuatorName: String,
    val startedAt: Long,
    val repeatIntervalMs: Long,
    val lastAttemptAt: Long,
    val lastSuccessAt: Long,
    val lastError: String? = null,
)
