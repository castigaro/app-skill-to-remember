package de.skilltoremember.app

import android.content.Context
import android.speech.tts.TextToSpeech

/**
 * Stimme, Tempo und Tonhöhe für die Sprachausgabe im Dialogmodus. Die
 * Standardstimme vieler Geräte klingt mit Rate 1.0 schleppend — deshalb ist
 * das Default-Tempo leicht angehoben.
 */
object VoiceSettings {

    const val DEFAULT_RATE = 1.15f
    const val DEFAULT_PITCH = 1.0f

    private fun prefs(context: Context) =
        context.getSharedPreferences("voice_settings", Context.MODE_PRIVATE)

    /** Technischer Name der gewählten Stimme ([android.speech.tts.Voice.getName]); leer = Systemstandard. */
    fun getVoiceName(context: Context): String = prefs(context).getString("voiceName", "")!!
    fun getRate(context: Context): Float = prefs(context).getFloat("rate", DEFAULT_RATE)
    fun getPitch(context: Context): Float = prefs(context).getFloat("pitch", DEFAULT_PITCH)

    fun setVoiceName(context: Context, name: String) {
        prefs(context).edit().putString("voiceName", name).apply()
    }

    fun setRate(context: Context, rate: Float) {
        prefs(context).edit().putFloat("rate", rate).apply()
    }

    fun setPitch(context: Context, pitch: Float) {
        prefs(context).edit().putFloat("pitch", pitch).apply()
    }

    /** Wendet die gespeicherten Werte auf eine [TextToSpeech]-Instanz an. */
    fun apply(context: Context, tts: TextToSpeech) {
        tts.setSpeechRate(getRate(context))
        tts.setPitch(getPitch(context))
        val wanted = getVoiceName(context)
        if (wanted.isNotBlank()) {
            // voices kann je nach Engine scheitern oder die Stimme fehlen — dann bleibt der Standard.
            runCatching { tts.voices?.firstOrNull { it.name == wanted } }.getOrNull()
                ?.let { tts.voice = it }
        }
    }
}
