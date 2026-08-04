package com.volvo960.obdctl.data

/** How an actuator's command is meant to be sent. */
enum class ActuatorBehavior {
    /** Fired once on tap, e.g. a stateless "pulse" relay command. */
    ONCE,

    /** Repeated on an interval while the user holds the toggle on. */
    HOLD_REPEAT,
}
