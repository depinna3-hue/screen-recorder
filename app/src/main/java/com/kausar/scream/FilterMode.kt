package com.kausar.scream

/** Mode filter sistem (NoiseSuppressor / AGC / AEC). */
enum class FilterMode {
    ORIGINAL,      // Murni, tanpa efek sistem
    VOICE,         // NS + AGC — suara bicara
    QUIET_SOUNDS,  // AGC saja — suara pelan
    MUSIC,         // Tanpa efek — dinamika alami
    OUTDOOR        // NS — kurangi angin/bising
}
