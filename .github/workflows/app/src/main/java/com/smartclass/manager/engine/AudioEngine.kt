package com.smartclass.manager.engine

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import com.smartclass.manager.core.ErrorTracker
import java.util.Locale

class AudioEngine(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isTtsReady = false
    private val toneGen: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 85)
    } catch (t: Throwable) {
        ErrorTracker.recordError("AudioEngine", "initToneGenerator", t)
        null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ar"))
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    fun playCountdownTick() {
        ErrorTracker.safeRun("AudioEngine", "playCountdownTick") {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
        }
    }

    fun playSelectedChime() {
        ErrorTracker.safeRun("AudioEngine", "playSelectedChime") {
            toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 260)
        }
    }

    fun playSuccessTone() {
        ErrorTracker.safeRun("AudioEngine", "playSuccessTone") {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
        }
    }

    fun playGentleMissTone() {
        ErrorTracker.safeRun("AudioEngine", "playGentleMissTone") {
            toneGen?.startTone(ToneGenerator.TONE_PROP_NACK, 200)
        }
    }

    fun speakArabicName(name: String, speechRate: Float) {
        if (!isTtsReady || name.isBlank()) return
        ErrorTracker.safeRun("AudioEngine", "speakArabicName") {
            tts?.setSpeechRate(speechRate.coerceIn(0.5f, 1.8f))
            tts?.speak(name, TextToSpeech.QUEUE_FLUSH, null, "student_name")
        }
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        toneGen?.release()
    }
}
