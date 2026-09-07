package de.skilltoremember.app

import android.content.Context
import android.speech.tts.TextToSpeech
import de.skilltoremember.app.api.ChatApi

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

    /** "Sofort zuhören": App-Start springt direkt in den Sprachdialog (Default aus). */
    fun isAutoListenEnabled(context: Context): Boolean = prefs(context).getBoolean("autoListen", false)

    fun setAutoListenEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("autoListen", enabled).apply()
    }

    /** Ob der einmalige Hinweis zur Spracherkennung schon bestätigt wurde. */
    fun isSpeechHintShown(context: Context): Boolean = prefs(context).getBoolean("speechHintShown", false)

    fun setSpeechHintShown(context: Context) {
        prefs(context).edit().putBoolean("speechHintShown", true).apply()
    }

    fun setVoiceName(context: Context, name: String) {
        prefs(context).edit().putString("voiceName", name).apply()
    }

    fun setRate(context: Context, rate: Float) {
        prefs(context).edit().putFloat("rate", rate).apply()
    }

    fun setPitch(context: Context, pitch: Float) {
        prefs(context).edit().putFloat("pitch", pitch).apply()
    }

    /**
     * Bereitet Antworttext fürs Vorlesen auf: Der angehängte Quellen-Block der
     * Websuche wird abgeschnitten (URLs vorlesen hilft niemandem — auf dem
     * Bildschirm bleiben sie stehen), Markdown-Reste werden entfernt.
     */
    fun textForSpeech(text: String): String =
        text.substringBefore("\n\n" + ChatApi.SOURCES_HEADING)
            .replace(Regex("[*_#`>|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

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
