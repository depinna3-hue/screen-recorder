package com.kausar.scream

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.util.Log

/**
 * Efek sistem Android (Equalizer, BassBoost, LoudnessEnhancer, PresetReverb).
 * Dipasang pada audioSessionId dari AudioRecord saat merekam.
 */
class AudioEffectController(private val audioSessionId: Int) {

    companion object {
        private const val TAG = "AudioEffectCtrl"
    }

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var presetReverb: PresetReverb? = null

    /**
     * @param bassStrength 0–1000
     * @param loudnessMb target gain mB (0–1000)
     * @param reverbPreset PresetReverb.PRESET_* atau -1 = off
     * @param eqPreset index equalizer preset, -1 = flat/default
     */
    fun enable(
        bassStrength: Int = 0,
        loudnessMb: Int = 0,
        reverbPreset: Short = -1,
        eqPreset: Int = -1
    ) {
        release()
        if (audioSessionId == 0) return
        try {
            equalizer = Equalizer(0, audioSessionId).also { eq ->
                eq.enabled = true
                if (eqPreset >= 0 && eqPreset < eq.numberOfPresets) {
                    try { eq.usePreset(eqPreset.toShort()) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Equalizer", e)
        }
        try {
            if (bassStrength > 0) {
                bassBoost = BassBoost(0, audioSessionId).also {
                    it.enabled = true
                    it.setStrength(bassStrength.coerceIn(0, 1000).toShort())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "BassBoost", e)
        }
        try {
            if (loudnessMb > 0) {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId).also {
                    it.enabled = true
                    it.setTargetGain(loudnessMb.coerceIn(0, 2000))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "LoudnessEnhancer", e)
        }
        try {
            if (reverbPreset >= 0) {
                presetReverb = PresetReverb(0, audioSessionId).also {
                    it.preset = reverbPreset
                    it.enabled = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PresetReverb", e)
        }
    }

    /** Terapkan sesuai FilterMode + effect DSP (gema pakai PresetReverb). */
    fun applyForModes(filter: FilterMode, effectMode: Int) {
        val bass = when (filter) {
            FilterMode.MUSIC -> 400
            FilterMode.VOICE -> 200
            FilterMode.OUTDOOR -> 100
            else -> 0
        }
        val loud = when (filter) {
            FilterMode.QUIET_SOUNDS -> 600
            FilterMode.VOICE -> 300
            else -> 0
        }
        val reverb: Short = when (effectMode) {
            RecordingService.EFFECT_REVERB -> PresetReverb.PRESET_LARGEHALL
            RecordingService.EFFECT_ECHO -> PresetReverb.PRESET_MEDIUMROOM
            else -> (-1).toShort()
        }
        val eqPreset = when (filter) {
            FilterMode.VOICE -> 0 // sering "Normal" / pertama
            FilterMode.MUSIC -> 1
            else -> -1
        }
        enable(bassStrength = bass, loudnessMb = loud, reverbPreset = reverb, eqPreset = eqPreset)
    }

    fun release() {
        try { equalizer?.release() } catch (_: Exception) {}
        equalizer = null
        try { bassBoost?.release() } catch (_: Exception) {}
        bassBoost = null
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        loudnessEnhancer = null
        try { presetReverb?.release() } catch (_: Exception) {}
        presetReverb = null
    }
}
