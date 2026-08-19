package com.ioniqvitals.obd

data class ObdSnapshot(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val adapterName: String? = null,
    val socDisplayPercent: Float? = null,
    val sohPercent: Float? = null,
    val auxSocPercent: Float? = null,
    val coolantTempCelsius: Float? = null,
    val headlightsOn: Boolean? = null,
    val brakeLightsOn: Boolean? = null,
    val lastError: String? = null,
    
    // Debug info
    val socRaw: String? = null,
    val socIndex: Int? = null,
    val sohRaw: String? = null,
    val sohIndexHigh: Int? = null,
    val auxSocRaw: String? = null,
    val auxSocIndex: Int? = null,
    val coolantRaw: String? = null,
    val coolantIndex: Int? = null,
    val headlightsRaw: String? = null,
    val brakeLightsRaw: String? = null,

    // Enhanced Diagnostics
    val debugLog: String? = null,
    val loopDurationMs: Long? = null,
    val stitchedSoc: String? = null,
    val stitchedSoh: String? = null,
    val stitchedAux: String? = null,
    val stitchedCoolant: String? = null,
    val stitchedHeadlights: String? = null,
    val stitchedBrakes: String? = null,

    // Decel/regen brake heuristic internals (for on-screen debugging)
    val decelDebug: Ioniq5Pids.DecelDebug? = null,
)