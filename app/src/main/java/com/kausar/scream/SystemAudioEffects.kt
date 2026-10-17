package com.kausar.scream

import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Efek audio bawaan Android (bukan DSP kustom).
 * - NoiseSuppressor: kurangi bising
 * - AutomaticGainControl (AGC): ratakan volume
 * - AcousticEchoCanceler (AEC): kurangi gema speaker
 */
class SystemAudioEffects(
    private val audioSessionId: Int
) {
    companion object {
        private const val TAG = "SystemAudioFX"

        fun getAudioSourceForMode(mode: FilterMode): Int {
            return when (mode) {
                FilterMode.ORIGINAL -> MediaRecorder.AudioSource.MIC
                FilterMode.VOICE -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                FilterMode.QUIET_SOUNDS -> MediaRecorder.AudioSource.MIC
                FilterMode.MUSIC -> MediaRecorder.AudioSource.MIC
                FilterMode.OUTDOOR -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }
        }
    }

    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var currentMode: FilterMode = FilterMode.ORIGINAL

    fun checkAvailability(): EffectAvailability {
        return EffectAvailability(
            noiseSuppressor = NoiseSuppressor.isAvailable(),
            automaticGainControl = AutomaticGainControl.isAvailable(),
            acousticEchoCanceler = AcousticEchoCanceler.isAvailable()
        )
    }

    fun applyMode(mode: FilterMode) {
        currentMode = mode
        releaseEffects()
        when (mode) {
            FilterMode.ORIGINAL, FilterMode.MUSIC -> { /* tanpa efek sistem */ }
            FilterMode.VOICE -> {
                enableNoiseSuppressor()
                enableAutomaticGainControl()
            }
            FilterMode.QUIET_SOUNDS -> enableAutomaticGainControl()
            FilterMode.OUTDOOR -> enableNoiseSuppressor()
        }
        Log.d(TAG, "mode=$mode status=${getStatus()}")
    }

    private fun enableNoiseSuppressor() {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)
                noiseSuppressor?.enabled = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "NS init", e)
        }
    }

    private fun enableAutomaticGainControl() {
        try {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(audioSessionId)
                automaticGainControl?.enabled = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "AGC init", e)
        }
    }

    @Suppress("unused")
    private fun enableAcousticEchoCanceler() {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)
                acousticEchoCanceler?.enabled = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "AEC init", e)
        }
    }

    fun releaseEffects() {
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        try { automaticGainControl?.release() } catch (_: Exception) {}
        automaticGainControl = null
        try { acousticEchoCanceler?.release() } catch (_: Exception) {}
        acousticEchoCanceler = null
    }

    fun getStatus(): EffectStatus = EffectStatus(
        mode = currentMode,
        nsEnabled = noiseSuppressor?.enabled == true,
        agcEnabled = automaticGainControl?.enabled == true,
        aecEnabled = acousticEchoCanceler?.enabled == true
    )

    data class EffectAvailability(
        val noiseSuppressor: Boolean,
        val automaticGainControl: Boolean,
        val acousticEchoCanceler: Boolean
    )

    data class EffectStatus(
        val mode: FilterMode,
        val nsEnabled: Boolean,
        val agcEnabled: Boolean,
        val aecEnabled: Boolean
    )
}
