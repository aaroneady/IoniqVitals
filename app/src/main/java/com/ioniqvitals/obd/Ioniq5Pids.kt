package com.ioniqvitals.obd

import android.util.Log
import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdRawResponse

/**
 * Hyundai Ioniq 5 EV PIDs.
 */
object Ioniq5Pids {

    const val BMS_CAN_HEADER = "7E4"
    const val ICCU_CAN_HEADER = "7E5"
    const val IGMP_CAN_HEADER = "770"
    const val BCM_CAN_HEADER = "7A0"

    const val SOC_PERCENT_BYTE = 31
    const val SOH_PERCENT_HIGH_BYTE = 25
    const val SOH_PERCENT_LOW_BYTE = 26
    const val AUX_SOC_PERCENT_BYTE = 20
    const val COOLANT_TEMP_CELSIUS_BYTE = 22
    const val BRAKE_LIGHT_STATE_BYTE = 6
    const val HEADLIGHT_BC10_STATE_BYTE = 4
    const val HEADLIGHT_BC09_STATE_BYTE = 6
    const val AMPS_BYTE = 10
    const val VOLTAGE_BYTE = 12
    const val SPEED_KPH_BYTE = 14
    const val CONFIRMATION_HEADER_0105 = "620105"
    const val CONFIRMATION_HEADER_E011 = "62E011"
    const val CONFIRMATION_HEADER_0101 = "620101"
    const val CONFIRMATION_HEADER_B008 = "62B008"
    const val CONFIRMATION_HEADER_BC09 = "62BC09"
    const val CONFIRMATION_HEADER_BC10 = "62BC10"

    // Brake-lamp status — VERIFIED on the vehicle: PID 22BC06 @ header 770 (IGMP), byte 4.
    // 0x00 = off, 0x2D = on via pedal, 0x2C = on via regen/i-Pedal (other non-zero codes for
    // auto-hold/HDA). Any non-zero = brake lamp commanded on. This is the car's own lamp state,
    // covering both pedal and regen.
    const val CONFIRMATION_HEADER_BC06 = "62BC06"
    const val BRAKE_LAMP_BC06_BYTE = 4

    // Vehicle speed — VERIFIED on the vehicle: PID 220100 @ header 7B3 (instrument cluster),
    // byte 29, read directly as km/h (matched the speedometer via the on-car probe). Parser is
    // kept dormant (no consumer yet); reinstate the per-loop 220100 poll in ObdReaderService
    // when a speed tile/feature needs it.
    const val CONFIRMATION_HEADER_0100 = "620100"
    const val VEHICLE_SPEED_BYTE = 29

    class Mode22Snapshot(
        tag: String,
        name: String,
        pid: String,
    ) : ObdCommand() {
        override val tag = tag
        override val name = name
        override val mode = "22"
        override val pid = pid
        override val defaultUnit = ""
        override val handler: (ObdRawResponse) -> String = { _ -> "" }
    }

    val Bms0101 = Mode22Snapshot("IONIQ5_BMS_0101", "BMS 0101", "0101")
    val Bms0105 = Mode22Snapshot("IONIQ5_BMS_0105", "BMS 0105", "0105")
    val IccuE011 = Mode22Snapshot("IONIQ5_ICCU_E011", "ICCU E011", "E011")
    val IgmpBc09 = Mode22Snapshot("IONIQ5_IGMP_BC09", "IGMP BC09", "BC09")
    val IgmpBc10 = Mode22Snapshot("IONIQ5_IGMP_BC10", "IGMP BC10", "BC10")
    val BcmB008 = Mode22Snapshot("IONIQ5_BCM_B008", "BCM B008", "B008")

    // Brake light velocity threshold calculation values.
    private var lastSpeedKmH: Int = -1
    private var lastTimeMs: Long = 0
    private var isBrakeLightOn: Boolean = false
    // Last computed decel/regen brake output, held between recomputes. The hysteresis only
    // re-evaluates once >250ms of speed delta has accumulated; on the faster intermediate
    // poll loops we report this held value instead of dropping to false (which would flicker).
    private var lastDecelState: Boolean = false

