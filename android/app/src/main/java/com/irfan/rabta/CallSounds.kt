package com.irfan.rabta

import android.media.AudioManager
import android.media.ToneGenerator

/** Caller-only ringback. Incoming sound belongs to Android's ringtone notification channel. */
class CallSounds {
    private var tone:ToneGenerator?=null
    fun ringing() {
        if(tone!=null) return
        try { tone=ToneGenerator(AudioManager.STREAM_VOICE_CALL,60).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) } } catch(_:Exception) { stop() }
    }
    fun stop() { runCatching { tone?.stopTone(); tone?.release() }; tone=null }
}
