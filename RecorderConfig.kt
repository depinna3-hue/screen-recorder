package com.kausar.scream

/** Pengaturan rekaman yang dipilih user di UI. */
data class RecorderConfig(
    val mode: Int,
    val sampleRate: Int,
    val audioQuality: Int,   // 0=tinggi 1=sedang 2=rendah
    val videoQuality: Int,   // 0=480p 1=720p 2=1080p (hanya layar)
    val audioFormat: Int,    // 0=AAC 1=MP3 2=WAV (hanya suara)
    val isScreen: Boolean
) {
    fun audioBitrate(): Int = when (audioQuality) {
        0 -> 256_000
        1 -> 128_000
        else -> 64_000
    }

    fun videoSize(displayW: Int, displayH: Int): Pair<Int, Int> {
        val targetH = when (videoQuality) {
            0 -> 480
            1 -> 720
            else -> 1080
        }
        val scale = targetH.toFloat() / displayH.coerceAtLeast(1)
        var w = (displayW * scale).toInt()
        var h = targetH
        // MediaRecorder butuh genap
        if (w % 2 != 0) w++
        if (h % 2 != 0) h++
        return w to h
    }

    fun videoBitrate(): Int = when (videoQuality) {
        0 -> 2_000_000
        1 -> 5_000_000
        else -> 10_000_000
    }

    companion object {
        // Mode layar
        const val SCREEN_MIC = 0
        const val SCREEN_INTERNAL = 1
        const val SCREEN_INTERNAL_ONLY = 2
        // Mode suara
        const val AUDIO_MIC = 0
        const val AUDIO_INTERNAL = 1
        const val AUDIO_INTERNAL_MIC = 2

        const val FMT_AAC = 0
        const val FMT_MP3 = 1
        const val FMT_WAV = 2
    }
}