    /** Snapshot of the decel/regen intermediate values for on-screen debugging. */
    data class DecelDebug(
        val speedKmH: Int,
        val prevSpeedKmH: Int,
        val deltaTimeS: Float,
        val accelMs2: Float,
        val currentAmps: Float,
        val voltageVolts: Float,
        val powerKw: Float,
        val hysteresisOn: Boolean,
        val output: Boolean,
        val recomputed: Boolean,
        val note: String? = null,
    )

    /** Most recent decel computation, updated every parseDecelerationState() call. */
    var lastDecelDebug: DecelDebug? = null
        private set

    // Deceleration thresholds (m/s^2) as negative acceleration. Retained for the debug
    // readout / future hybrid mode; no longer the trigger (speed PID 010D is unsupported
    // on the Ioniq 5, so the accel delta is unreliable).
    private const val TURN_ON_THRESHOLD_MS2 = -1.3f
    private const val TURN_OFF_THRESHOLD_MS2 = -0.7f

    // Regen power thresholds (kW, negative = energy flowing INTO the pack = regen braking).
    // Used only by the power-decel fallback now that brake state comes from the direct
    // brake-lamp PID (22195E). Hysteresis: arm at strong regen (ON, more negative), disarm
    // when regen eases (OFF, less negative).
    private const val REGEN_ON_THRESHOLD_KW = -8.0f
    private const val REGEN_OFF_THRESHOLD_KW = -3.0f

    fun parseSocPercent(response: ObdRawResponse): Float? {
        val byte = parseIsoTpPayload(response.value, SOC_PERCENT_BYTE, CONFIRMATION_HEADER_0105) ?: return null
        return byte / 2f
    }

    fun parseSohPercent(response: ObdRawResponse): Float? {
        val high = parseIsoTpPayload(response.value, SOH_PERCENT_HIGH_BYTE, CONFIRMATION_HEADER_0105) ?: return null
        val low = parseIsoTpPayload(response.value, SOH_PERCENT_LOW_BYTE, CONFIRMATION_HEADER_0105) ?: return null
        return ((high * 256) + low) / 10f
    }

    fun parseAuxSocPercent(response: ObdRawResponse): Float? {
        return parseIsoTpPayload(response.value, AUX_SOC_PERCENT_BYTE, CONFIRMATION_HEADER_E011)?.toFloat()
    }

    fun parseCoolantTempCelsius(response: ObdRawResponse): Float? {
        val byte = parseIsoTpPayload(response.value, COOLANT_TEMP_CELSIUS_BYTE, CONFIRMATION_HEADER_0101) ?: return null
        return (toSignedInt(byte) - 40).toFloat()
    }

    private fun toSignedInt(value: Int): Int = if (value > 127) value - 256 else value

    /**
     * Reassembles a raw ELM327 / OBDLink CX multi-frame ISO-TP response into a single
     * contiguous hex string of the full message (starting with the 0x62 positive-response
     * byte).
     *
     * The OBDLink prints multi-frame replies as `<3-nibble length><frame#>:<data>` per
     * frame, e.g. `00B0:62B0080400081:0000001000AAAA` where `00B` is the 11-byte ISO-TP
     * length, `0:`/`1:` are frame indices, and trailing `AA`s are padding. We use the
     * length field to discard the frame-index markers and padding *precisely* — never
     * by stripping literal "AA", which would corrupt any real 0xAA data byte.
     */
    fun reassembleIsoTp(rawLog: String): String? {
        val s = rawLog.replace(" ", "").uppercase().trim()
        if (s.isEmpty() || s == "STOPPED" || s == "SEARCHING" || s == "NODATA") return null

        // Single-frame responses have no frame markers.
        if (!s.contains(":")) return s

        val segments = s.split(":").filter { it.isNotEmpty() }
        if (segments.size < 2) return null

        // First segment is "<3-nibble total length><frame index 0>", e.g. "02E0" -> 0x02E bytes.
        val first = segments[0]
        if (first.length < 4) return null
        val totalLen = first.take(3).toIntOrNull(16) ?: return null

        val data = StringBuilder()
        for (i in 1 until segments.size) {
            val seg = segments[i]
            if (i < segments.size - 1) {
                // The final nibble of an intermediate segment is the NEXT frame's index.
                if (seg.length > 1) data.append(seg.substring(0, seg.length - 1))
            } else {
                data.append(seg)
            }
        }

        val full = data.toString()
        val hexLen = totalLen * 2
        return if (full.length >= hexLen) full.substring(0, hexLen) else full
    }

