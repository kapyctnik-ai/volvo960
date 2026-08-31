package com.volvo960.obdctl.data

import com.volvo960.obdctl.transport.Elm327Transport

/**
 * Answers the only question that matters at the car: is this working, and if
 * not, which half is broken — the dongle, the protocol, or the ECU.
 *
 * It runs with the ignition on and the engine off. Every step here is answered
 * in that state: the adapter reports itself and the battery voltage without the
 * car's help, and an ECU that is awake answers `0100` and coolant temperature
 * with the engine stopped. Nothing needs the engine running, so verifying the
 * app costs no fuel and no five minutes of idling.
 *
 * When the ECU stays silent it retries on the other protocols before giving a
 * verdict. A 1996 960 is wired for ISO 9141-2, but a dongle that insists on
 * hunting, or a car answering KWP2000, would otherwise look identical to a dead
 * bus — and the difference is one AT command.
 */
class SelfTest(private val transport: Elm327Transport) {

    data class Result(
        val adapterId: String?,
        val voltage: String?,
        val protocolName: String?,
        /** Protocol number that the ECU actually answered on, if any. */
        val workingProtocol: String?,
        val ecuAnswered: Boolean,
        val coolantC: Int?,
        val supportedPids: String?,
        val transcript: List<Pair<String, String>>,
    )

    companion object {
        /** ISO 9141-2 first: that is what the car is wired for. */
        private val PROTOCOLS = listOf("3", "4", "5", "0")
        private const val TIMEOUT_MS = 9_000L
    }

    suspend fun run(currentProtocol: String): Result {
        val transcript = mutableListOf<Pair<String, String>>()

        val basics = transport.sendScriptVerbose(listOf("ATI", "ATRV", "ATDP"), TIMEOUT_MS)
        transcript += basics
        val adapterId = basics.getOrNull(0)?.second?.takeIf { it.isUsable() }
        val voltage = basics.getOrNull(1)?.second?.takeIf { it.isUsable() }
        val protocolName = basics.getOrNull(2)?.second?.takeIf { it.isUsable() }

        // Try the protocol in use first, then the rest — no point resetting the
        // adapter if it is already talking to the car.
        val order = listOf(currentProtocol) + PROTOCOLS.filter { it != currentProtocol }
        var working: String? = null
        var supported: String? = null
        for (protocol in order) {
            val step = transport.sendScriptVerbose(listOf("ATSP$protocol", "0100"), TIMEOUT_MS)
            transcript += step
            val reply = step.lastOrNull()?.second.orEmpty()
            if (reply.contains("41 00") || reply.replace(" ", "").contains("4100")) {
                working = protocol
                supported = reply
                break
            }
        }

        var coolant: Int? = null
        if (working != null) {
            val step = transport.sendScriptVerbose(listOf("0105"), TIMEOUT_MS)
            transcript += step
            coolant = parseCoolant(step.lastOrNull()?.second.orEmpty())
        }

        return Result(
            adapterId = adapterId,
            voltage = voltage,
            protocolName = protocolName,
            workingProtocol = working,
            ecuAnswered = working != null,
            coolantC = coolant,
            supportedPids = supported,
            transcript = transcript,
        )
    }

    private fun String.isUsable(): Boolean =
        isNotBlank() && !startsWith("ОШИБКА") && this != "(пусто)"

    private fun parseCoolant(reply: String): Int? {
        val bytes = reply.uppercase().split(Regex("[^0-9A-F]+")).filter { it.length == 2 }
        val at = bytes.indexOfFirst { it == "41" }
        if (at == -1 || at + 2 >= bytes.size || bytes[at + 1] != "05") return null
        return bytes[at + 2].toIntOrNull(16)?.minus(40)
    }
}