    /**
     * Extracts the payload that follows [confirmationHeader] (e.g. "620105") in a
     * reassembled ISO-TP response. Byte indices used by the parsers are relative to the
     * first byte after the confirmation header.
     */
    fun getStitchedPayload(rawLog: String, confirmationHeader: String): String? {
        val full = reassembleIsoTp(rawLog) ?: return null
        val idx = full.indexOf(confirmationHeader)
        if (idx == -1) return null
        return full.substring(idx + confirmationHeader.length)
    }

    fun parseIsoTpPayload(rawLog: String, byteIndex: Int, confirmationHeader: String): Int? {
        val cleanPayload = getStitchedPayload(rawLog, confirmationHeader) ?: return null
        
        val hexPairStart = byteIndex * 2
        if (hexPairStart + 2 > cleanPayload.length) {
            Log.w("Ioniq5Pids", "Index $byteIndex out of bounds for payload $cleanPayload")
            return null
        }

        return try {
            cleanPayload.substring(hexPairStart, hexPairStart + 2).toInt(16)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads a big-endian 16-bit value starting at [byteIndex] (high byte) of the payload.
     * Returned as an unsigned 0..65535 Int; callers needing a signed value should apply
     * `.toShort()`. Returns null if either byte is out of range.
     */
    fun parseIsoTpWord(rawLog: String, byteIndex: Int, confirmationHeader: String): Int? {
        val high = parseIsoTpPayload(rawLog, byteIndex, confirmationHeader) ?: return null
        val low = parseIsoTpPayload(rawLog, byteIndex + 1, confirmationHeader) ?: return null
        return (high shl 8) or low
    }

    fun parseBrakePedalState(response: ObdRawResponse): Boolean? {
        // Fist check the byte for depressing the brake pedal.
        val brakePedalState =
            parseIsoTpPayload(response.value, BRAKE_LIGHT_STATE_BYTE, CONFIRMATION_HEADER_B008)
                ?: return null
        return (brakePedalState and 0x10) != 0
    }

    /**
     * Brake-lamp status from PID 22BC06 (header 770), byte 4 — the car's own commanded lamp
     * state, covering both the physical pedal (0x2D) and regen/i-Pedal (0x2C). Any non-zero
     * value means the lamp is on; 0x00 is off. Returns null on no data so the caller can fall
     * back to the pedal/power heuristic.
     */
    fun parseBrakeLampOn(response: ObdRawResponse): Boolean? {
        val state = parseIsoTpPayload(response.value, BRAKE_LAMP_BC06_BYTE, CONFIRMATION_HEADER_BC06)
            ?: return null
        return state != 0
    }

    /** Vehicle speed in km/h from PID 220100 (header 7B3), byte 29. Null on no data. */
    fun parseVehicleSpeed(response: ObdRawResponse): Int? =
        parseIsoTpPayload(response.value, VEHICLE_SPEED_BYTE, CONFIRMATION_HEADER_0100)

    fun parseDecelerationState(response: ObdRawResponse, currentSpeedKmH: Int): Boolean {
        /**
         * Power-based regen-braking detection (speed-independent).
         * powerKw: calculated BMS pack power; negative = regen (current into the pack).
         * Brake-light output is driven by a hysteresis on powerKw. currentSpeedKmH is no
         * longer part of the trigger (010D is unsupported on the Ioniq 5); it is only carried
         * into the debug overlay so a future working speed source is visible.
         */
        val currentTimeMs = System.currentTimeMillis()
        val prevSpeedKmH = lastSpeedKmH

        // Battery current is a 16-bit SIGNED value (0.1 A); pack voltage is 16-bit UNSIGNED
        // (0.1 V). Reading a single byte (as before) made current always positive, so power
        // was never negative and the regen branch could never fire.
        val currentWord = parseIsoTpWord(response.value, AMPS_BYTE, CONFIRMATION_HEADER_0101)
        val voltageWord = parseIsoTpWord(response.value, VOLTAGE_BYTE, CONFIRMATION_HEADER_0101)

        if (currentWord == null || voltageWord == null) {
            lastDecelDebug = DecelDebug(
                speedKmH = currentSpeedKmH, prevSpeedKmH = prevSpeedKmH, deltaTimeS = 0f,
                accelMs2 = 0f, currentAmps = 0f, voltageVolts = 0f, powerKw = 0f,
                hysteresisOn = isBrakeLightOn, output = lastDecelState, recomputed = false,
                note = "no 0101 power data",
            )
            return lastDecelState
        }

        val currentAmps = currentWord.toShort().toFloat() * 0.1f
        val voltageVolts = voltageWord.toFloat() * 0.1f
        // Negative power = current flowing INTO the pack = regen braking.
        val powerKw = (currentAmps * voltageVolts) / 1000.0f

        // --- Primary trigger: regen power hysteresis (speed-independent) ---
        if (!isBrakeLightOn && powerKw <= REGEN_ON_THRESHOLD_KW) {
            isBrakeLightOn = true
        } else if (isBrakeLightOn && powerKw > REGEN_OFF_THRESHOLD_KW) {
            isBrakeLightOn = false
        }
        lastDecelState = isBrakeLightOn

        // --- Informational only: speed-derived accel, still surfaced in the debug overlay
        // so we can see whether a speed source ever comes back (010D / future PID). NOT used
        // for the trigger above. ---
        var deltaTimeS = 0f
        var accelerationMs2 = 0f
        var recomputed = false
        if (lastSpeedKmH >= 0 && lastTimeMs > 0) {
            deltaTimeS = (currentTimeMs - lastTimeMs) / 1000.0f
            if (deltaTimeS > 0.25f) {
                accelerationMs2 = (currentSpeedKmH / 3.6f - lastSpeedKmH / 3.6f) / deltaTimeS
                lastSpeedKmH = currentSpeedKmH
                lastTimeMs = currentTimeMs
                recomputed = true
            }
        } else {
            lastSpeedKmH = currentSpeedKmH
            lastTimeMs = currentTimeMs
        }

        lastDecelDebug = DecelDebug(
            speedKmH = currentSpeedKmH,
            prevSpeedKmH = prevSpeedKmH,
            deltaTimeS = deltaTimeS,
            accelMs2 = accelerationMs2,
            currentAmps = currentAmps,
            voltageVolts = voltageVolts,
            powerKw = powerKw,
            hysteresisOn = isBrakeLightOn,
            output = lastDecelState,
            recomputed = recomputed,
        )

        return lastDecelState
    }

    fun parseHeadlightState(response: ObdRawResponse): Boolean? {
        // BC10 index 4
        val bc10Val = parseIsoTpPayload(response.value, HEADLIGHT_BC10_STATE_BYTE, CONFIRMATION_HEADER_BC10)
        if (bc10Val != null) return (bc10Val and 0xF0) != 0
        
        // BC09 index 6
        val bc09Val = parseIsoTpPayload(response.value, HEADLIGHT_BC09_STATE_BYTE, CONFIRMATION_HEADER_BC09) ?: return null
        return bc09Val != 0
    }
}
